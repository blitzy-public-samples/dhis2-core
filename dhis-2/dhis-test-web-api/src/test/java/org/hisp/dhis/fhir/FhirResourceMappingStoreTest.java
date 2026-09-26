/*
 * Copyright (c) 2004-2026, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors 
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.fhir;

import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;
import static org.junit.jupiter.api.Assertions.*;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.JsonMixed;
import org.hisp.dhis.test.webapi.PostgresControllerIntegrationTestBase;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Tests {@link FhirResourceMapping} persistence on PostgreSQL: sharing-free lookup, schema
 * contract, partial unique indexes, concurrent creation and the resolution guard.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ContextConfiguration(classes = FhirPostgresControllerTestBase.FhirApiEnabledConfig.class)
class FhirResourceMappingStoreTest extends PostgresControllerIntegrationTestBase {
  private static final String TABLE = "fhirresourcemapping";
  private static final String MAPPING_HBM =
      "org/hisp/dhis/fhir/mapping/hibernate/FhirResourceMapping.hbm.xml";
  private static final String IDENTIFIABLE_PROPERTIES_HBM =
      "org/hisp/dhis/common/identifiableProperties.hbm";
  private static final Set<String> MAPPED_ELEMENTS = Set.of("id", "property", "many-to-one");
  private static final String TRACKED_ENTITY_TYPE_UID = "ja8NY4PW7Xm";
  private static final String PROGRAM_UID = "BFcipDERJnf";
  private static final String PROGRAM_STAGE_UID = "NpsdDv6kKSO";
  private static final String PATIENT_UID = "dUE514NMOlo";
  private static final String COUNTS_BY_TYPE =
      "select resourcetype, count(*)::text from " + TABLE + " group by resourcetype";

  /**
   * The schema contract of table {@code fhirresourcemapping}, one row per persisted model property:
   * property | HBM element | column | udt | length | nullable | default | constraint type (p =
   * primary key, u = unique, f = foreign key) | constraint name | referenced table.
   */
  private static final List<ContractRow> CONTRACT =
      """
      id                | id          | fhirresourcemappingid | int8      |     | NO  |             | p | fhirresourcemapping_pkey                   |
      uid               | property    | uid                   | varchar   | 11  | NO  |             | u | fhirresourcemapping_uid_key                |
      code              | property    | code                  | varchar   | 50  | YES |             | u | fhirresourcemapping_code_key               |
      created           | property    | created               | timestamp |     | NO  |             |   |                                            |
      lastUpdated       | property    | lastupdated           | timestamp |     | NO  |             |   |                                            |
      lastUpdatedBy     | many-to-one | lastupdatedby         | int8      |     | YES |             | f | fk_lastupdateby_userid                     | userinfo
      name              | property    | name                  | varchar   | 230 | NO  |             | u | fhirresourcemapping_name_key               |
      createdBy         | many-to-one | userid                | int8      |     | YES |             | f | fk_fhirresourcemapping_userid              | userinfo
      translations      | property    | translations          | jsonb     |     | YES |             |   |                                            |
      sharing           | property    | sharing               | jsonb     |     | YES | '{}'::jsonb |   |                                            |
      attributeValues   | property    | attributevalues       | jsonb     |     | YES | '{}'::jsonb |   |                                            |
      resourceType      | property    | resourcetype          | varchar   | 50  | NO  |             |   |                                            |
      trackedEntityType | many-to-one | trackedentitytypeid   | int8      |     | NO  |             | f | fk_fhirresourcemapping_trackedentitytypeid | trackedentitytype
      program           | many-to-one | programid             | int8      |     | YES |             | f | fk_fhirresourcemapping_programid           | program
      programStage      | many-to-one | programstageid        | int8      |     | YES |             | f | fk_fhirresourcemapping_programstageid      | programstage
      fieldMappings     | property    | fieldmappings         | jsonb     |     | NO  | '[]'::jsonb |   |                                            |
      """
          .lines()
          .map(ContractRow::parse)
          .toList();

  /**
   * The partial unique indexes of table {@code fhirresourcemapping}: name | validator uniqueness
   * key it enforces | blank-separated fragments of its normalised definition.
   */
  private static final List<IndexContract> PARTIAL_INDEXES =
      """
      ux_fhirresourcemapping_patient      | PATIENT                                | usingbtree(resourcetype)where (resourcetype)='patient'
      ux_fhirresourcemapping_stage        | ENCOUNTER:{stage}, OBSERVATION:{stage} | usingbtree(resourcetype,programstageid)where (resourcetype)=any 'encounter','observation'
      ux_fhirresourcemapping_immunization | IMMUNIZATION:{stage}:{administered DE} | usingbtree(programstageid,((jsonb_path_query_first(fieldmappings, @.target==immunization_administered .source')#>>'{}')))where (resourcetype)='immunization'
      """
          .lines()
          .map(IndexContract::parse)
          .toList();

  @Autowired private FhirResourceMappingStore store;
  @Autowired private TestSetup testSetup;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;
  private final AtomicInteger uidSequence = new AtomicInteger();
  private long trackedEntityTypeId;
  private long programId;
  private long programStageId;

  @BeforeAll
  void importTrackerMetadata() throws IOException {
    deleteAllMappings();
    testSetup.importMetadata();
    manager.flush();
    manager.clear();
    trackedEntityTypeId = idOf("trackedentitytype", "trackedentitytypeid", TRACKED_ENTITY_TYPE_UID);
    programId = idOf("program", "programid", PROGRAM_UID);
    programStageId = idOf("programstage", "programstageid", PROGRAM_STAGE_UID);
  }

  @Test
  void getByResourceTypeNoAclIgnoresSharing() {
    String hidden = insertCommitted("PATIENT", null, null, "[]");
    String visible = insertCommitted("OBSERVATION", programId, programStageId, "[]");
    setPublicSharing(hidden, "--------");
    setPublicSharing(visible, "rw------");
    manager.clear();
    switchToNewUser("fhir-plain");
    List<String> sharedWithUser = inTransaction(() -> uids(store.getAll()));
    assertAll(
        () -> assertFalse(sharedWithUser.contains(hidden), "hidden mapping visible"),
        () -> assertTrue(sharedWithUser.contains(visible), "public mapping not visible"),
        () -> assertEquals(List.of(hidden), noAclUids(FhirResourceType.PATIENT)),
        () -> assertEquals(List.of(visible), noAclUids(FhirResourceType.OBSERVATION)),
        () -> assertEquals(List.of(), noAclUids(FhirResourceType.ENCOUNTER)));
  }

  @Test
  void schemaMatchesContract() throws Exception {
    Set<String> modelProperties =
        Arrays.stream(Introspector.getBeanInfo(FhirResourceMapping.class).getPropertyDescriptors())
            .filter(descriptor -> descriptor.getReadMethod() != null)
            .map(PropertyDescriptor::getName)
            .collect(toSet());
    Map<String, String> hbm = new TreeMap<>();
    Map<String, String> columns = new TreeMap<>();
    Map<String, String> constraints = new TreeMap<>();
    Map<String, String> constraintIndexes = new TreeMap<>();
    for (ContractRow row : CONTRACT) {
      hbm.put(row.property(), row.hbm());
      columns.put(row.column(), row.migratedColumn());
      if (!row.constraintType().isEmpty()) {
        constraints.put(row.constraintName(), row.constraintDefinition());
      }
      if (row.constraintType().matches("[pu]")) {
        constraintIndexes.put(row.constraintName(), row.constraintIndexDefinition());
      }
    }
    Map<String, String> indexes = migratedIndexes();
    Map<String, String> otherIndexes = new TreeMap<>(indexes);
    PARTIAL_INDEXES.forEach(index -> otherIndexes.remove(index.name()));
    List<String> unmodelled =
        hbm.keySet().stream().filter(p -> !modelProperties.contains(p)).toList();
    Set<String> modelOwned = new TreeSet<>(hbm.keySet());
    Class<?> type = FhirResourceMapping.class;
    while ((type = type.getSuperclass()) != Object.class)
      modelOwned.removeAll(instanceFields(type));
    assertAll(
        () -> assertEquals(List.of(), unmodelled, "properties missing from the model"),
        () -> assertEquals(modelOwned, instanceFields(FhirResourceMapping.class), "model fields"),
        () -> assertEquals(hbm, parseHbmElements()),
        () -> assertEquals(columns, migratedColumns()),
        () -> assertEquals(constraints, migratedConstraints()),
        () -> assertEquals(constraintIndexes, otherIndexes),
        () ->
            assertAll(PARTIAL_INDEXES.stream().map(i -> () -> i.assertIn(indexes.get(i.name())))));
  }

  @Test
  void uniqueIndexesRejectDuplicates() {
    String first = administered("DATAEL00001");
    insertCommitted("PATIENT", null, null, "[]");
    insertCommitted("ENCOUNTER", programId, programStageId, "[]");
    insertCommitted("OBSERVATION", programId, programStageId, "[]");
    insertCommitted("IMMUNIZATION", programId, programStageId, first);
    assertAll(
        () -> assertRejected("ux_fhirresourcemapping_patient", "PATIENT", "[]"),
        () -> assertRejected("ux_fhirresourcemapping_stage", "ENCOUNTER", "[]"),
        () -> assertRejected("ux_fhirresourcemapping_stage", "OBSERVATION", "[]"),
        () -> assertRejected("ux_fhirresourcemapping_immunization", "IMMUNIZATION", first));
    insertCommitted("IMMUNIZATION", programId, programStageId, administered("DATAEL00002"));
    assertEquals(
        Map.of("ENCOUNTER", "1", "IMMUNIZATION", "2", "OBSERVATION", "1", "PATIENT", "1"),
        queryPairs(COUNTS_BY_TYPE));
  }

  @Test
  void concurrentCreationCommitsExactlyOne() throws InterruptedException {
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<String> insert = () -> newTransaction().execute(status -> insertAfter(barrier));
      List<Throwable> failures = new ArrayList<>();
      for (Future<String> future : executor.invokeAll(List.of(insert, insert), 20, SECONDS)) {
        try {
          future.get();
        } catch (ExecutionException | CancellationException ex) {
          failures.add(ex.getCause() != null ? ex.getCause() : ex);
        }
      }
      assertAll(
          () -> assertEquals(1, failures.size(), "failed insertions: " + failures),
          () -> assertInstanceOf(DataIntegrityViolationException.class, failures.get(0)),
          () -> assertEquals(Map.of("PATIENT", "1"), queryPairs(COUNTS_BY_TYPE)));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, SECONDS), "executor terminated");
    }
  }

  @Test
  void metadataImportBypassIsContainedAtResolution() {
    String uid = nextUid();
    String bundle =
        """
        {"fhirResourceMappings": [{"id": "%s", "name": "FHIR store test %s",
          "resourceType": "PATIENT", "trackedEntityType": {"id": "%s"}, "fieldMappings": [
            {"target": "PATIENT_IDENTIFIER", "sourceType": "ATTRIBUTE", "source": "integerAttr"}
          ]}]}
        """
            .formatted(uid, uid, TRACKED_ENTITY_TYPE_UID);
    JsonMixed imported = POST("/metadata?skipValidation=true", bundle).content(HttpStatus.OK);
    assertEquals("OK", imported.getString("response.status").string());
    manager.clear();
    assertEquals(List.of(uid), noAclUids(FhirResourceType.PATIENT));
    FhirPostgresControllerTestBase.assertOutcome(
        GET("/fhir/Patient/{id}", PATIENT_UID),
        HttpStatus.NOT_IMPLEMENTED,
        IssueType.NOTSUPPORTED,
        diagnostics -> diagnostics.contains("Patient"));
  }

  /** Asserts that inserting another mapping of the type violates the given unique index. */
  private void assertRejected(String index, String resourceType, String fieldMappings) {
    Long program = "PATIENT".equals(resourceType) ? null : programId;
    Long stage = program == null ? null : programStageId;
    DataIntegrityViolationException ex =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> insertCommitted(resourceType, program, stage, fieldMappings));
    assertTrue(String.valueOf(ex.getMessage()).contains(index), ex.getMessage());
  }

  /** Returns each column of the table as {@code "udt length nullable default"}. */
  private Map<String, String> migratedColumns() {
    return queryPairs(
        "select column_name, concat_ws(' ', udt_name, coalesce(character_maximum_length::text, ''),"
            + " is_nullable, coalesce(column_default, '')) from information_schema.columns"
            + " where table_schema = current_schema() and table_name = ?",
        TABLE);
  }

  private Map<String, String> migratedConstraints() {
    return queryPairs(
        "select conname, lower(pg_get_constraintdef(oid)) from pg_constraint"
            + " where conrelid = 'fhirresourcemapping'::regclass and contype <> 'n'");
  }

  /** Returns each index definition without schema, blanks, quotes and casts, in lower case. */
  private Map<String, String> migratedIndexes() {
    Map<String, String> indexes =
        queryPairs(
            "select indexname, lower(replace(indexdef, ' ON ' || schemaname || '.', ' ON '))"
                + " from pg_indexes where schemaname = current_schema() and tablename = ?",
            TABLE);
    indexes.replaceAll(
        (name, def) -> def.replaceAll("[\\s\"]", "").replaceAll("::[a-z]+(\\[])?", ""));
    return indexes;
  }

  private Map<String, String> queryPairs(String sql, Object... args) {
    Map<String, String> rows = new TreeMap<>();
    jdbcTemplate.query(
        sql, (RowCallbackHandler) rs -> rows.put(rs.getString(1), rs.getString(2)), args);
    return rows;
  }

  /** Returns each mapped element of the HBM and its fragment, as {@link ContractRow#hbm()}. */
  private static Map<String, String> parseHbmElements() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
    Map<String, String> elements = new TreeMap<>();
    String fragment = "<root>" + readClasspath(IDENTIFIABLE_PROPERTIES_HBM) + "</root>";
    for (String xml : List.of(readClasspath(MAPPING_HBM), fragment)) {
      NodeList nodes =
          builder.parse(new InputSource(new StringReader(xml))).getElementsByTagName("*");
      for (int i = 0; i < nodes.getLength(); i++) {
        Element element = (Element) nodes.item(i);
        if (MAPPED_ELEMENTS.contains(element.getTagName())) {
          String property = element.getAttribute("name");
          assertNull(elements.put(property, hbmElement(element)), "HBM repeats " + property);
        }
      }
    }
    return elements;
  }

  private static String hbmElement(Element element) {
    Element child = (Element) element.getElementsByTagName("column").item(0);
    Element column = child == null ? element : child;
    String name = child == null ? element.getAttribute("column") : child.getAttribute("name");
    boolean nullable =
        !"id".equals(element.getTagName()) && !"true".equals(column.getAttribute("not-null"));
    return String.join(
        " ",
        element.getTagName(),
        (name.isEmpty() ? element.getAttribute("name") : name).toLowerCase(Locale.ROOT),
        column.getAttribute("length"),
        nullable ? "YES" : "NO",
        String.valueOf("true".equals(column.getAttribute("unique"))),
        element.getAttribute("foreign-key"));
  }

  private static String readClasspath(String path) throws IOException {
    return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
  }

  private String insertCommitted(String resourceType, Long program, Long stage, String fields) {
    String uid = nextUid();
    newTransaction()
        .executeWithoutResult(status -> insertRow(uid, resourceType, program, stage, fields));
    return uid;
  }

  /** Inserts a row with plain JDBC: empty translations, default sharing and attribute values. */
  private void insertRow(String uid, String resourceType, Long program, Long stage, String fields) {
    int text = Types.VARCHAR;
    int id = Types.BIGINT;
    jdbcTemplate.update(
        "insert into fhirresourcemapping (fhirresourcemappingid, uid, name, created, lastupdated,"
            + " translations, resourcetype, trackedentitytypeid, programid, programstageid,"
            + " fieldmappings) values (nextval('hibernate_sequence'), ?, 'FHIR store test ' || ?,"
            + " now(), now(), '[]'::jsonb, ?, ?, ?, ?, ?::jsonb)",
        new Object[] {uid, uid, resourceType, trackedEntityTypeId, program, stage, fields},
        new int[] {text, text, text, id, id, id, text});
  }

  private void setPublicSharing(String uid, String publicAccess) {
    String sharing =
        "{\"public\":\"%s\",\"owner\":\"%s\",\"users\":{},\"userGroups\":{}}"
            .formatted(publicAccess, getAdminUid());
    String sql = "update fhirresourcemapping set sharing = ?::jsonb where uid = ?";
    newTransaction().executeWithoutResult(status -> jdbcTemplate.update(sql, sharing, uid));
  }

  @AfterEach
  void deleteAllMappings() {
    manager.clear();
    newTransaction()
        .executeWithoutResult(status -> jdbcTemplate.update("delete from fhirresourcemapping"));
    manager.clear();
  }

  @AfterAll
  void deleteFixtureMappings() {
    deleteAllMappings();
  }

  private long idOf(String table, String idColumn, String uid) {
    String sql = "select " + idColumn + " from " + table + " where uid = ?";
    Long id = jdbcTemplate.queryForObject(sql, Long.class, uid);
    assertNotNull(id, table + " " + uid);
    return id;
  }

  private List<String> noAclUids(FhirResourceType type) {
    return inTransaction(() -> uids(store.getByResourceTypeNoAcl(type)));
  }

  private <T> T inTransaction(Supplier<T> operation) {
    TransactionTemplate template = newTransaction();
    template.setReadOnly(true);
    return template.execute(status -> operation.get());
  }

  private TransactionTemplate newTransaction() {
    TransactionTemplate template =
        new TransactionTemplate(transactionTemplate.getTransactionManager());
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }

  private static List<String> uids(List<FhirResourceMapping> mappings) {
    return mappings.stream().map(FhirResourceMapping::getUid).sorted().toList();
  }

  private static String administered(String dataElementUid) {
    return "[{\"target\":\"IMMUNIZATION_ADMINISTERED\",\"sourceType\":\"DATA_ELEMENT\",\"source\":\"%s\"}]"
        .formatted(dataElementUid);
  }

  private String nextUid() {
    return "FhirStr%04d".formatted(uidSequence.incrementAndGet());
  }

  /** Inserts a Patient mapping once both concurrent transactions have reached the barrier. */
  private String insertAfter(CyclicBarrier barrier) {
    String uid = nextUid();
    try {
      barrier.await(10, SECONDS);
    } catch (Exception ex) {
      throw new IllegalStateException("Concurrent insertions did not start together", ex);
    }
    insertRow(uid, "PATIENT", null, null, "[]");
    return uid;
  }

  /** Returns the names of the non-static, non-transient, non-synthetic fields of the type. */
  private static Set<String> instanceFields(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .filter(f -> !f.isSynthetic())
        .filter(f -> (f.getModifiers() & (Modifier.STATIC | Modifier.TRANSIENT)) == 0)
        .map(Field::getName)
        .collect(toCollection(TreeSet::new));
  }

  /** One row of the schema contract; an empty cell is an empty string. */
  private record ContractRow(
      String property,
      String element,
      String column,
      String udtName,
      String length,
      String nullable,
      String columnDefault,
      String constraintType,
      String constraintName,
      String referencedTable) {
    static ContractRow parse(String line) {
      String[] c = Arrays.stream(line.split("\\|", -1)).map(String::strip).toArray(String[]::new);
      assertEquals(10, c.length, line);
      return new ContractRow(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9]);
    }

    String constraintDefinition() {
      return switch (constraintType) {
        case "p" -> "primary key (" + column + ")";
        case "u" -> "unique (" + column + ")";
        case "f" ->
            "foreign key (%s) references %s(%sid)"
                .formatted(column, referencedTable, referencedTable);
        default -> throw new IllegalArgumentException(constraintType);
      };
    }

    String constraintIndexDefinition() {
      return "createuniqueindex%son%susingbtree(%s)".formatted(constraintName, TABLE, column);
    }

    /** Returns {@code "element column length nullable unique foreignKey"}. */
    String hbm() {
      String foreignKey = "f".equals(constraintType) ? constraintName : "";
      String unique = String.valueOf("u".equals(constraintType));
      return String.join(" ", element, column, length, nullable, unique, foreignKey);
    }

    String migratedColumn() {
      return String.join(" ", udtName, length, nullable, columnDefault);
    }
  }

  /** A partial unique index with the validator uniqueness key it enforces. */
  private record IndexContract(String name, String uniquenessKey, List<String> fragments) {
    static IndexContract parse(String line) {
      String[] cells = Arrays.stream(line.split("\\|")).map(String::strip).toArray(String[]::new);
      assertEquals(3, cells.length, line);
      return new IndexContract(cells[0], cells[1], List.of(cells[2].split(" ")));
    }

    void assertIn(String definition) {
      String description = name + " enforcing " + uniquenessKey + ": " + definition;
      assertNotNull(definition, description);
      assertTrue(definition.startsWith("createuniqueindex" + name), description);
      List<String> absent = fragments.stream().filter(f -> !definition.contains(f)).toList();
      assertEquals(List.of(), absent, description);
    }
  }
}
