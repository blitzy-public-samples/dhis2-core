/*
 * Copyright (c) 2004-2025, University of Oslo
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.annotation.CheckForNull;
import org.hisp.dhis.common.CodeGenerator;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.fhir.mapping.FhirResourceMapping;
import org.hisp.dhis.http.HttpClientAdapter.HttpResponse;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.JsonArray;
import org.hisp.dhis.jsontree.JsonObject;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.test.config.PostgresDhisConfigurationProvider;
import org.hisp.dhis.test.webapi.PostgresControllerIntegrationTestBase;
import org.hisp.dhis.trackedentity.TrackedEntityType;
import org.hisp.dhis.user.User;
import org.hisp.dhis.util.DateUtils;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;

/**
 * Tests the FHIR R4 {@code Encounter}, {@code Immunization} and {@code Observation} read and
 * search-type interactions on PostgreSQL with {@code fhir.api.enabled=true}, the base Tracker
 * fixtures and the mappings of {@value #MAPPINGS_FILE}.
 *
 * <p>Every response is checked for the FHIR JSON content type and parsed strictly. Access tests run
 * as a non-superuser whose capture and search org unit covers the fixtures and whose access to the
 * mapped metadata comes from public sharing only. Each access test changes the public access of one
 * or two objects through {@code /api/sharing} and restores it afterwards: programs keep metadata
 * access and lose data read, data elements lose all access.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ContextConfiguration(classes = FhirEventResourceControllerTest.FhirApiEnabledConfig.class)
class FhirEventResourceControllerTest extends PostgresControllerIntegrationTestBase {

  private static final String IMPORT_USER_UID = "tTgjgobT1oS";
  private static final String MAPPINGS_FILE = "fhir/fhir_resource_mappings.json";
  private static final String RESTRICTED_USERNAME = "fhireventreader";
  private static final String ORG_UNIT = "h4w96yEMlzO";
  private static final String TRACKED_ENTITY_TYPE = "ja8NY4PW7Xm";
  private static final String PROGRAM = "BFcipDERJnf";
  private static final String SECOND_PROGRAM = "shPjYNifvMK";

  private static final String PATIENT_A = "QS6w44flWAf";
  private static final String PATIENT_B = "dUE514NMOlo";
  private static final String ENROLLMENT_A = "nxP7UnKhomJ";
  private static final String SECOND_PROGRAM_ENROLLMENT_A = "nxP8UnKhomJ";
  private static final String EVENT_A = "pTzf9KYMk72";
  private static final String EVENT_A_OCCURRED_AT = "2019-01-25T12:10:38.100";
  private static final String ENROLLMENT_B = "TvctPPhpD8z";
  private static final String EVENT_B = "D9PbzJY8bJM";
  private static final String EVENT_B_OCCURRED_AT = "2020-01-28T00:00:00.000";
  private static final String DE_ADMINISTERED = "DATAEL00001";
  private static final String DE_INTEGER = "DATAEL00006";
  private static final String DE_NUMBER = "GieVkTxp4HH";

  private static final String ENCOUNTER_A = ENROLLMENT_A + "-" + EVENT_A;
  private static final String IMMUNIZATION_A = ENCOUNTER_A + "-" + DE_ADMINISTERED;
  private static final String OBSERVATION_A = ENCOUNTER_A + "-" + DE_INTEGER;
  private static final String ENCOUNTER_B = ENROLLMENT_B + "-" + EVENT_B;
  private static final String IMMUNIZATION_B = ENCOUNTER_B + "-" + DE_ADMINISTERED;
  private static final String OBSERVATION_B_INTEGER = ENCOUNTER_B + "-" + DE_INTEGER;
  private static final String OBSERVATION_B_NUMBER = ENCOUNTER_B + "-" + DE_NUMBER;

  private static final String ENCOUNTER = "/api/fhir/Encounter";
  private static final String IMMUNIZATION = "/api/fhir/Immunization";
  private static final String OBSERVATION = "/api/fhir/Observation";
  private static final List<String> EVENT_RESOURCES = List.of(ENCOUNTER, IMMUNIZATION, OBSERVATION);

  private static final String ACT_CODE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ActCode";
  private static final String ENCOUNTER_TYPE_SYSTEM = "urn:dhis2:fhir-test:encounter-type";
  private static final String CVX_SYSTEM = "http://hl7.org/fhir/sid/cvx";
  private static final String OBSERVATION_SYSTEM = "urn:dhis2:fhir-test:observation";

  private static final String NO_ACCESS = "--------";
  private static final String DATA_READ = "rwrw----";
  private static final String METADATA_ONLY = "rw------";
  private static final Restriction NO_PROGRAM_DATA_READ =
      new Restriction("program", PROGRAM, METADATA_ONLY);
  private static final Restriction NO_SECOND_PROGRAM_DATA_READ =
      new Restriction("program", SECOND_PROGRAM, METADATA_ONLY);

  @Autowired private TestSetup testSetup;

  private User importUser;

  private String restrictedUserUid;

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

    grantPublicAccess(TrackedEntityType.class, TRACKED_ENTITY_TYPE, DATA_READ);
    for (String program : List.of(PROGRAM, SECOND_PROGRAM)) {
      grantPublicAccess(Program.class, program, DATA_READ);
    }
    restrictedUserUid = createRestrictedUser();
    manager.flush();
    manager.clear();
  }

  @AfterAll
  void deleteFixtureMappings() {
    injectSecurityContextUser(importUser);
    deleteAllMappings();
  }

  @BeforeEach
  void useImportUser() {
    switchContextToUser(importUser);
  }

  @Test
  void readEncounterImmunizationObservationReturnValidResources() {
    Encounter encounter = parse(GET(ENCOUNTER + "/" + ENCOUNTER_A), Encounter.class);
    assertEquals(ENCOUNTER_A, encounter.getIdPart());
    assertNotNull(encounter.getMeta().getLastUpdated());
    assertEquals(Encounter.EncounterStatus.FINISHED, encounter.getStatus());
    assertCoding(ACT_CODE_SYSTEM, "AMB", encounter.getClass_());
    assertEquals(1, encounter.getType().size());
    assertCoding(ENCOUNTER_TYPE_SYSTEM, "option1", encounter.getTypeFirstRep().getCodingFirstRep());
    assertEquals("Patient/" + PATIENT_A, encounter.getSubject().getReference());
    assertEquals(
        occurredAt(EVENT_A_OCCURRED_AT), encounter.getPeriod().getStart().toInstant(), "start");

    Immunization immunization = parse(GET(IMMUNIZATION + "/" + IMMUNIZATION_A), Immunization.class);
    assertEquals(IMMUNIZATION_A, immunization.getIdPart());
    assertNotNull(immunization.getMeta().getLastUpdated());
    assertEquals(Immunization.ImmunizationStatus.COMPLETED, immunization.getStatus());
    assertEquals(1, immunization.getVaccineCode().getCoding().size());
    assertCoding(CVX_SYSTEM, "08", immunization.getVaccineCode().getCodingFirstRep());
    assertEquals("Patient/" + PATIENT_A, immunization.getPatient().getReference());
    assertEquals("Encounter/" + ENCOUNTER_A, immunization.getEncounter().getReference());
    assertEquals(
        occurredAt(EVENT_A_OCCURRED_AT),
        immunization.getOccurrenceDateTimeType().getValue().toInstant(),
        "occurrence");
    assertFalse(immunization.hasLotNumber());

    Immunization withLot = parse(GET(IMMUNIZATION + "/" + IMMUNIZATION_B), Immunization.class);
    assertEquals("value00002", withLot.getLotNumber());

    Observation observation =
        parse(GET(OBSERVATION + "/" + OBSERVATION_B_NUMBER), Observation.class);
    assertEquals(OBSERVATION_B_NUMBER, observation.getIdPart());
    assertNotNull(observation.getMeta().getLastUpdated());
    assertEquals(Observation.ObservationStatus.FINAL, observation.getStatus());
    assertEquals(1, observation.getCode().getCoding().size());
    assertCoding(OBSERVATION_SYSTEM, "number-value", observation.getCode().getCodingFirstRep());
    Quantity quantity = observation.getValueQuantity();
    assertEquals(
        0,
        new BigDecimal("15").compareTo(quantity.getValue()),
        () -> "value " + quantity.getValue());
    assertEquals("kg", quantity.getUnit());
    assertEquals("Patient/" + PATIENT_B, observation.getSubject().getReference());
    assertEquals("Encounter/" + ENCOUNTER_B, observation.getEncounter().getReference());
    assertEquals(
        occurredAt(EVENT_B_OCCURRED_AT),
        observation.getEffectiveDateTimeType().getValue().toInstant(),
        "effective");
  }

  @Test
  void readAcceptsFormatAndRejectsOtherParameters() {
    assertEquals(
        ENCOUNTER_A,
        parse(GET(ENCOUNTER + "/" + ENCOUNTER_A + "?_format=json"), Encounter.class).getIdPart());
    assertEquals(
        IMMUNIZATION_A,
        parse(GET(IMMUNIZATION + "/" + IMMUNIZATION_A + "?_format=json"), Immunization.class)
            .getIdPart());
    assertEquals(
        OBSERVATION_A,
        parse(GET(OBSERVATION + "/" + OBSERVATION_A + "?_format=json"), Observation.class)
            .getIdPart());

    for (String read :
        List.of(
            ENCOUNTER + "/" + ENCOUNTER_A,
            IMMUNIZATION + "/" + IMMUNIZATION_A,
            OBSERVATION + "/" + OBSERVATION_A)) {
      assertInvalid(read + "?_format=xml", "_format");
      assertInvalid(read + "?foo=1", "foo");
    }
  }

  @Test
  void searchByPatientAndSubjectReturnsBundlesForEachType() {
    Map<String, String> expected =
        Map.of(ENCOUNTER, ENCOUNTER_A, IMMUNIZATION, IMMUNIZATION_A, OBSERVATION, OBSERVATION_A);
    for (String path : EVENT_RESOURCES) {
      List<String> queries = new ArrayList<>();
      queries.add("patient=" + PATIENT_A);
      queries.add("patient=Patient/" + PATIENT_A);
      if (!IMMUNIZATION.equals(path)) {
        queries.add("subject=" + PATIENT_A);
        queries.add("subject=Patient/" + PATIENT_A);
      }
      for (String query : queries) {
        Bundle bundle = search(path, query);
        assertEquals(List.of(expected.get(path)), entryIds(bundle), path + "?" + query);
        assertEquals(1, bundle.getTotal(), path + "?" + query);
      }

      Bundle unknownPatient = search(path, "patient=" + CodeGenerator.generateUid());
      assertTrue(unknownPatient.getEntry().isEmpty(), path);
      assertEquals(0, unknownPatient.getTotal(), path);
    }
  }

  @Test
  void searchByIdAndCodeSelectsResources() {
    assertEquals(List.of(ENCOUNTER_B), entryIds(search(ENCOUNTER, "_id=" + ENCOUNTER_B)));
    assertEquals(
        List.of(OBSERVATION_B_NUMBER),
        entryIds(search(OBSERVATION, "_id=" + OBSERVATION_B_NUMBER)));

    Bundle bySystemAndCode =
        search(OBSERVATION, "code=" + OBSERVATION_SYSTEM + "|integer-value&patient=" + PATIENT_B);
    assertEquals(List.of(OBSERVATION_B_INTEGER), entryIds(bySystemAndCode));
    assertEquals(1, bySystemAndCode.getTotal());

    Bundle byCode = search(OBSERVATION, "code=integer-value&patient=" + PATIENT_B);
    assertEquals(List.of(OBSERVATION_B_INTEGER), entryIds(byCode));
    assertEquals(1, byCode.getTotal());

    Bundle unknownCode = search(OBSERVATION, "code=urn:x|nope&patient=" + PATIENT_B);
    assertTrue(unknownCode.getEntry().isEmpty());
    assertEquals(0, unknownCode.getTotal());
  }

  @Test
  void searchPagesWithCountAndPage() {
    String query = "patient=" + PATIENT_B + "&_count=1";

    Bundle first = search(OBSERVATION, query + "&_page=1");
    assertEquals(List.of(OBSERVATION_B_INTEGER), entryIds(first));
    assertEquals(2, first.getTotal());
    assertNotNull(first.getLink("self"));
    assertNotNull(first.getLink("next"));
    assertTrue(first.getLink("next").getUrl().contains("_page=2"), first.getLink("next")::getUrl);
    assertNull(first.getLink("previous"));

    Bundle second = search(OBSERVATION, query + "&_page=2");
    assertEquals(List.of(OBSERVATION_B_NUMBER), entryIds(second));
    assertEquals(2, second.getTotal());
    assertNotNull(second.getLink("previous"));
    assertTrue(
        second.getLink("previous").getUrl().contains("_page=1"),
        second.getLink("previous")::getUrl);
    assertNull(second.getLink("next"));

    Bundle formatted = search(OBSERVATION, query + "&_page=2&_format=json");
    assertEquals(List.of(OBSERVATION_B_NUMBER), entryIds(formatted));
    assertEquals(2, formatted.getTotal());
  }

  @Test
  void searchRejectsInvalidParameters() {
    for (String path : EVENT_RESOURCES) {
      String byPatient = path + "?patient=" + PATIENT_A;
      assertInvalid(path, "patient");
      assertInvalid(path + "?patient=bad", "patient");
      assertInvalid(path + "?_id=bad", "_id");
      assertInvalid(byPatient + "&_count=0", "_count");
      assertInvalid(byPatient + "&_count=x", "_count");
      assertInvalid(byPatient + "&_page=0", "_page");
      assertInvalid(byPatient + "&_format=xml", "_format");
      assertInvalid(byPatient + "&foo=1", "foo");
    }
    assertInvalid(ENCOUNTER + "?patient=" + PATIENT_A + "&subject=" + PATIENT_A, "subject");
    assertInvalid(OBSERVATION + "?patient=" + PATIENT_A + "&subject=" + PATIENT_A, "subject");
    assertInvalid(IMMUNIZATION + "?subject=" + PATIENT_A, "subject");
  }

  @Test
  void readUnknownEncounterImmunizationObservationReturnsNotFound() {
    String unknownEvent = CodeGenerator.generateUid();
    List<String> reads =
        List.of(
            ENCOUNTER + "/" + CodeGenerator.generateUid() + "-" + CodeGenerator.generateUid(),
            ENCOUNTER + "/" + ENROLLMENT_A + "-" + unknownEvent,
            IMMUNIZATION + "/" + unknownPerDataElementId(),
            IMMUNIZATION + "/" + ENROLLMENT_A + "-" + unknownEvent + "-" + DE_ADMINISTERED,
            IMMUNIZATION + "/" + ENCOUNTER_A + "-" + DE_INTEGER,
            OBSERVATION + "/" + unknownPerDataElementId(),
            OBSERVATION + "/" + ENROLLMENT_A + "-" + unknownEvent + "-" + DE_INTEGER,
            OBSERVATION + "/" + ENCOUNTER_A + "-" + DE_NUMBER);
    for (String read : reads) {
      assertNotFound(GET(read));
    }
  }

  @Test
  void readMalformedIdReturnsNotFound() {
    List<String> reads =
        List.of(
            ENCOUNTER + "/" + ENROLLMENT_A,
            ENCOUNTER + "/" + IMMUNIZATION_A,
            ENCOUNTER + "/1xP7UnKhomJ-" + EVENT_A,
            OBSERVATION + "/" + ENCOUNTER_A,
            OBSERVATION + "/" + ENCOUNTER_A + "-",
            IMMUNIZATION + "/abc-def-ghi");
    for (String read : reads) {
      assertNotFound(GET(read));
    }
  }

  @Test
  void forbiddenResponseIsIdenticalForExistingAndUnknownIds() {
    String unknownId = CodeGenerator.generateUid() + "-" + CodeGenerator.generateUid();
    String existing = ENCOUNTER + "/" + ENCOUNTER_A;
    String unknown = ENCOUNTER + "/" + unknownId;
    assertEquals(ENCOUNTER_A, parse(GET(existing), Encounter.class).getIdPart());
    asRestrictedUser(
        List.of(),
        () -> assertEquals(ENCOUNTER_A, parse(GET(existing), Encounter.class).getIdPart()));

    asRestrictedUser(
        List.of(NO_PROGRAM_DATA_READ, NO_SECOND_PROGRAM_DATA_READ),
        () -> {
          String existingBody = assertForbidden(GET(existing));
          String unknownBody = assertForbidden(GET(unknown));
          assertEquals(existingBody, unknownBody);
          assertFalse(existingBody.contains(ENROLLMENT_A), existingBody);
          assertFalse(existingBody.contains(EVENT_A), existingBody);
          assertFalse(unknownBody.contains(unknownId), unknownBody);
        });

    asRestrictedUser(
        List.of(NO_PROGRAM_DATA_READ),
        () -> assertEquals(assertNotFound(GET(existing)), assertNotFound(GET(unknown))));
  }

  @Test
  void searchForbiddenOnlyWhenEveryProgramIsForbidden() {
    String encounters = ENCOUNTER + "?patient=" + PATIENT_A;
    String unknownPatientEncounters = ENCOUNTER + "?patient=" + CodeGenerator.generateUid();
    String observations = OBSERVATION + "?patient=" + PATIENT_B;
    assertEquals(List.of(ENCOUNTER_A), entryIds(search(ENCOUNTER, "patient=" + PATIENT_A)));
    assertEquals(
        List.of(OBSERVATION_B_INTEGER, OBSERVATION_B_NUMBER),
        entryIds(search(OBSERVATION, "patient=" + PATIENT_B)));

    asRestrictedUser(
        List.of(NO_PROGRAM_DATA_READ, NO_SECOND_PROGRAM_DATA_READ),
        () -> {
          String body = assertForbidden(GET(encounters));
          assertEquals(body, assertForbidden(GET(unknownPatientEncounters)));
          assertFalse(body.contains(PATIENT_A), body);
        });

    asRestrictedUser(List.of(NO_PROGRAM_DATA_READ), () -> assertForbidden(GET(observations)));
  }

  @Test
  void searchWithMixedProgramAccessReturnsAccessibleResources() {
    assertEquals(List.of(ENCOUNTER_A), entryIds(search(ENCOUNTER, "patient=" + PATIENT_A)));
    assertEquals(List.of(ENCOUNTER_B), entryIds(search(ENCOUNTER, "patient=" + PATIENT_B)));

    asRestrictedUser(
        List.of(NO_SECOND_PROGRAM_DATA_READ),
        () -> {
          String body = fhirBody(GET(ENCOUNTER + "?patient=" + PATIENT_A), HttpStatus.OK);
          Bundle bundle = searchset(ENCOUNTER, body);
          assertEquals(List.of(ENCOUNTER_A), entryIds(bundle));
          assertEquals(1, bundle.getTotal());
          assertFalse(body.contains(SECOND_PROGRAM_ENROLLMENT_A), body);
          assertFalse(body.contains(SECOND_PROGRAM), body);
        });

    asRestrictedUser(
        List.of(NO_PROGRAM_DATA_READ),
        () -> {
          String body = fhirBody(GET(ENCOUNTER + "?patient=" + PATIENT_B), HttpStatus.OK);
          Bundle bundle = searchset(ENCOUNTER, body);
          assertTrue(bundle.getEntry().isEmpty(), body);
          assertEquals(0, bundle.getTotal());
          assertFalse(body.contains(EVENT_B), body);
          assertFalse(body.contains(ENROLLMENT_B), body);
        });
  }

  @Test
  void hiddenDataElementProducesNoImmunizationOrObservation() {
    String observationRead = OBSERVATION + "/" + OBSERVATION_B_NUMBER;
    String immunizationRead = IMMUNIZATION + "/" + IMMUNIZATION_B;
    Runnable everyValueIsVisible =
        () -> {
          assertEquals(
              List.of(OBSERVATION_B_INTEGER, OBSERVATION_B_NUMBER),
              entryIds(search(OBSERVATION, "patient=" + PATIENT_B)));
          assertEquals(
              OBSERVATION_B_NUMBER, parse(GET(observationRead), Observation.class).getIdPart());
          assertEquals(
              List.of(IMMUNIZATION_B), entryIds(search(IMMUNIZATION, "patient=" + PATIENT_B)));
          assertEquals(
              IMMUNIZATION_B, parse(GET(immunizationRead), Immunization.class).getIdPart());
        };
    everyValueIsVisible.run();
    asRestrictedUser(List.of(), everyValueIsVisible);

    asRestrictedUser(
        List.of(new Restriction("dataElement", DE_NUMBER, NO_ACCESS)),
        () -> {
          Bundle observations = search(OBSERVATION, "patient=" + PATIENT_B);
          assertEquals(List.of(OBSERVATION_B_INTEGER), entryIds(observations));
          assertEquals(1, observations.getTotal());
          assertNotFound(GET(observationRead));
        });

    asRestrictedUser(
        List.of(new Restriction("dataElement", DE_ADMINISTERED, NO_ACCESS)),
        () -> {
          Bundle immunizations = search(IMMUNIZATION, "patient=" + PATIENT_B);
          assertTrue(immunizations.getEntry().isEmpty());
          assertEquals(0, immunizations.getTotal());
          assertNotFound(GET(immunizationRead));
        });
  }

  /**
   * Sends {@code GET {path}?{query}} and returns the {@code searchset} Bundle of a {@code 200}
   * response, checked as in {@link #searchset(String, String)}.
   */
  private Bundle search(String path, String query) {
    return searchset(path, fhirBody(GET(path + "?" + query), HttpStatus.OK));
  }

  /**
   * Parses a search response body and checks that it is a {@code searchset} Bundle with a {@code
   * total} whose entries hold resources of the searched type, each with the {@code fullUrl} {@code
   * .../api/fhir/{Type}/{id}} and the search mode {@code match}.
   *
   * @param path the search path, for example {@code /api/fhir/Encounter}
   * @param body the response body
   */
  private static Bundle searchset(String path, String body) {
    Bundle bundle = strictParse(body, Bundle.class);
    String type = path.substring(path.lastIndexOf('/') + 1);
    assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType(), body);
    assertTrue(bundle.hasTotal(), body);
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      Resource resource = entry.getResource();
      assertEquals(type, resource.fhirType(), body);
      assertTrue(entry.getFullUrl().endsWith(path + "/" + resource.getIdPart()), entry::getFullUrl);
      assertEquals(Bundle.SearchEntryMode.MATCH, entry.getSearch().getMode(), body);
    }
    return bundle;
  }

  /** Returns the logical ids of the Bundle's entries in entry order. */
  private static List<String> entryIds(Bundle bundle) {
    return bundle.getEntry().stream().map(entry -> entry.getResource().getIdPart()).toList();
  }

  /** Asserts a {@code 200} FHIR JSON response and parses its body strictly as {@code type}. */
  private static <T extends IBaseResource> T parse(HttpResponse response, Class<T> type) {
    return strictParse(fhirBody(response, HttpStatus.OK), type);
  }

  /** Parses FHIR JSON strictly: unknown elements and invalid values fail the parse. */
  private static <T extends IBaseResource> T strictParse(String body, Class<T> type) {
    return FhirContext.forR4Cached()
        .newJsonParser()
        .setParserErrorHandler(new StrictErrorHandler())
        .parseResource(type, body);
  }

  /**
   * Asserts the response status and the content type {@value
   * FhirResourceSerializer#FHIR_JSON_CONTENT_TYPE}, and returns the raw body.
   */
  private static String fhirBody(HttpResponse response, HttpStatus expected) {
    assertEquals(
        expected,
        response.status(),
        () -> response.hasBody() ? response.contentUnchecked().toJson() : "no body");
    String contentType = response.getContentType();
    assertNotNull(contentType, "content type");
    assertEquals(
        FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE, MediaType.parseMediaType(contentType));
    return response.content(contentType);
  }

  /**
   * Asserts an error response: the status, the FHIR JSON content type and an {@code
   * OperationOutcome} with exactly one issue of severity {@code error} and the given code whose
   * diagnostics, when {@code parameter} is given, name that parameter in quotes.
   *
   * @return the raw response body
   */
  private static String assertOutcome(
      HttpResponse response, HttpStatus status, IssueType code, @CheckForNull String parameter) {
    String body = fhirBody(response, status);
    OperationOutcome outcome = strictParse(body, OperationOutcome.class);
    assertEquals(1, outcome.getIssue().size(), body);
    OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
    assertEquals(OperationOutcome.IssueSeverity.ERROR, issue.getSeverity(), body);
    assertEquals(code, issue.getCode(), body);
    if (parameter != null) {
      assertTrue(issue.getDiagnostics().contains("'" + parameter + "'"), body);
    }
    return body;
  }

  /**
   * Sends {@code GET url} and asserts {@code 400 invalid} whose diagnostics name {@code parameter}
   * and none of the other parameters of the query.
   */
  private void assertInvalid(String url, String parameter) {
    String body = assertOutcome(GET(url), HttpStatus.BAD_REQUEST, IssueType.INVALID, parameter);
    String diagnostics =
        strictParse(body, OperationOutcome.class).getIssueFirstRep().getDiagnostics();
    int queryStart = url.indexOf('?');
    if (queryStart < 0) {
      return;
    }
    for (String pair : url.substring(queryStart + 1).split("&")) {
      String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
      if (!name.equals(parameter)) {
        assertFalse(diagnostics.contains("'" + name + "'"), () -> url + ": " + diagnostics);
      }
    }
  }

  /** Asserts {@code 404 not-found} with the fixed diagnostics and returns the raw body. */
  private static String assertNotFound(HttpResponse response) {
    String body = assertOutcome(response, HttpStatus.NOT_FOUND, IssueType.NOTFOUND, null);
    assertEquals(
        FhirApiException.notFound().getDiagnostics(),
        strictParse(body, OperationOutcome.class).getIssueFirstRep().getDiagnostics());
    return body;
  }

  /** Asserts {@code 403 forbidden} with the fixed diagnostics and returns the raw body. */
  private static String assertForbidden(HttpResponse response) {
    String body = assertOutcome(response, HttpStatus.FORBIDDEN, IssueType.FORBIDDEN, null);
    assertEquals(
        FhirApiException.forbidden().getDiagnostics(),
        strictParse(body, OperationOutcome.class).getIssueFirstRep().getDiagnostics());
    return body;
  }

  private static void assertCoding(String system, String code, Coding coding) {
    assertEquals(system, coding.getSystem());
    assertEquals(code, coding.getCode());
  }

  /** Returns the fixture timestamp as the instant it was imported as, to the second. */
  private static Instant occurredAt(String fixtureTimestamp) {
    return DateUtils.parseDate(fixtureTimestamp).toInstant().truncatedTo(ChronoUnit.SECONDS);
  }

  /** Returns a well-formed per-data-element logical id of three fresh UIDs. */
  private static String unknownPerDataElementId() {
    return CodeGenerator.generateUid()
        + "-"
        + CodeGenerator.generateUid()
        + "-"
        + CodeGenerator.generateUid();
  }

  /**
   * Applies every restriction as the import user, runs {@code assertions} as the restricted user,
   * then restores each object's previous public access as the import user, also when {@code
   * assertions} fail.
   */
  private void asRestrictedUser(List<Restriction> restrictions, Runnable assertions) {
    Map<Restriction, String> previous = new LinkedHashMap<>();
    try {
      for (Restriction restriction : restrictions) {
        previous.put(
            restriction,
            setPublicSharing(restriction.type(), restriction.uid(), restriction.access()));
      }
      switchContextToUser(restrictedUser());
      assertions.run();
    } finally {
      switchContextToUser(importUser);
      previous.forEach(
          (restriction, access) -> setPublicSharing(restriction.type(), restriction.uid(), access));
    }
  }

  /**
   * Sets the public access of a metadata object through {@code /api/sharing}, keeping its user and
   * user group accesses, then flushes and clears the test's persistence context.
   *
   * @param type the sharing type, for example {@code program}
   * @param uid the object UID
   * @param access the new public access string
   * @return the public access the object had before
   */
  private String setPublicSharing(String type, String uid, String access) {
    String url = "/sharing?type=" + type + "&id=" + uid;
    JsonObject object = GET(url).content(HttpStatus.OK).getObject("object");
    String previous = object.getString("publicAccess").string();
    String body =
        "{\"object\":{\"publicAccess\":\""
            + access
            + "\",\"userAccesses\":"
            + arrayJson(object.getArray("userAccesses"))
            + ",\"userGroupAccesses\":"
            + arrayJson(object.getArray("userGroupAccesses"))
            + "}}";
    assertStatus(HttpStatus.OK, POST(url, body));
    manager.flush();
    manager.clear();
    return previous;
  }

  private static String arrayJson(JsonArray array) {
    return array.exists() ? array.toJson() : "[]";
  }

  /** Sets the public access of a metadata object through the object manager, bypassing ACL. */
  private void grantPublicAccess(
      Class<? extends IdentifiableObject> type, String uid, String access) {
    doInTransaction(
        () -> {
          IdentifiableObject object = manager.getNoAcl(type, uid);
          assertNotNull(object, uid);
          object.getSharing().setPublicAccess(access);
          manager.updateNoAcl(object);
        });
  }

  /**
   * Creates the non-superuser of the access tests: no authority, no user group, and capture and
   * search org unit {@value #ORG_UNIT}.
   *
   * @return the user's UID
   */
  private String createRestrictedUser() {
    User user = createUserWithAuth(RESTRICTED_USERNAME);
    OrganisationUnit orgUnit = manager.get(OrganisationUnit.class, ORG_UNIT);
    assertNotNull(orgUnit, ORG_UNIT);
    user.addOrganisationUnit(orgUnit);
    user.setTeiSearchOrganisationUnits(new HashSet<>(Set.of(orgUnit)));
    userService.updateUser(user);
    return user.getUid();
  }

  /** Returns the restricted user as currently stored. */
  private User restrictedUser() {
    return userService.getUser(restrictedUserUid);
  }

  private void deleteAllMappings() {
    doInTransaction(() -> manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete));
  }

  /**
   * The public access an access test gives one shareable metadata object.
   *
   * @param type the sharing type, for example {@code program}
   * @param uid the object UID
   * @param access the public access string applied for the test
   */
  private record Restriction(String type, String uid, String access) {}
}
