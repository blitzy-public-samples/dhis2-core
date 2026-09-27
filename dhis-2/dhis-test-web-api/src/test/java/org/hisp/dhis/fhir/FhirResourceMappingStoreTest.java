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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.*;
import static org.hisp.dhis.fhir.FhirResourceMappingStoreTest.FhirPostgresControllerTestBase.*;
import static org.hisp.dhis.fhir.FhirResourceMappingStoreTest.FhirResponses.*;
import static org.hisp.dhis.fhir.FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.hisp.dhis.http.HttpAssertions.assertStatus;
import static org.hisp.dhis.http.HttpClientAdapter.Header;
import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.beans.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.URLDecoder;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import java.util.stream.Stream;
import javax.xml.parsers.*;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.*;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.test.config.PostgresDhisConfigurationProvider;
import org.hisp.dhis.test.webapi.PostgresControllerIntegrationTestBase;
import org.hisp.dhis.test.webapi.json.domain.*;
import org.hisp.dhis.user.User;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.OperationOutcome.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Tests {@link FhirResourceMapping} persistence on PostgreSQL: schema, uniqueness and lookups. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ContextConfiguration(classes = FhirApiEnabledConfig.class)
class FhirResourceMappingStoreTest extends PostgresControllerIntegrationTestBase {
  private static final String TABLE = "fhirresourcemapping";
  private static final String SCRATCH_TABLE = "fhir_index_scratch";
  private static final String MAPPING_HBM =
      "org/hisp/dhis/fhir/mapping/hibernate/FhirResourceMapping.hbm.xml";
  private static final String IDENTIFIABLE_PROPERTIES_HBM =
      "org/hisp/dhis/common/identifiableProperties.hbm";
  private static final String PROGRAM_STAGE_UID = "NpsdDv6kKSO";
  private static final String COUNTS_BY_TYPE =
      "select resourcetype, count(*)::text from " + TABLE + " group by resourcetype";
  private static final String COLUMNS_SQL =
      "select column_name, concat_ws(' ', udt_name, coalesce(character_maximum_length::text, ''),"
          + " is_nullable, coalesce(column_default, '')) from information_schema.columns"
          + " where table_schema = current_schema() and table_name = ?";
  private static final String CONSTRAINTS_SQL =
      "select conname, lower(pg_get_constraintdef(oid)) from pg_constraint"
          + " where conrelid = 'fhirresourcemapping'::regclass and contype <> 'n'";
  private static final String INDEXES_SQL =
      "select i.relname::text, regexp_replace(pg_get_indexdef(i.oid), ' ON \\S+', '')"
          + " from pg_index join pg_class i on i.oid = indexrelid where indrelid = ?::regclass";
  private static final Map<String, String> CONSTRAINTS =
      Map.of(
          "p", "primary key (%s)",
          "u", "unique (%s)",
          "f", "foreign key (%s) references %2$s(%2$sid)");

