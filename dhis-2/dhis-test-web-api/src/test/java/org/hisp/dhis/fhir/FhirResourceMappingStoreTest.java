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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.fhir.mapping.FhirResourceMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingStore;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.http.HttpClientAdapter.HttpResponse;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.test.config.PostgresDhisConfigurationProvider;
import org.hisp.dhis.test.webapi.PostgresControllerIntegrationTestBase;
import org.hisp.dhis.test.webapi.json.domain.JsonImportSummary;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Tests the persistence of {@link FhirResourceMapping} on PostgreSQL: the store's sharing-free
 * lookup, the table created by migration {@code V2_44_25} against its schema contract and the
 * Hibernate mapping, the partial unique indexes, concurrent creation, and the resolution guard for
 * mappings stored by a metadata import that skipped validation.
 *
 * <p>Rows are written with plain JDBC where a test bypasses the validator. Every write is
 * committed; all mappings are deleted before the class, after each test and after the class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ContextConfiguration(classes = FhirResourceMappingStoreTest.FhirApiEnabledConfig.class)
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

  private static final String FHIR_JSON_CONTENT_TYPE = "application/fhir+json;charset=UTF-8";

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
   * The partial unique indexes of table {@code fhirresourcemapping}, each with the validator
   * uniqueness key it enforces and fragments of its normalised definition.
   */
  private static final List<IndexContract> PARTIAL_INDEXES =
      List.of(
          new IndexContract(
              "ux_fhirresourcemapping_patient",
              "PATIENT",
              List.of("usingbtree(resourcetype)where", "(resourcetype)='patient'")),
          new IndexContract(
              "ux_fhirresourcemapping_stage",
              "ENCOUNTER:{stage}, OBSERVATION:{stage}",
              List.of(
                  "usingbtree(resourcetype,programstageid)where",
                  "(resourcetype)=any",
                  "'encounter','observation'")),
          new IndexContract(
              "ux_fhirresourcemapping_immunization",
              "IMMUNIZATION:{stage}:{administered DE}",
              List.of(
                  "usingbtree(programstageid,((jsonb_path_query_first(fieldmappings,",
                  "@.target==immunization_administered",
                  ".source')#>>'{}')))where",
                  "(resourcetype)='immunization'")));

  @Autowired private FhirResourceMappingStore store;

  @Autowired private TestSetup testSetup;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private TransactionTemplate transactionTemplate;

  private final AtomicInteger uidSequence = new AtomicInteger();

  private long trackedEntityTypeId;

  private long programId;

  private long programStageId;

  /** Provides the PostgreSQL test configuration with {@code fhir.api.enabled=true}. */
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
  void importTrackerMetadata() throws IOException {
    deleteAllMappings();
    testSetup.importMetadata();
    manager.flush();
    manager.clear();
    trackedEntityTypeId = idOf("trackedentitytype", "trackedentitytypeid", TRACKED_ENTITY_TYPE_UID);
    programId = idOf("program", "programid", PROGRAM_UID);
    programStageId = idOf("programstage", "programstageid", PROGRAM_STAGE_UID);
  }

  @AfterEach
  void deleteMappingsAfterTest() {
    deleteAllMappings();
  }

  @AfterAll
  void deleteMappingsAfterClass() {
    deleteAllMappings();
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
    Map<String, HbmElement> hbm = parseHbmElements();
    Set<String> modelProperties =
        Arrays.stream(Introspector.getBeanInfo(FhirResourceMapping.class).getPropertyDescriptors())
            .filter(descriptor -> descriptor.getReadMethod() != null)
            .map(PropertyDescriptor::getName)
            .collect(Collectors.toSet());
    Map<String, MigratedColumn> columns = migratedColumns();
    Map<String, String> constraints = migratedConstraints();
    Map<String, String> indexes = migratedIndexes();

    List<Executable> checks = new ArrayList<>();
    checks.add(() -> assertEquals(contractValues(ContractRow::property), hbm.keySet()));
    checks.add(() -> assertEquals(contractValues(ContractRow::column), columns.keySet()));
    for (ContractRow row : CONTRACT) {
      checks.add(
          () -> assertTrue(modelProperties.contains(row.property()), "model: " + row.property()));
      checks.add(() -> assertEquals(row.expectedHbm(), hbm.get(row.property()), row.property()));
      checks.add(() -> assertEquals(row.expectedColumn(), columns.get(row.column()), row.column()));
    }

    Map<String, String> expectedConstraints = new LinkedHashMap<>();
    CONTRACT.stream()
        .filter(row -> row.constraintName() != null)
        .forEach(row -> expectedConstraints.put(row.constraintName(), row.constraintDefinition()));
    checks.add(() -> assertEquals(expectedConstraints, constraints));

    Set<String> expectedIndexes = new TreeSet<>();
    CONTRACT.stream()
        .filter(row -> "p".equals(row.constraintType()) || "u".equals(row.constraintType()))
        .forEach(
            row -> {
              expectedIndexes.add(row.constraintName());
              checks.add(() -> assertConstraintIndex(row, indexes.get(row.constraintName())));
            });
    for (IndexContract index : PARTIAL_INDEXES) {
      expectedIndexes.add(index.name());
      checks.add(() -> assertPartialIndex(index, indexes.get(index.name())));
    }
    checks.add(() -> assertEquals(expectedIndexes, new TreeSet<>(indexes.keySet())));

    assertAll(checks);
  }

  @Test
  void uniqueIndexesRejectDuplicates() {
    String administeredFirst = administered("DATAEL00001");
    insertCommitted("PATIENT", null, null, "[]");
    insertCommitted("ENCOUNTER", programId, programStageId, "[]");
    insertCommitted("OBSERVATION", programId, programStageId, "[]");
    insertCommitted("IMMUNIZATION", programId, programStageId, administeredFirst);

    assertAll(
        () -> assertRejected("ux_fhirresourcemapping_patient", "PATIENT", null, null, "[]"),
        () ->
            assertRejected(
                "ux_fhirresourcemapping_stage", "ENCOUNTER", programId, programStageId, "[]"),
        () ->
            assertRejected(
                "ux_fhirresourcemapping_stage", "OBSERVATION", programId, programStageId, "[]"),
        () ->
            assertRejected(
                "ux_fhirresourcemapping_immunization",
                "IMMUNIZATION",
                programId,
                programStageId,
                administeredFirst));

    insertCommitted("IMMUNIZATION", programId, programStageId, administered("DATAEL00002"));

    assertAll(
        () -> assertEquals(1, countOf("PATIENT")),
        () -> assertEquals(1, countOf("ENCOUNTER")),
        () -> assertEquals(1, countOf("OBSERVATION")),
        () -> assertEquals(2, countOf("IMMUNIZATION")));
  }

  @Test
  void concurrentCreationCommitsExactlyOne() throws InterruptedException {
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String uid = nextUid();
        futures.add(
            executor.submit(
                () ->
                    newTransaction()
                        .executeWithoutResult(
                            status -> {
                              awaitBarrier(barrier);
                              insertRow(uid, nameOf(uid), "PATIENT", null, null, "[]");
                            })));
      }

      int committed = 0;
      List<Throwable> failures = new ArrayList<>();
      for (Future<?> future : futures) {
        try {
          future.get(20, SECONDS);
          committed++;
        } catch (ExecutionException ex) {
          failures.add(ex.getCause());
        } catch (TimeoutException ex) {
          failures.add(ex);
        }
      }

      int finalCommitted = committed;
      assertAll(
          () -> assertEquals(1, finalCommitted, "committed insertions"),
          () -> assertEquals(1, failures.size(), "failed insertions: " + failures),
          () -> assertInstanceOf(DataIntegrityViolationException.class, failures.get(0)),
          () -> assertEquals(1, countOf("PATIENT")));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, SECONDS), "executor terminated");
    }
  }

  @Test
  void metadataImportBypassIsContainedAtResolution() {
    deleteAllMappings();
    String uid = nextUid();

    JsonImportSummary report =
        POST(
                "/metadata?skipValidation=true",
                """
                {"fhirResourceMappings": [{
                  "id": "%s",
                  "name": "%s",
                  "resourceType": "PATIENT",
                  "trackedEntityType": {"id": "%s"},
                  "fieldMappings": [
                    {"target": "PATIENT_IDENTIFIER", "sourceType": "ATTRIBUTE", "source": "integerAttr"}
                  ]
                }]}
                """
                    .formatted(uid, nameOf(uid), TRACKED_ENTITY_TYPE_UID))
            .content(HttpStatus.OK)
            .get("response")
            .as(JsonImportSummary.class);
    assertEquals("OK", report.getStatus());
    manager.clear();
    assertEquals(List.of(uid), noAclUids(FhirResourceType.PATIENT));

    HttpResponse response = GET("/fhir/Patient/{id}", PATIENT_UID);

    assertEquals(HttpStatus.NOT_IMPLEMENTED, response.status());
    String contentType = response.header("Content-Type");
    assertNotNull(contentType, "Content-Type");
    assertTrue(contentType.startsWith(FHIR_JSON_CONTENT_TYPE), contentType);
    OperationOutcome outcome =
        FhirContext.forR4Cached()
            .newJsonParser()
            .setParserErrorHandler(new StrictErrorHandler())
            .parseResource(OperationOutcome.class, response.content(FHIR_JSON_CONTENT_TYPE));
    assertEquals(1, outcome.getIssue().size());
    OperationOutcomeIssueComponent issue = outcome.getIssue().get(0);
    assertAll(
        () -> assertEquals(IssueSeverity.ERROR, issue.getSeverity()),
        () -> assertEquals(IssueType.NOTSUPPORTED, issue.getCode()),
        () -> assertEquals("not-supported", issue.getCode().toCode()));
  }

  private void assertRejected(
      String index, String resourceType, Long program, Long stage, String fieldMappings) {
    String uid = nextUid();
    DataIntegrityViolationException ex =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                newTransaction()
                    .executeWithoutResult(
                        status ->
                            insertRow(
                                uid, nameOf(uid), resourceType, program, stage, fieldMappings)));
    assertTrue(String.valueOf(ex.getMessage()).contains(index), ex.getMessage());
  }

  private static void assertConstraintIndex(ContractRow row, String definition) {
    assertNotNull(definition, row.constraintName());
    String expected = "createuniqueindex" + row.constraintName() + "on";
    assertAll(
        () -> assertTrue(definition.startsWith(expected), definition),
        () -> assertTrue(definition.endsWith("usingbtree(" + row.column() + ")"), definition));
  }

  private static void assertPartialIndex(IndexContract index, String definition) {
    assertNotNull(definition, index.name() + " enforcing " + index.uniquenessKey());
    List<Executable> checks = new ArrayList<>();
    checks.add(
        () -> assertTrue(definition.startsWith("createuniqueindex" + index.name()), definition));
    for (String fragment : index.fragments()) {
      checks.add(
          () ->
              assertTrue(
                  definition.contains(fragment),
                  index.uniquenessKey() + ": " + fragment + " not in " + definition));
    }
    assertAll(checks);
  }

  private Map<String, MigratedColumn> migratedColumns() {
    Map<String, MigratedColumn> columns = new LinkedHashMap<>();
    jdbcTemplate
        .queryForList(
            "select column_name, udt_name, character_maximum_length, is_nullable, column_default"
                + " from information_schema.columns"
                + " where table_schema = current_schema() and table_name = ?",
            TABLE)
        .forEach(
            row ->
                columns.put(
                    (String) row.get("column_name"),
                    new MigratedColumn(
                        (String) row.get("udt_name"),
                        row.get("character_maximum_length") == null
                            ? null
                            : ((Number) row.get("character_maximum_length")).intValue(),
                        "YES".equals(row.get("is_nullable")),
                        (String) row.get("column_default"))));
    return columns;
  }

  private Map<String, String> migratedConstraints() {
    Map<String, String> constraints = new LinkedHashMap<>();
    jdbcTemplate
        .queryForList(
            "select conname, pg_get_constraintdef(oid) as definition from pg_constraint"
                + " where conrelid = 'fhirresourcemapping'::regclass and contype <> 'n'")
        .forEach(
            row ->
                constraints.put(
                    (String) row.get("conname"),
                    ((String) row.get("definition")).toLowerCase(Locale.ROOT)));
    return constraints;
  }

  private Map<String, String> migratedIndexes() {
    Map<String, String> indexes = new LinkedHashMap<>();
    jdbcTemplate
        .queryForList(
            "select indexname, indexdef from pg_indexes"
                + " where schemaname = current_schema() and tablename = ?",
            TABLE)
        .forEach(
            row ->
                indexes.put(
                    (String) row.get("indexname"), normalise((String) row.get("indexdef"))));
    return indexes;
  }

  private static String normalise(String definition) {
    return definition
        .toLowerCase(Locale.ROOT)
        .replace("\"", "")
        .replaceAll("\\s+", "")
        .replaceAll("::[a-z]+(\\[])?", "");
  }

  private static Map<String, HbmElement> parseHbmElements() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setValidating(false);
    factory.setNamespaceAware(false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));

    Map<String, HbmElement> elements = new LinkedHashMap<>();
    collectMappedElements(parse(builder, readClasspath(MAPPING_HBM)), elements);
    collectMappedElements(
        parse(builder, "<root>" + readClasspath(IDENTIFIABLE_PROPERTIES_HBM) + "</root>"),
        elements);
    return elements;
  }

  private static Document parse(DocumentBuilder builder, String xml) throws Exception {
    return builder.parse(new InputSource(new StringReader(xml)));
  }

  private static String readClasspath(String path) throws IOException {
    try (InputStream in = new ClassPathResource(path).getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void collectMappedElements(Document document, Map<String, HbmElement> elements) {
    NodeList nodes = document.getElementsByTagName("*");
    for (int i = 0; i < nodes.getLength(); i++) {
      Element element = (Element) nodes.item(i);
      if (!MAPPED_ELEMENTS.contains(element.getTagName())) {
        continue;
      }
      HbmElement mapped = HbmElement.of(element);
      assertTrue(elements.put(mapped.property(), mapped) == null, "HBM repeats " + mapped);
    }
  }

  private String insertCommitted(
      String resourceType, Long program, Long stage, String fieldMappings) {
    String uid = nextUid();
    newTransaction()
        .executeWithoutResult(
            status -> insertRow(uid, nameOf(uid), resourceType, program, stage, fieldMappings));
    return uid;
  }

  /**
   * Inserts a mapping row with plain JDBC, bypassing the validator, with the column values
   * Hibernate writes for a new mapping: an empty translations array and the default sharing and
   * attribute values.
   */
  private void insertRow(
      String uid,
      String name,
      String resourceType,
      Long program,
      Long stage,
      String fieldMappingsJson) {
    jdbcTemplate.update(
        "insert into fhirresourcemapping (fhirresourcemappingid, uid, name, created, lastupdated,"
            + " translations, resourcetype, trackedentitytypeid, programid, programstageid,"
            + " fieldmappings) values (nextval('hibernate_sequence'), ?, ?, now(), now(),"
            + " '[]'::jsonb, ?, ?, ?, ?, ?::jsonb)",
        new Object[] {
          uid, name, resourceType, trackedEntityTypeId, program, stage, fieldMappingsJson
        },
        new int[] {
          Types.VARCHAR,
          Types.VARCHAR,
          Types.VARCHAR,
          Types.BIGINT,
          Types.BIGINT,
          Types.BIGINT,
          Types.VARCHAR
        });
  }

  private void setPublicSharing(String uid, String publicAccess) {
    String sharing =
        "{\"public\":\"%s\",\"owner\":\"%s\",\"users\":{},\"userGroups\":{}}"
            .formatted(publicAccess, getAdminUid());
    newTransaction()
        .executeWithoutResult(
            status ->
                jdbcTemplate.update(
                    "update fhirresourcemapping set sharing = ?::jsonb where uid = ?",
                    sharing,
                    uid));
  }

  /**
   * Deletes every mapping row in a committed transaction, clearing the persistence context before
   * and after.
   */
  private void deleteAllMappings() {
    manager.clear();
    newTransaction()
        .executeWithoutResult(status -> jdbcTemplate.update("delete from fhirresourcemapping"));
    manager.clear();
  }

  private long idOf(String table, String idColumn, String uid) {
    Long id =
        jdbcTemplate.queryForObject(
            "select " + idColumn + " from " + table + " where uid = ?", Long.class, uid);
    assertNotNull(id, table + " " + uid);
    return id;
  }

  private int countOf(String resourceType) {
    Integer count =
        jdbcTemplate.queryForObject(
            "select count(*) from fhirresourcemapping where resourcetype = ?",
            Integer.class,
            resourceType);
    return count == null ? 0 : count;
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

  private static String nameOf(String uid) {
    return "FHIR store test " + uid;
  }

  private static void awaitBarrier(CyclicBarrier barrier) {
    try {
      barrier.await(10, SECONDS);
    } catch (Exception ex) {
      throw new IllegalStateException("Concurrent insertions did not start together", ex);
    }
  }

  private static Set<String> contractValues(Function<ContractRow, String> value) {
    return CONTRACT.stream().map(value).collect(Collectors.toSet());
  }

  /** One row of the schema contract. */
  private record ContractRow(
      String property,
      String element,
      String column,
      String udtName,
      Integer length,
      boolean nullable,
      String columnDefault,
      String constraintType,
      String constraintName,
      String referencedTable) {

    static ContractRow parse(String line) {
      String[] cells =
          Arrays.stream(line.split("\\|", -1))
              .map(String::strip)
              .map(cell -> cell.isEmpty() ? null : cell)
              .toArray(String[]::new);
      assertEquals(10, cells.length, line);
      return new ContractRow(
          cells[0],
          cells[1],
          cells[2],
          cells[3],
          cells[4] == null ? null : Integer.valueOf(cells[4]),
          "YES".equals(cells[5]),
          cells[6],
          cells[7],
          cells[8],
          cells[9]);
    }

    String constraintDefinition() {
      if (constraintType == null) {
        return null;
      }
      return switch (constraintType) {
        case "p" -> "primary key (" + column + ")";
        case "u" -> "unique (" + column + ")";
        case "f" ->
            "foreign key (%s) references %s(%sid)"
                .formatted(column, referencedTable, referencedTable);
        default -> throw new IllegalArgumentException(constraintType);
      };
    }

    HbmElement expectedHbm() {
      return new HbmElement(
          element,
          property,
          column,
          length,
          nullable,
          "u".equals(constraintType),
          "f".equals(constraintType) ? constraintName : null);
    }

    MigratedColumn expectedColumn() {
      return new MigratedColumn(udtName, length, nullable, columnDefault);
    }
  }

  /** A mapped element of the Hibernate mapping with the column attributes it declares. */
  private record HbmElement(
      String element,
      String property,
      String column,
      Integer length,
      boolean nullable,
      boolean unique,
      String foreignKey) {

    static HbmElement of(Element element) {
      Element column = firstChildElement(element, "column");
      String property = element.getAttribute("name");
      String columnName = column != null ? attribute(column, "name") : attribute(element, "column");
      String length = attribute(column != null ? column : element, "length");
      String notNull = attribute(column != null ? column : element, "not-null");
      String unique = attribute(column != null ? column : element, "unique");
      return new HbmElement(
          element.getTagName(),
          property,
          columnName != null
              ? columnName.toLowerCase(Locale.ROOT)
              : property.toLowerCase(Locale.ROOT),
          length == null ? null : Integer.valueOf(length),
          !"id".equals(element.getTagName()) && !"true".equals(notNull),
          "true".equals(unique),
          attribute(element, "foreign-key"));
    }

    private static Element firstChildElement(Element parent, String tagName) {
      for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (child instanceof Element childElement && tagName.equals(childElement.getTagName())) {
          return childElement;
        }
      }
      return null;
    }

    private static String attribute(Element element, String name) {
      String value = element.getAttribute(name);
      return value.isEmpty() ? null : value;
    }
  }

  /** A column of the migrated table as reported by {@code information_schema.columns}. */
  private record MigratedColumn(
      String udtName, Integer length, boolean nullable, String columnDefault) {}

  /** A partial unique index with the validator uniqueness key it enforces. */
  private record IndexContract(String name, String uniquenessKey, List<String> fragments) {}
}
