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

import static org.hisp.dhis.http.HttpAssertions.assertStatus;
import static org.hisp.dhis.http.HttpClientAdapter.Accept;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.hisp.dhis.common.CodeGenerator;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.fhir.mapping.FhirResourceMapping;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.JsonArray;
import org.hisp.dhis.jsontree.JsonObject;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.test.config.PostgresDhisConfigurationProvider;
import org.hisp.dhis.test.webapi.PostgresControllerIntegrationTestBase;
import org.hisp.dhis.user.User;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleLinkComponent;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;

/**
 * Tests the FHIR R4 {@code Patient} read, search-type and {@code $everything} operations on
 * PostgreSQL over the Tracker base fixtures and {@code fhir/fhir_resource_mappings.json}, with
 * {@code fhir.api.enabled=true}: mapped resources, parameter validation, and the {@code 400},
 * {@code 403} and {@code 404} outcomes, including those a user with restricted sharing or scope
 * receives.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ContextConfiguration(classes = FhirPatientControllerTest.FhirApiEnabledConfig.class)
class FhirPatientControllerTest extends PostgresControllerIntegrationTestBase {

  private static final String IMPORT_USER_UID = "tTgjgobT1oS";
  private static final String MAPPINGS_FILE = "fhir/fhir_resource_mappings.json";
  private static final String PATIENT_MAPPING = "FhirMapPat1";
  private static final String PERSON_TYPE = "ja8NY4PW7Xm";
  private static final String PROGRAM = "BFcipDERJnf";
  private static final String OTHER_PROGRAM = "shPjYNifvMK";
  private static final String FRANK = "dUE514NMOlo";
  private static final String SUMMER = "QS6w44flWAf";
  private static final String OTHER_TYPE_TRACKED_ENTITY = "XUitxQbWYNq";
  private static final String IDENTIFIER_SYSTEM = "urn:dhis2:fhir-test:integer-attr";
  private static final String FAMILY_ATTRIBUTE = "toUpdate000";
  private static final String GIVEN_ATTRIBUTE = "dIVt4l5vIOa";
  private static final String ROOT_ORG_UNIT = "h4w96yEMlzO";
  private static final String OTHER_ORG_UNIT = "DiszpKrYNg8";
  private static final String PATIENT_PATH = "/api/fhir/Patient";
  private static final String EVERYTHING = "/$everything";
  private static final String DATA_READ = "rwrw----";
  private static final String NO_ACCESS = "--------";
  private static final String METADATA_ONLY = "rw------";
  private static final String MAX_LIMIT_SETTING = "KeyTrackedEntityMaxLimit";
  private static final String FHIR_JSON = "application/fhir+json";
  private static final SharedObject TYPE_SHARING =
      new SharedObject("trackedEntityType", PERSON_TYPE);
  private static final SharedObject PROGRAM_SHARING = new SharedObject("program", PROGRAM);
  private static final SharedObject OTHER_PROGRAM_SHARING =
      new SharedObject("program", OTHER_PROGRAM);
  private static final SharedObject GIVEN_ATTRIBUTE_SHARING =
      new SharedObject("trackedEntityAttribute", GIVEN_ATTRIBUTE);
  private static final Pattern ACCESS_STRING = Pattern.compile("^[r-][w-][r-][w-]----$");

  private final AtomicInteger userCounter = new AtomicInteger();

  @Autowired private TestSetup testSetup;

  private User importUser;

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
  void importFixtures() throws IOException {
    deleteAllMappings();
    testSetup.importMetadata();

    importUser = userService.getUser(IMPORT_USER_UID);
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
    deleteAllMappings();
  }

  @BeforeEach
  void switchToImportUser() {
    switchContextToUser(importUser);
  }

  @Test
  void readPatientReturnsValidMappedPatient() {
    Patient patient = parseOk(GET(PATIENT_PATH + "/" + FRANK), Patient.class);

    assertFrankPatient(patient);
    assertNotNull(patient.getMeta().getLastUpdated());
  }

  @Test
  void readPatientWithProgramScopedMapping() {
    String typeScoped = fhirBody(GET(PATIENT_PATH + "/" + FRANK), HttpStatus.OK);

    try {
      patchPatientMapping("[{'op':'add','path':'/program','value':{'id':'" + PROGRAM + "'}}]");
      assertEquals(
          PROGRAM,
          GET("/fhirResourceMappings/{id}?fields=program[id]", PATIENT_MAPPING)
              .content()
              .getObject("program")
              .getString("id")
              .string());

      String programScoped = fhirBody(GET(PATIENT_PATH + "/" + FRANK), HttpStatus.OK);

      assertFrankPatient(parse(programScoped, Patient.class));
      assertEquals(typeScoped, programScoped);
    } finally {
      patchPatientMapping("[{'op':'remove','path':'/program'}]");
    }
    assertTrue(
        GET("/fhirResourceMappings/{id}?fields=program[id]", PATIENT_MAPPING)
            .content()
            .getObject("program")
            .isUndefined());
  }

  @Test
  void readPatientAcceptsFormatAndRejectsOtherParameters() {
    assertFrankPatient(parseOk(GET(PATIENT_PATH + "/" + FRANK + "?_format=json"), Patient.class));

    assertInvalid(PATIENT_PATH + "/" + FRANK + "?_format=xml", "_format");
    assertInvalid(PATIENT_PATH + "/" + FRANK + "?foo=1", "foo");
  }

  static Stream<Arguments> patientSearches() {
    Set<String> both = Set.of(SUMMER, FRANK);
    return Stream.of(
        Arguments.of("_id=" + SUMMER, Set.of(SUMMER), 1, false, false),
        Arguments.of("_id=" + SUMMER + "," + FRANK, both, 2, false, false),
        Arguments.of("identifier=" + IDENTIFIER_SYSTEM + "|70", Set.of(FRANK), 1, false, false),
        Arguments.of("identifier=88", Set.of(SUMMER), 1, false, false),
        Arguments.of("family=rain", Set.of(FRANK), 1, false, false),
        Arguments.of("given=frank", Set.of(FRANK), 1, false, false),
        Arguments.of("family=rain&_format=json", Set.of(FRANK), 1, false, false),
        Arguments.of("_id=" + SUMMER + "," + FRANK + "&_count=1&_page=1", both, 1, true, false),
        Arguments.of("_id=" + SUMMER + "," + FRANK + "&_count=1&_page=2", both, 1, false, true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("patientSearches")
  void searchPatientsByEachSupportedParameter(
      String query,
      Set<String> expectedIds,
      int expectedEntries,
      boolean expectNext,
      boolean expectPrevious) {
    Bundle bundle = assertPatientSearchset(PATIENT_PATH + "?" + query);

    List<String> ids = entryIds(bundle);
    assertEquals(expectedEntries, ids.size(), () -> query + ": " + ids);
    assertTrue(expectedIds.containsAll(ids), () -> query + ": " + ids);
    assertEquals(expectNext, bundle.getLink(Bundle.LINK_NEXT) != null, query);
    assertEquals(expectPrevious, bundle.getLink(Bundle.LINK_PREV) != null, query);

    Set<String> allPages = new HashSet<>(ids);
    for (String relation : List.of(Bundle.LINK_NEXT, Bundle.LINK_PREV)) {
      BundleLinkComponent link = bundle.getLink(relation);
      if (link != null) {
        List<String> linkedIds = entryIds(assertPatientSearchset(apiPathOf(link.getUrl())));
        assertTrue(linkedIds.stream().noneMatch(ids::contains), () -> relation + ": " + linkedIds);
        allPages.addAll(linkedIds);
      }
    }
    if (expectNext || expectPrevious) {
      assertEquals(expectedIds, allPages, query);
    }
  }

  @Test
  void patientEverythingReturnsPatientAndEventResources() {
    Bundle summer = parseOk(GET(PATIENT_PATH + "/" + SUMMER + EVERYTHING), Bundle.class);
    assertEverything(
        summer,
        SUMMER,
        Map.of(
            "nxP7UnKhomJ-pTzf9KYMk72", ResourceType.Encounter,
            "nxP7UnKhomJ-pTzf9KYMk72-DATAEL00001", ResourceType.Immunization,
            "nxP7UnKhomJ-pTzf9KYMk72-DATAEL00006", ResourceType.Observation));

    Bundle frank = parseOk(GET(PATIENT_PATH + "/" + FRANK + EVERYTHING), Bundle.class);
    assertEverything(
        frank,
        FRANK,
        Map.of(
            "TvctPPhpD8z-D9PbzJY8bJM", ResourceType.Encounter,
            "TvctPPhpD8z-D9PbzJY8bJM-DATAEL00001", ResourceType.Immunization,
            "TvctPPhpD8z-D9PbzJY8bJM-DATAEL00006", ResourceType.Observation,
            "TvctPPhpD8z-D9PbzJY8bJM-GieVkTxp4HH", ResourceType.Observation));
    Immunization immunization =
        (Immunization) resourcesById(frank).get("TvctPPhpD8z-D9PbzJY8bJM-DATAEL00001");
    assertEquals("value00002", immunization.getLotNumber());
  }

  @Test
  void patientEverythingRejectsParametersOtherThanFormat() {
    String everything = PATIENT_PATH + "/" + FRANK + EVERYTHING;
    Bundle bundle = parseOk(GET(everything + "?_format=json"), Bundle.class);
    assertEquals(FRANK, entryIds(bundle).get(0));

    assertInvalid(everything + "?_count=1", "_count");
    assertInvalid(everything + "?foo=1", "foo");
  }

  @Test
  void searchPatientsRejectsInvalidParameters() {
    Map<String, String> cases = new LinkedHashMap<>();
    cases.put("foo=1", "foo");
    cases.put("family:exact=rain", "family:exact");
    cases.put("family=rain&family=sun", "family");
    cases.put("_count=abc", "_count");
    cases.put("_count=0", "_count");
    cases.put("_page=0", "_page");
    cases.put("_page=x", "_page");
    cases.put("_id=bad", "_id");
    cases.put("identifier=urn:unknown|70", "identifier");
    cases.put("birthdate=2000-01-01", "birthdate");
    cases.put("gender=male", "gender");
    cases.put("_format=xml", "_format");
    assertAll(
        cases.entrySet().stream()
            .map(
                invalid ->
                    (Executable)
                        () ->
                            assertInvalid(
                                PATIENT_PATH + "?" + invalid.getKey(), invalid.getValue())));

    String previousLimit =
        GET("/systemSettings/{key}", MAX_LIMIT_SETTING, Accept("text/plain")).content("text/plain");
    try {
      setMaxLimit("2");
      assertInvalid(PATIENT_PATH + "?family=rain&_count=3", "_count");
    } finally {
      setMaxLimit(previousLimit);
    }
  }

  @Test
  void searchWithNonSearchableAttributeOutsideCaptureScopeReturnsInvalid() {
    User user = userWithScope(OTHER_ORG_UNIT, ROOT_ORG_UNIT);
    String query = PATIENT_PATH + "?family=summer";
    List<String> superuserIds = entryIds(assertPatientSearchset(query));
    assertTrue(superuserIds.contains(SUMMER), superuserIds::toString);

    withPublicSharing(
        Map.of(TYPE_SHARING, DATA_READ),
        () -> {
          asUser(user, () -> assertFalse(entryIds(assertPatientSearchset(query)).isEmpty()));
          try {
            setTypeAttributeSearchable(FAMILY_ATTRIBUTE, false);
            asUser(user, () -> assertInvalid(query, "family"));
          } finally {
            setTypeAttributeSearchable(FAMILY_ATTRIBUTE, true);
          }
        });
  }

  @Test
  void searchRejectsAttributeConstraintViolations() {
    String search = PATIENT_PATH + "?family=rain&";
    assertInvalid(search + "identifier=" + IDENTIFIER_SYSTEM + "|abc", "identifier");

    JsonObject given = attributeSearchSettings(GIVEN_ATTRIBUTE);
    int previousMinCharacters = given.getNumber("minCharactersToSearch").intValue();
    try {
      patchAttribute(GIVEN_ATTRIBUTE, "minCharactersToSearch", "3");
      assertInvalid(search + "given=Fr", "given");
    } finally {
      patchAttribute(
          GIVEN_ATTRIBUTE, "minCharactersToSearch", String.valueOf(previousMinCharacters));
    }

    JsonArray previousBlocked =
        attributeSearchSettings(FAMILY_ATTRIBUTE).getArray("blockedSearchOperators");
    try {
      patchAttribute(FAMILY_ATTRIBUTE, "blockedSearchOperators", "['SW']");
      assertInvalid(search + "given=Fra", "family");
    } finally {
      patchAttribute(
          FAMILY_ATTRIBUTE,
          "blockedSearchOperators",
          previousBlocked.isUndefined() || previousBlocked.isNull()
              ? "[]"
              : previousBlocked.toJson());
    }
    assertFalse(entryIds(assertPatientSearchset(search + "given=Fra")).isEmpty());
  }

  @Test
  void readUnknownPatientReturnsNotFound() {
    assertNotFound(PATIENT_PATH + "/" + CodeGenerator.generateUid());
    assertNotFound(PATIENT_PATH + "/" + OTHER_TYPE_TRACKED_ENTITY);
  }

  @Test
  void readPatientOutsideUserScopeReturnsNotFound() {
    assertEquals(SUMMER, parseOk(GET(PATIENT_PATH + "/" + SUMMER), Patient.class).getIdPart());
    User user = userWithScope(OTHER_ORG_UNIT, OTHER_ORG_UNIT);

    withPublicSharing(
        Map.of(TYPE_SHARING, DATA_READ),
        () -> asUser(user, () -> assertNotFound(PATIENT_PATH + "/" + SUMMER)));
  }

  @Test
  void forbiddenResponseIsIdenticalForExistingAndUnknownIds() {
    assertEquals(SUMMER, parseOk(GET(PATIENT_PATH + "/" + SUMMER), Patient.class).getIdPart());
    String unknown = CodeGenerator.generateUid();
    Set<String> bodies = new HashSet<>();
    asRestrictedUserWithBaseline(
        () ->
            assertEquals(
                SUMMER, parseOk(GET(PATIENT_PATH + "/" + SUMMER), Patient.class).getIdPart()));

    for (String typeAccess : List.of(NO_ACCESS, METADATA_ONLY)) {
      withPublicSharing(
          baselineWith(TYPE_SHARING, typeAccess),
          () ->
              asRestrictedUser(
                  () -> {
                    String existingBody = assertForbidden(PATIENT_PATH + "/" + SUMMER);
                    String unknownBody = assertForbidden(PATIENT_PATH + "/" + unknown);

                    assertEquals(existingBody, unknownBody, typeAccess);
                    assertFalse(existingBody.contains(SUMMER), existingBody);
                    assertFalse(unknownBody.contains(unknown), unknownBody);
                    bodies.add(existingBody);
                  }));
    }
    assertEquals(1, bodies.size(), bodies::toString);
  }

  @Test
  void searchPatientsForbiddenWithoutTypeReadAccess() {
    assertEquals(
        List.of(SUMMER), entryIds(assertPatientSearchset(PATIENT_PATH + "?_id=" + SUMMER)));
    String unknown = CodeGenerator.generateUid();

    withPublicSharing(
        baselineWith(TYPE_SHARING, NO_ACCESS),
        () ->
            asRestrictedUser(
                () -> {
                  String byId = assertForbidden(PATIENT_PATH + "?_id=" + SUMMER);
                  String byFamily = assertForbidden(PATIENT_PATH + "?family=summer");
                  String byUnknownId = assertForbidden(PATIENT_PATH + "?_id=" + unknown);

                  assertEquals(byId, byFamily);
                  assertEquals(byId, byUnknownId);
                }));
  }

  @Test
  void patientEverythingOmitsUnreadableProgram() {
    String everything = PATIENT_PATH + "/" + SUMMER + EVERYTHING;
    String superuserBody = fhirBody(GET(everything), HttpStatus.OK);
    assertTrue(superuserBody.contains("nxP7UnKhomJ-pTzf9KYMk72"), superuserBody);
    asRestrictedUserWithBaseline(
        () -> {
          String body = fhirBody(GET(everything), HttpStatus.OK);
          assertTrue(body.contains("nxP7UnKhomJ-pTzf9KYMk72-DATAEL00006"), body);
        });

    withPublicSharing(
        baselineWith(PROGRAM_SHARING, NO_ACCESS),
        () ->
            asRestrictedUser(
                () -> {
                  String body = fhirBody(GET(everything), HttpStatus.OK);
                  Bundle bundle = parse(body, Bundle.class);

                  assertEquals(List.of(SUMMER), entryIds(bundle), body);
                  assertEquals(
                      ResourceType.Patient,
                      bundle.getEntryFirstRep().getResource().getResourceType());
                  assertEquals(1, bundle.getTotal());
                  for (String hidden :
                      List.of("pTzf9KYMk72", "nxP7UnKhomJ", "DATAEL00001", "DATAEL00006")) {
                    assertFalse(body.contains(hidden), () -> hidden + " in " + body);
                  }
                }));
  }

  @Test
  void hiddenAttributeIsAbsentFromPatient() {
    assertFrankPatient(parseOk(GET(PATIENT_PATH + "/" + FRANK), Patient.class));
    asRestrictedUserWithBaseline(
        () -> assertFrankPatient(parseOk(GET(PATIENT_PATH + "/" + FRANK), Patient.class)));

    withPublicSharing(
        baselineWith(GIVEN_ATTRIBUTE_SHARING, NO_ACCESS),
        () ->
            asRestrictedUser(
                () -> {
                  String body = fhirBody(GET(PATIENT_PATH + "/" + FRANK), HttpStatus.OK);
                  Patient patient = parse(body, Patient.class);

                  assertEquals(FRANK, patient.getIdPart());
                  assertEquals(1, patient.getName().size(), body);
                  HumanName name = patient.getNameFirstRep();
                  assertEquals("rainy day", name.getFamily());
                  assertFalse(name.hasGiven(), body);
                  assertFalse(body.contains("Frank"), body);
                }));
  }

  /** Asserts the mapped elements of the fixture tracked entity {@code dUE514NMOlo}. */
  private static void assertFrankPatient(Patient patient) {
    assertEquals(FRANK, patient.getIdElement().getIdPart());
    assertEquals(1, patient.getName().size());
    HumanName name = patient.getNameFirstRep();
    assertEquals("rainy day", name.getFamily());
    assertEquals(1, name.getGiven().size());
    assertEquals("Frank PTEA", name.getGiven().get(0).getValue());
    assertEquals(1, patient.getIdentifier().size());
    Identifier identifier = patient.getIdentifierFirstRep();
    assertEquals(IDENTIFIER_SYSTEM, identifier.getSystem());
    assertEquals("70", identifier.getValue());
  }

  /**
   * Asserts a {@code 200} {@code searchset} Bundle of Patients without {@code total}, whose entries
   * are matches with Patient {@code fullUrl}s and which carries a {@code self} link.
   */
  private Bundle assertPatientSearchset(String url) {
    Bundle bundle = parseOk(GET(url), Bundle.class);

    assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType(), url);
    assertFalse(bundle.hasTotal(), url);
    BundleLinkComponent self = bundle.getLink(Bundle.LINK_SELF);
    assertNotNull(self, url);
    assertTrue(self.getUrl().contains(PATIENT_PATH), self::getUrl);
    for (BundleEntryComponent entry : bundle.getEntry()) {
      assertEquals(ResourceType.Patient, entry.getResource().getResourceType(), url);
      String id = entry.getResource().getIdElement().getIdPart();
      assertTrue(entry.getFullUrl().endsWith(PATIENT_PATH + "/" + id), entry::getFullUrl);
      assertEquals(Bundle.SearchEntryMode.MATCH, entry.getSearch().getMode(), url);
    }
    return bundle;
  }

  /**
   * Asserts a {@code $everything} Bundle: a {@code searchset} whose first entry is the Patient
   * {@code patientId}, whose remaining entries are exactly {@code related} by id and resource type,
   * and whose {@code total} is the number of entries.
   */
  private static void assertEverything(
      Bundle bundle, String patientId, Map<String, ResourceType> related) {
    assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType());
    assertFalse(bundle.getEntry().isEmpty());
    Resource first = bundle.getEntryFirstRep().getResource();
    assertEquals(ResourceType.Patient, first.getResourceType());
    assertEquals(patientId, first.getIdElement().getIdPart());

    Map<String, ResourceType> actual =
        bundle.getEntry().stream()
            .skip(1)
            .map(BundleEntryComponent::getResource)
            .collect(
                Collectors.toMap(
                    resource -> resource.getIdElement().getIdPart(), Resource::getResourceType));
    assertEquals(related, actual);
    assertEquals(related.size() + 1, bundle.getEntry().size());
    assertEquals(bundle.getEntry().size(), bundle.getTotal());
    for (BundleEntryComponent entry : bundle.getEntry()) {
      Resource resource = entry.getResource();
      assertTrue(
          entry
              .getFullUrl()
              .endsWith(
                  "/api/fhir/" + resource.fhirType() + "/" + resource.getIdElement().getIdPart()),
          entry::getFullUrl);
    }
  }

  /** Returns the resources of a Bundle by logical id. */
  private static Map<String, Resource> resourcesById(Bundle bundle) {
    return bundle.getEntry().stream()
        .map(BundleEntryComponent::getResource)
        .collect(
            Collectors.toMap(resource -> resource.getIdElement().getIdPart(), Function.identity()));
  }

  /** Returns the logical ids of a Bundle's entries in entry order. */
  private static List<String> entryIds(Bundle bundle) {
    return bundle.getEntry().stream()
        .map(entry -> entry.getResource().getIdElement().getIdPart())
        .toList();
  }

  /** Returns the path and query of an absolute link URL, starting at {@code /api/}. */
  private static String apiPathOf(String url) {
    int start = url.indexOf("/api/");
    assertTrue(start >= 0, url);
    return url.substring(start);
  }

  /** Asserts a {@code 200} FHIR JSON response and parses its body strictly as {@code type}. */
  private static <T extends Resource> T parseOk(HttpResponse response, Class<T> type) {
    return parse(fhirBody(response, HttpStatus.OK), type);
  }

  /** Parses FHIR JSON strictly: unknown elements and invalid values fail the parse. */
  private static <T extends Resource> T parse(String body, Class<T> type) {
    return FhirContext.forR4Cached()
        .newJsonParser()
        .setParserErrorHandler(new StrictErrorHandler())
        .parseResource(type, body);
  }

  /**
   * Asserts the status and the {@code application/fhir+json;charset=UTF-8} content type of a
   * response and returns its body.
   */
  private static String fhirBody(HttpResponse response, HttpStatus status) {
    assertEquals(status, response.status(), () -> response.contentUnchecked().toString());
    String body = response.content(FHIR_JSON);
    assertEquals(
        FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE,
        MediaType.parseMediaType(response.getContentType()),
        response.getContentType());
    return body;
  }

  /**
   * Asserts a {@code GET} answered with {@code status} and an {@code OperationOutcome} holding
   * exactly one {@code error} issue with {@code code} whose diagnostics satisfy {@code
   * diagnostics}. Returns the raw body.
   */
  private String assertOutcome(
      String url, HttpStatus status, IssueType code, Predicate<String> diagnostics) {
    String body = fhirBody(GET(url), status);
    OperationOutcome outcome = parse(body, OperationOutcome.class);

    assertEquals(1, outcome.getIssue().size(), () -> url + ": " + body);
    OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
    assertEquals(OperationOutcome.IssueSeverity.ERROR, issue.getSeverity(), url);
    assertEquals(code, issue.getCode(), () -> url + ": " + body);
    assertTrue(diagnostics.test(issue.getDiagnostics()), () -> url + ": " + body);
    return body;
  }

  /** Asserts {@code 400 invalid} whose diagnostics name exactly {@code parameter}. */
  private void assertInvalid(String url, String parameter) {
    String prefix = "Invalid parameter '" + parameter + "':";
    assertOutcome(url, HttpStatus.BAD_REQUEST, IssueType.INVALID, d -> d.startsWith(prefix));
  }

  private void assertNotFound(String url) {
    String diagnostics = FhirApiException.notFound().getDiagnostics();
    assertOutcome(url, HttpStatus.NOT_FOUND, IssueType.NOTFOUND, diagnostics::equals);
  }

  /** Asserts the {@code 403 forbidden} outcome with its fixed diagnostics and returns the body. */
  private String assertForbidden(String url) {
    String diagnostics = FhirApiException.forbidden().getDiagnostics();
    return assertOutcome(url, HttpStatus.FORBIDDEN, IssueType.FORBIDDEN, diagnostics::equals);
  }

  /**
   * Returns the baseline public sharing, data read on the Person type and both mapped programs and
   * metadata read on the given-name attribute, with {@code restricted} set to {@code access}.
   */
  private static Map<SharedObject, String> baselineWith(SharedObject restricted, String access) {
    Map<SharedObject, String> sharing = new LinkedHashMap<>();
    sharing.put(TYPE_SHARING, DATA_READ);
    sharing.put(PROGRAM_SHARING, DATA_READ);
    sharing.put(OTHER_PROGRAM_SHARING, DATA_READ);
    sharing.put(GIVEN_ATTRIBUTE_SHARING, METADATA_ONLY);
    sharing.put(restricted, access);
    return sharing;
  }

  /**
   * Sets the given public sharing through {@code /api/sharing}, runs {@code test} and restores the
   * public access each object reported before. A reported access string that is malformed, and
   * therefore grants nothing, is restored as {@code --------}.
   */
  private void withPublicSharing(Map<SharedObject, String> sharing, Runnable test) {
    Map<SharedObject, String> previous = new LinkedHashMap<>();
    try {
      sharing.forEach(
          (object, access) -> {
            String reported = publicSharing(object.type(), object.uid());
            previous.put(object, ACCESS_STRING.matcher(reported).matches() ? reported : NO_ACCESS);
            setPublicSharing(object.type(), object.uid(), access);
          });
      test.run();
    } finally {
      switchContextToUser(importUser);
      previous.forEach((object, access) -> setPublicSharing(object.type(), object.uid(), access));
    }
  }

  /** Returns the public access {@code /api/sharing} reports for an object. */
  private String publicSharing(String type, String uid) {
    return GET("/sharing?type={type}&id={id}", type, uid)
        .content()
        .getObject("object")
        .getString("publicAccess")
        .string();
  }

  /** Sets the public access of an object through {@code /api/sharing}. */
  private void setPublicSharing(String type, String uid, String access) {
    assertStatus(
        HttpStatus.OK,
        POST(
            "/sharing?type=" + type + "&id=" + uid,
            "{'object':{'publicAccess':'" + access + "'}}"));
    manager.clear();
  }

  /**
   * Creates a non-superuser without authorities and without user or group sharing, with the given
   * capture and search org units.
   */
  private User userWithScope(String captureOrgUnit, String searchOrgUnit) {
    User user = createUserWithAuth("fhirpatientuser" + userCounter.incrementAndGet());
    user.addOrganisationUnit(manager.get(OrganisationUnit.class, captureOrgUnit));
    user.setTeiSearchOrganisationUnits(Set.of(manager.get(OrganisationUnit.class, searchOrgUnit)));
    userService.updateUser(user);
    manager.clear();
    return user;
  }

  /** Creates the restricted user: capture and search org unit {@code h4w96yEMlzO}. */
  private User restrictedUser() {
    return userWithScope(ROOT_ORG_UNIT, ROOT_ORG_UNIT);
  }

  /** Runs {@code test} as a newly created {@link #restrictedUser()}. */
  private void asRestrictedUser(Runnable test) {
    asUser(restrictedUser(), test);
  }

  /** Runs {@code test} as a newly created {@link #restrictedUser()} with the baseline sharing. */
  private void asRestrictedUserWithBaseline(Runnable test) {
    withPublicSharing(baselineWith(TYPE_SHARING, DATA_READ), () -> asRestrictedUser(test));
  }

  /** Runs {@code test} as {@code user}, then switches back to the import user. */
  private void asUser(User user, Runnable test) {
    switchContextToUser(user);
    try {
      test.run();
    } finally {
      switchContextToUser(importUser);
    }
  }

  private void patchPatientMapping(String patch) {
    assertStatus(HttpStatus.OK, PATCH("/fhirResourceMappings/" + PATIENT_MAPPING, patch));
    manager.clear();
  }

  /** Returns the search settings of a tracked entity attribute. */
  private JsonObject attributeSearchSettings(String attribute) {
    return GET(
            "/trackedEntityAttributes/{id}?fields=minCharactersToSearch,blockedSearchOperators",
            attribute)
        .content();
  }

  /** Sets one property of a tracked entity attribute through a JSON patch. */
  private void patchAttribute(String attribute, String property, String jsonValue) {
    assertStatus(
        HttpStatus.OK,
        PATCH(
            "/trackedEntityAttributes/" + attribute,
            "[{'op':'add','path':'/" + property + "','value':" + jsonValue + "}]"));
    manager.clear();
  }

  /** Sets whether an attribute of the Person type is searchable, through a JSON patch. */
  private void setTypeAttributeSearchable(String attribute, boolean searchable) {
    List<String> attributes =
        GET(
                "/trackedEntityTypes/{id}?fields=trackedEntityTypeAttributes[trackedEntityAttribute[id]]",
                PERSON_TYPE)
            .content()
            .getList("trackedEntityTypeAttributes", JsonObject.class)
            .toList(
                typeAttribute ->
                    typeAttribute.getObject("trackedEntityAttribute").getString("id").string());
    int index = attributes.indexOf(attribute);
    assertTrue(index >= 0, attributes::toString);

    assertStatus(
        HttpStatus.OK,
        PATCH(
            "/trackedEntityTypes/" + PERSON_TYPE,
            "[{'op':'replace','path':'/trackedEntityTypeAttributes/"
                + index
                + "/searchable','value':"
                + searchable
                + "}]"));
    manager.clear();

    JsonObject updated =
        GET(
                "/trackedEntityTypes/{id}?fields=trackedEntityTypeAttributes[searchable,trackedEntityAttribute[id]]",
                PERSON_TYPE)
            .content()
            .getArray("trackedEntityTypeAttributes")
            .getObject(index);
    assertEquals(attribute, updated.getObject("trackedEntityAttribute").getString("id").string());
    assertEquals(searchable, updated.getBoolean("searchable").booleanValue());
  }

  private void setMaxLimit(String value) {
    assertStatus(HttpStatus.OK, POST("/systemSettings/" + MAX_LIMIT_SETTING + "?value=" + value));
    manager.clear();
  }

  private void deleteAllMappings() {
    doInTransaction(() -> manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete));
  }

  /** An object whose public sharing a test changes, by its {@code /api/sharing} type and UID. */
  private record SharedObject(String type, String uid) {}
}