  /**
   * Schema contract, one row per persisted property: property | HBM element | column | udt | length
   * | nullable | default | constraint type (p primary key, u unique, f foreign key) | name | table.
   */
  private static final String CONTRACT =
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
      """;

  /** Partial unique indexes: name | validator uniqueness key | SQL key expressions | predicate. */
  private static final List<IndexContract> PARTIAL_INDEXES =
      """
      ux_fhirresourcemapping_patient      | PATIENT                                | resourcetype                 | resourcetype = 'PATIENT'
      ux_fhirresourcemapping_stage        | ENCOUNTER:{stage}, OBSERVATION:{stage} | resourcetype, programstageid | resourcetype in ('ENCOUNTER', 'OBSERVATION')
      ux_fhirresourcemapping_immunization | IMMUNIZATION:{stage}:{administered DE} | programstageid, (jsonb_path_query_first(fieldmappings, '$[*] ? (@.target == "IMMUNIZATION_ADMINISTERED").source') #>> '{}') | resourcetype = 'IMMUNIZATION'
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
    trackedEntityTypeId = idOf("trackedentitytype", PERSON_TYPE);
    programId = idOf("program", PROGRAM);
    programStageId = idOf("programstage", PROGRAM_STAGE_UID);
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
        () -> assertEquals(List.of(hidden), noAclUids(PATIENT)),
        () -> assertEquals(List.of(visible), noAclUids(OBSERVATION)),
        () -> assertEquals(List.of(), noAclUids(ENCOUNTER)));
  }

  @Test
  void schemaMatchesContract() throws Exception {
    Map<String, String> hbm = new TreeMap<>();
    Map<String, String> columns = new TreeMap<>();
    Map<String, String> constraints = new TreeMap<>();
    Map<String, String> constraintIndexes = new TreeMap<>();
    for (String[] c : CONTRACT.lines().map(line -> cells(line, 10)).toList()) {
      String type = c[7];
      String unique = String.valueOf(type.equals("u"));
      hbm.put(c[0], String.join(" ", c[1], c[2], c[4], c[5], unique, type.equals("f") ? c[8] : ""));
      columns.put(c[2], String.join(" ", c[3], c[4], c[5], c[6]));
      if (!type.isEmpty()) constraints.put(c[8], CONSTRAINTS.get(type).formatted(c[2], c[9]));
      if (type.matches("[pu]"))
        constraintIndexes.put(c[8], "CREATE UNIQUE INDEX " + c[8] + " USING btree (" + c[2] + ")");
    }
    Map<String, String> indexes = queryPairs(INDEXES_SQL, TABLE);
    Map<String, String> otherIndexes = new TreeMap<>(indexes);
    PARTIAL_INDEXES.forEach(index -> otherIndexes.remove(index.name()));
    List<String> unmodelled = new ArrayList<>(hbm.keySet());
    for (var d : Introspector.getBeanInfo(FhirResourceMapping.class).getPropertyDescriptors())
      if (d.getReadMethod() != null) unmodelled.remove(d.getName());
    Set<String> modelOwned = new TreeSet<>(hbm.keySet());
    Class<?> type = FhirResourceMapping.class;
    while ((type = type.getSuperclass()) != Object.class)
      modelOwned.removeAll(instanceFields(type));
    IndexContract patient = PARTIAL_INDEXES.get(0);
    IndexContract stage = PARTIAL_INDEXES.get(1);
    String predicate = patient.predicate() + " or resourcetype = 'OBSERVATION'";
    String keys = stage.keys() + ", name";
    List<IndexContract> mutated =
        List.of(
            new IndexContract(patient.name(), patient.uniquenessKey(), patient.keys(), predicate),
            new IndexContract(stage.name(), stage.uniquenessKey(), keys, stage.predicate()),
            PARTIAL_INDEXES.get(2));
    Map<String, String> mismatches = partialIndexMismatches(scratchIndexes(mutated));
    assertAll(
        () -> assertEquals(List.of(), unmodelled, "properties missing from the model"),
        () -> assertEquals(modelOwned, instanceFields(FhirResourceMapping.class), "model fields"),
        () -> assertEquals(hbm, parseHbmElements()),
        () -> assertEquals(columns, queryPairs(COLUMNS_SQL, TABLE)),
        () -> assertEquals(constraints, queryPairs(CONSTRAINTS_SQL)),
        () -> assertEquals(constraintIndexes, otherIndexes),
        () -> assertEquals(Map.of(), partialIndexMismatches(indexes), "partial unique indexes"),
        () ->
            assertEquals(
                Set.of(patient.name(), stage.name()), mismatches.keySet(), mismatches::toString));
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
    String mapping =
        """
        {"id": "%s", "name": "FHIR store test %s", "resourceType": "PATIENT", "trackedEntityType":
          {"id": "%s"}, "fieldMappings": [{"target": "PATIENT_IDENTIFIER", "sourceType": "ATTRIBUTE",
          "source": "integerAttr"}]}
        """
            .formatted(uid, uid, PERSON_TYPE);
    String bundle = "{\"fhirResourceMappings\": [%s]}".formatted(mapping);
    JsonMixed imported = POST("/metadata?skipValidation=true", bundle).content(HttpStatus.OK);
    manager.clear();
    JsonImportSummary report = imported.get("response", JsonImportSummary.class);
    assertEquals("OK", report.getStatus(), imported::toJson);
    assertEquals(List.of(uid), noAclUids(PATIENT));
    HttpResponse read = GET("/fhir/Patient/{id}", FRANK);
    assertOutcome(
        read, HttpStatus.NOT_IMPLEMENTED, IssueType.NOTSUPPORTED, d -> d.contains("Patient"));
  }

  private void assertRejected(String index, String resourceType, String fieldMappings) {
    Long program = "PATIENT".equals(resourceType) ? null : programId;
    Long stage = program == null ? null : programStageId;
    Executable insert = () -> insertCommitted(resourceType, program, stage, fieldMappings);
    var ex = assertThrows(DataIntegrityViolationException.class, insert);
    assertTrue(String.valueOf(ex.getMessage()).contains(index), ex.getMessage());
  }

  private Map<String, String> scratchIndexes(List<IndexContract> indexes) {
    TransactionTemplate rollback = newTransaction();
    return rollback.execute(
        status -> {
          status.setRollbackOnly();
          jdbcTemplate.execute("create temporary table " + SCRATCH_TABLE + " (like " + TABLE + ")");
          indexes.forEach(index -> jdbcTemplate.execute(index.create(SCRATCH_TABLE)));
          return queryPairs(INDEXES_SQL, SCRATCH_TABLE);
        });
  }

  private Map<String, String> partialIndexMismatches(Map<String, String> actual) {
    Map<String, String> expected = scratchIndexes(PARTIAL_INDEXES);
    Map<String, String> mismatches = new TreeMap<>();
    for (IndexContract index : PARTIAL_INDEXES) {
      String name = index.name();
      String contract = expected.get(name);
      if (!contract.equals(actual.get(name)))
        mismatches.put(name, index.uniquenessKey() + ": " + actual.get(name) + " <> " + contract);
    }
    return mismatches;
  }

  private Map<String, String> queryPairs(String sql, Object... args) {
    Map<String, String> rows = new TreeMap<>();
    jdbcTemplate.query(
        sql, (RowCallbackHandler) rs -> rows.put(rs.getString(1), rs.getString(2)), args);
    return rows;
  }

  private static Map<String, String> parseHbmElements() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
    Map<String, String> elements = new TreeMap<>();
    for (String path : List.of(MAPPING_HBM, IDENTIFIABLE_PROPERTIES_HBM)) {
      String hbm = new ClassPathResource(path).getContentAsString(UTF_8);
      String xml = path.equals(MAPPING_HBM) ? hbm : "<root>" + hbm + "</root>";
      NodeList nodes =
          builder.parse(new InputSource(new StringReader(xml))).getElementsByTagName("*");
      for (int i = 0; i < nodes.getLength(); i++) {
        Element element = (Element) nodes.item(i);
        if (Set.of("id", "property", "many-to-one").contains(element.getTagName())) {
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

  private String insertCommitted(String resourceType, Long program, Long stage, String fields) {
    String uid = nextUid();
    newTransaction()
        .executeWithoutResult(status -> insertRow(uid, resourceType, program, stage, fields));
    return uid;
  }

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
    newTransaction().executeWithoutResult(status -> jdbcTemplate.update("delete from " + TABLE));
    manager.clear();
  }

  @AfterAll
  void deleteFixtureMappings() {
    deleteAllMappings();
  }

  private long idOf(String table, String uid) {
    String sql = "select " + table + "id from " + table + " where uid = ?";
    return Objects.requireNonNull(jdbcTemplate.queryForObject(sql, Long.class, uid), uid);
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
    var template = new TransactionTemplate(transactionTemplate.getTransactionManager());
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

  private static Set<String> instanceFields(Class<?> type) {
    return Arrays.stream(type.getDeclaredFields())
        .filter(f -> !f.isSynthetic())
        .filter(f -> (f.getModifiers() & (Modifier.STATIC | Modifier.TRANSIENT)) == 0)
        .map(Field::getName)
        .collect(toCollection(TreeSet::new));
  }

  private static String[] cells(String line, int count) {
    String[] cells = Arrays.stream(line.split("\\|", -1)).map(String::strip).toArray(String[]::new);
    assertEquals(count, cells.length, line);
    return cells;
  }

  private record IndexContract(String name, String uniquenessKey, String keys, String predicate) {
    static IndexContract parse(String line) {
      String[] c = cells(line, 4);
      return new IndexContract(c[0], c[1], c[2], c[3]);
    }

    String create(String table) {
      return "create unique index %s on %s (%s) where %s".formatted(name, table, keys, predicate);
    }
  }

  interface FhirResponses {
    String ORIGIN = "https://fhir.example.org";
    Object[] FORWARDED = {
      Header("X-Forwarded-Proto", "https"),
      Header("X-Forwarded-Host", "fhir.example.org"),
      Header("X-Forwarded-Port", "443")
    };

    /** Parses FHIR JSON strictly: unknown elements and invalid values fail the parse. */
    static <T extends IBaseResource> T parse(String body, Class<T> type) {
      return FhirContext.forR4Cached()
          .newJsonParser()
          .setParserErrorHandler(new StrictErrorHandler())
          .parseResource(type, body);
    }

    static <T extends IBaseResource> T parseOk(HttpResponse response, Class<T> type) {
      return parse(fhirBody(response, HttpStatus.OK), type);
    }

    static String fhirBody(HttpResponse response, HttpStatus status) {
      assertEquals(status, response.status(), () -> response.contentUnchecked().toString());
      String body = response.content("application/fhir+json");
      assertEquals(FHIR_JSON_MEDIA_TYPE, MediaType.parseMediaType(response.getContentType()));
      return body;
    }

    /** Returns the logical ids of the entries of a Bundle in order, asserting that they differ. */
    static List<String> entryIds(Bundle bundle) {
      List<String> ids = bundle.getEntry().stream().map(e -> e.getResource().getIdPart()).toList();
      assertEquals(ids.size(), Set.copyOf(ids).size(), ids::toString);
      return ids;
    }

    /** Parses a searchset: one self link to {@code url}, match entries of the type at fullUrls. */
    static Bundle searchset(String url, String body) {
      Bundle bundle = parse(body, Bundle.class);
      assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType(), body);
      List<String> self =
          bundle.getLink().stream()
              .filter(link -> Bundle.LINK_SELF.equals(link.getRelation()))
              .map(link -> link.getUrl().replaceFirst("^.*?/api/", "/api/"))
              .map(link -> URLDecoder.decode(link, UTF_8))
              .toList();
      assertEquals(List.of(URLDecoder.decode(url, UTF_8)), self, body);
      String type = url.split("\\?")[0].replaceAll(".*/", "");
      for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
        Resource resource = entry.getResource();
        String fullUrl = "/api/fhir/" + resource.fhirType() + "/" + resource.getIdPart();
        assertTrue(type.startsWith("$") || type.equals(resource.fhirType()), body);
        assertTrue(entry.getFullUrl().endsWith(fullUrl), entry::getFullUrl);
        assertEquals(Bundle.SearchEntryMode.MATCH, entry.getSearch().getMode(), body);
      }
      return bundle;
    }

    /** Asserts an {@code OperationOutcome} with one {@code error} issue and returns the body. */
    static String assertOutcome(
        HttpResponse response, HttpStatus status, IssueType code, Predicate<String> diagnostics) {
      String body = fhirBody(response, status);
      OperationOutcome outcome = parse(body, OperationOutcome.class);
      assertEquals(1, outcome.getIssue().size(), body);
      OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
      assertEquals(IssueSeverity.ERROR, issue.getSeverity(), body);
      assertEquals(code, issue.getCode(), body);
      assertTrue(diagnostics.test(issue.getDiagnostics()), body);
      return body;
    }

    static String assertNotFound(HttpResponse response) {
      String diagnostics = FhirApiException.notFound().getDiagnostics();
      return assertOutcome(response, HttpStatus.NOT_FOUND, IssueType.NOTFOUND, diagnostics::equals);
    }
  }

  /**
   * Base of the FHIR controller tests on PostgreSQL with {@code fhir.api.enabled=true}. Once per
   * class it deletes every mapping, imports the Tracker fixtures and {@value #MAPPINGS_FILE}, and
   * deletes every mapping afterwards. Each test starts with public access {@value #DATA_READ} on
   * the person type and both programs and {@value #METADATA_ONLY} on the given-name attribute.
   */
  @TestInstance(TestInstance.Lifecycle.PER_CLASS)
  @ContextConfiguration(classes = FhirPostgresControllerTestBase.FhirApiEnabledConfig.class)
  abstract static class FhirPostgresControllerTestBase
      extends PostgresControllerIntegrationTestBase {
    static final String NO_ACCESS = "--------";
    static final String DATA_READ = "rwrw----";
    static final String METADATA_ONLY = "rw------";
    static final String ROOT_ORG_UNIT = "h4w96yEMlzO";
    static final String PERSON_TYPE = "ja8NY4PW7Xm";
    static final String PROGRAM = "BFcipDERJnf";
    static final String SECOND_PROGRAM = "shPjYNifvMK";
    static final String GIVEN_ATTRIBUTE = "dIVt4l5vIOa";
    static final String FRANK = "dUE514NMOlo";
    static final String SUMMER = "QS6w44flWAf";
    private static final String MAPPINGS_FILE = "fhir/fhir_resource_mappings.json";

    private final AtomicInteger userCounter = new AtomicInteger();
    @Autowired private TestSetup testSetup;
    User importUser;

    public static class FhirApiEnabledConfig {
      @Bean
      public DhisConfigurationProvider dhisConfigurationProvider() {
        Properties override = new Properties();
        override.put(ConfigurationKey.FHIR_API_ENABLED.getKey(), "true");
        PostgresDhisConfigurationProvider provider = new PostgresDhisConfigurationProvider(null);
        provider.addProperties(override);
        return provider;
      }
    }

    @BeforeAll
    void importFixtures() throws IOException {
      doInTransaction(
          () -> manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete));
      testSetup.importMetadata();
      importUser = userService.getUser("tTgjgobT1oS");
      injectSecurityContextUser(importUser);
      testSetup.importTrackerData();
      manager.flush();
      manager.clear();
      testSetup.importMetadata(MAPPINGS_FILE);
      manager.flush();
      manager.clear();
    }

    @AfterAll
    void deleteFixtureMappings() {
      injectSecurityContextUser(importUser);
      doInTransaction(
          () -> manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete));
    }

    @BeforeEach
    void switchToImportUserWithBaselineSharing() {
      switchContextToUser(importUser);
      setPublicSharing("trackedEntityType", PERSON_TYPE, DATA_READ);
      setPublicSharing("program", PROGRAM, DATA_READ);
      setPublicSharing("program", SECOND_PROGRAM, DATA_READ);
      setPublicSharing("trackedEntityAttribute", GIVEN_ATTRIBUTE, METADATA_ONLY);
    }

    /** Reads {@code /api/fhir/{type}/{idAndQuery}}: a {@code 200} with the id and lastUpdated. */
    <T extends IBaseResource> T read(Class<T> type, String idAndQuery) {
      T resource = parseOk(GET("/api/fhir/" + type.getSimpleName() + "/" + idAndQuery), type);
      assertEquals(idAndQuery.split("\\?")[0], resource.getIdElement().getIdPart(), idAndQuery);
      assertNotNull(resource.getMeta().getLastUpdated(), idAndQuery);
      return resource;
    }

    /** Asserts {@code 400 invalid} naming {@code parameter} and no other query parameter. */
    void assertInvalid(String url, String parameter) {
      String query = url.contains("?") ? url.substring(url.indexOf('?') + 1) : "";
      List<String> others =
          Stream.of(query.split("&"))
              .map(pair -> pair.split("=", 2)[0])
              .filter(name -> !name.isEmpty() && !name.equals(parameter))
              .toList();
      String prefix = "Invalid parameter '" + parameter + "':";
      Predicate<String> namesOnlyParameter =
          d -> d.startsWith(prefix) && others.stream().noneMatch(n -> d.contains("'" + n + "'"));
      assertOutcome(GET(url), HttpStatus.BAD_REQUEST, IssueType.INVALID, namesOnlyParameter);
    }

    /** GETs {@code url} and its next page behind a proxy: every link starts with the origin. */
    List<Bundle> forwardedPages(String url, String path) {
      Bundle first = searchset(url, fhirBody(GET(url, FORWARDED), HttpStatus.OK));
      String self = first.getLink(Bundle.LINK_SELF).getUrl();
      String next = first.getLink(Bundle.LINK_NEXT).getUrl();
      for (String link : List.of(first.getEntryFirstRep().getFullUrl(), self, next))
        assertTrue(link.startsWith(ORIGIN + path), link);
      String nextPath = next.substring(ORIGIN.length());
      Bundle second = searchset(nextPath, fhirBody(GET(nextPath, FORWARDED), HttpStatus.OK));
      assertTrue(second.getEntryFirstRep().getFullUrl().startsWith(ORIGIN + path), nextPath);
      return List.of(first, second);
    }

    String assertForbidden(String url) {
      String diagnostics = FhirApiException.forbidden().getDiagnostics();
      return assertOutcome(
          GET(url), HttpStatus.FORBIDDEN, IssueType.FORBIDDEN, diagnostics::equals);
    }

    /** Asserts one {@code 403} body for all {@code urls} that contains none of {@code hidden}. */
    String assertSameForbidden(List<String> urls, String... hidden) {
      String body = assertForbidden(urls.get(0));
      urls.stream().skip(1).forEach(url -> assertEquals(body, assertForbidden(url), url));
      Stream.of(hidden).forEach(value -> assertFalse(body.contains(value), body));
      return body;
    }

    /** Runs {@code test} as a new user under the restrictions, then restores public access. */
    void asRestrictedUser(List<Restriction> restrictions, Runnable test) {
      Map<Restriction, String> previous = new LinkedHashMap<>();
      try {
        restrictions.forEach(r -> previous.put(r, setPublicSharing(r.type(), r.uid(), r.access())));
        asUser(userWithScope(ROOT_ORG_UNIT, ROOT_ORG_UNIT), test);
      } finally {
        previous.forEach((r, access) -> setPublicSharing(r.type(), r.uid(), access));
      }
    }

    /** Runs {@code check} as the import user, then as a new user with the baseline sharing. */
    void asImportAndBaselineUser(Runnable check) {
      check.run();
      asRestrictedUser(List.of(), check);
    }

    void asUser(User user, Runnable test) {
      switchContextToUser(user);
      try {
        test.run();
      } finally {
        switchContextToUser(importUser);
      }
    }

    /** Creates a user without authorities, user groups or user sharing. */
    User userWithScope(String captureOrgUnit, String searchOrgUnit) {
      User user = createUserWithAuth("fhiruser" + userCounter.incrementAndGet());
      user.addOrganisationUnit(manager.get(OrganisationUnit.class, captureOrgUnit));
      user.setTeiSearchOrganisationUnits(
          Set.of(manager.get(OrganisationUnit.class, searchOrgUnit)));
      userService.updateUser(user);
      manager.clear();
      return user;
    }

    /** Sets public access through {@code /api/sharing} and returns the previous public access. */
    String setPublicSharing(String type, String uid, String access) {
      String url = "/sharing?type=" + type + "&id=" + uid;
      JsonObject object = GET(url).content(HttpStatus.OK).getObject("object");
      String users = arrayJson(object.getArray("userAccesses"));
      String groups = arrayJson(object.getArray("userGroupAccesses"));
      String body = "{'object':{'publicAccess':'%s','userAccesses':%s,'userGroupAccesses':%s}}";
      assertStatus(HttpStatus.OK, POST(url, body.formatted(access, users, groups)));
      manager.flush();
      manager.clear();
      return object.getString("publicAccess").string();
    }

    private static String arrayJson(JsonArray array) {
      return array.exists() ? array.toJson() : "[]";
    }

    record Restriction(String type, String uid, String access) {}
  }
}
