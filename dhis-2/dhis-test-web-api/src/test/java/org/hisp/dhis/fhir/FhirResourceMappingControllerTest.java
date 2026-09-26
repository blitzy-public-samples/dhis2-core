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

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;
import static org.hisp.dhis.feedback.ErrorCode.E4000;
import static org.hisp.dhis.feedback.ErrorCode.E4010;
import static org.hisp.dhis.feedback.ErrorCode.E4014;
import static org.hisp.dhis.feedback.ErrorCode.E4027;
import static org.hisp.dhis.feedback.ErrorCode.E5002;
import static org.hisp.dhis.feedback.ErrorCode.E5003;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.ENCOUNTER;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.IMMUNIZATION;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.OBSERVATION;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.PATIENT;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.ATTRIBUTE;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.CONSTANT;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.ENCOUNTER_CLASS;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.ENCOUNTER_TYPE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_ADMINISTERED;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_LOT_NUMBER;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_VACCINE_CODE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.OBSERVATION_VALUE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_ADDRESS_TEXT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_BIRTH_DATE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_FAMILY_NAME;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_GENDER;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_GIVEN_NAME;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_IDENTIFIER;
import static org.hisp.dhis.http.HttpAssertions.assertStatus;
import static org.hisp.dhis.http.HttpClientAdapter.Accept;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.feedback.ErrorCode;
import org.hisp.dhis.feedback.ErrorMessage;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingSchemaDescriptor;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingStore;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.mapping.FhirSourceType;
import org.hisp.dhis.fhir.mapping.FhirTargetField;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.JsonMixed;
import org.hisp.dhis.jsontree.JsonObject;
import org.hisp.dhis.test.config.H2DhisConfigurationProvider;
import org.hisp.dhis.test.webapi.H2ControllerIntegrationTestBase;
import org.hisp.dhis.test.webapi.json.domain.JsonErrorReport;
import org.hisp.dhis.test.webapi.json.domain.JsonImportSummary;
import org.hisp.dhis.test.webapi.json.domain.JsonWebMessage;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests the FHIR resource mapping metadata API at {@code /api/fhirResourceMappings} on H2, over the
 * tracker base metadata the mappings reference: a valid mapping can be created, read, replaced,
 * patched and deleted; every mapping rule violation is rejected with a {@code 409} conflict whose
 * message lists the violated rules, also when the request carries the metadata-import options
 * {@code skipValidation=true} or {@code atomicMode=NONE}; a validating metadata import reports the
 * violations as error reports; creating requires the mapping create authority; and the settings
 * page is served as uncached HTML. Every test runs in a transaction that is rolled back afterwards.
 */
@Transactional
@ContextConfiguration(classes = FhirResourceMappingControllerTest.FhirApiEnabledConfig.class)
class FhirResourceMappingControllerTest extends H2ControllerIntegrationTestBase {

  /** Supplies the H2 test configuration with {@code fhir.api.enabled} set to {@code true}. */
  public static class FhirApiEnabledConfig {
    @Bean
    public DhisConfigurationProvider dhisConfigurationProvider() {
      Properties properties = new Properties();
      properties.put(ConfigurationKey.FHIR_API_ENABLED.getKey(), "true");

      H2DhisConfigurationProvider provider = new H2DhisConfigurationProvider();
      provider.addProperties(properties);
      return provider;
    }
  }

  private static final String ENDPOINT = FhirResourceMappingSchemaDescriptor.API_ENDPOINT;
  private static final String PERSON = "ja8NY4PW7Xm";
  private static final String TYPE_WITHOUT_PROGRAM = "Ip8NY4PW7Xm";
  private static final String PROGRAM = "BFcipDERJnf";
  private static final String STAGE = "NpsdDv6kKSO";
  private static final String EVENT_PROGRAM = "BFcipDERJne";
  private static final String EVENT_PROGRAM_STAGE = "NpsdDv6kKSe";
  private static final String OTHER_PROGRAM_STAGE = "SKNvpoLioON";
  private static final String INTEGER_ATTRIBUTE = "integerAttr";
  private static final String FAMILY_ATTRIBUTE = "toUpdate000";
  private static final String GIVEN_ATTRIBUTE = "dIVt4l5vIOa";
  private static final String NUMBER_ATTRIBUTE = "V66aa7a2122";
  private static final String PROGRAM_ONLY_ATTRIBUTE = "fRGt4l6yIRb";
  private static final String INTEGER_ELEMENT = "DATAEL00006";
  private static final String NUMBER_ELEMENT = "GieVkTxp4HH";
  private static final String OTHER_STAGE_ELEMENT = "FieVkTxp4HE";
  private static final String IDENTIFIER_SYSTEM = "urn:dhis2:fhir-test:integer-attr";
  private static final String CODING_SYSTEM = "urn:dhis2:fhir-test:coding";
  private static final String NOT_A_UID = "not-a-uid";
  private static final String INVALID_ID = "FhirMapBad1";
  private static final String INVALID_NAME = "FHIR invalid mapping";
  private static final String STORED_ID = "FhirMapPat1";
  private static final String STORED_NAME = "FHIR stored Patient";

  private static final List<String> PATIENT_ENTRIES =
      List.of(
          identifier(INTEGER_ATTRIBUTE, IDENTIFIER_SYSTEM),
          attribute(PATIENT_FAMILY_NAME, FAMILY_ATTRIBUTE),
          attribute(PATIENT_GIVEN_NAME, GIVEN_ATTRIBUTE));

  private static final List<String> ENCOUNTER_ENTRIES =
      List.of(constant(ENCOUNTER_CLASS, "AMB"), dataElement(ENCOUNTER_TYPE, "DATAEL00005"));

  private static final List<String> IMMUNIZATION_ENTRIES =
      List.of(
          dataElement(IMMUNIZATION_ADMINISTERED, "DATAEL00001"),
          dataElement(IMMUNIZATION_LOT_NUMBER, "DATAEL00002"),
          constant(IMMUNIZATION_VACCINE_CODE, "08"));

  private static final List<String> OBSERVATION_ENTRIES =
      List.of(observation(INTEGER_ELEMENT, "integer"), observation(NUMBER_ELEMENT, "number"));

  @Autowired private TestSetup testSetup;

  @Autowired private FhirResourceMappingStore store;

  @BeforeEach
  void importTrackerMetadata() throws IOException {
    testSetup.importMetadata();
    store.getAllNoAcl().forEach(store::delete);
    manager.flush();
    manager.clear();
  }

  @Test
  void createReadUpdateDeleteMapping() {
    List<String> entries = PATIENT_ENTRIES.subList(0, 2);
    String uid =
        assertStatus(HttpStatus.CREATED, POST(ENDPOINT, patientMapping(STORED_NAME, entries)));

    JsonObject created = GET(ENDPOINT + "/" + uid).content(HttpStatus.OK);
    assertEquals(uid, created.getString("id").string());
    assertEquals(STORED_NAME, created.getString("name").string());
    assertEquals(PATIENT.name(), created.getString("resourceType").string());
    assertEquals(PERSON, created.getString("trackedEntityType.id").string());
    assertEquals(summaries(entries), summaries(created));

    String replacedName = "FHIR replaced Patient";
    assertStatus(
        HttpStatus.OK, PUT(ENDPOINT + "/" + uid, patientMapping(replacedName, PATIENT_ENTRIES)));
    JsonObject replaced = GET(ENDPOINT + "/" + uid).content(HttpStatus.OK);
    assertEquals(replacedName, replaced.getString("name").string());
    assertEquals(summaries(PATIENT_ENTRIES), summaries(replaced));

    String patch = "[{\"op\": \"replace\", \"path\": \"/name\", \"value\": \"FHIR patched\"}]";
    assertStatus(HttpStatus.OK, PATCH(ENDPOINT + "/" + uid, patch));
    JsonObject patched = GET(ENDPOINT + "/" + uid).content(HttpStatus.OK);
    assertEquals("FHIR patched", patched.getString("name").string());
    assertEquals(summaries(PATIENT_ENTRIES), summaries(patched));

    assertStatus(HttpStatus.OK, DELETE(ENDPOINT + "/" + uid));
    assertStatus(HttpStatus.NOT_FOUND, GET(ENDPOINT + "/" + uid));
    assertEquals(0, mappingCount());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidMappings")
  void invalidMappingsAreRejectedWithConflictMessage(InvalidMapping invalid) {
    if (invalid.withStoredPatient()) {
      assertStatus(HttpStatus.CREATED, POST(ENDPOINT, storedPatientMapping()));
    }
    int stored = mappingCount();

    assertConflict(POST(ENDPOINT, invalid.body()), invalid.messages());

    assertEquals(stored, mappingCount());
    assertStatus(HttpStatus.NOT_FOUND, GET(ENDPOINT + "/" + INVALID_ID));
  }

  @Test
  void metadataImportReportsValidatorErrorReports() {
    List<String> mappings =
        List.of(
            patientMapping("Patient without target", with(PATIENT_ENTRIES, entry(null, ATTRIBUTE))),
            patientMapping("second Patient", PATIENT_ENTRIES),
            encounterMapping(
                "Encounter with attribute type",
                List.of(ENCOUNTER_ENTRIES.get(0), attribute(ENCOUNTER_TYPE, FAMILY_ATTRIBUTE))),
            observationMapping("Observation of no UID", List.of(observation(NOT_A_UID, "x"))),
            immunizationMapping(
                "Immunization administered by an integer",
                replace(
                    IMMUNIZATION_ENTRIES,
                    0,
                    dataElement(IMMUNIZATION_ADMINISTERED, INTEGER_ELEMENT))),
            mapping(
                "Encounter of a stage of another program",
                ENCOUNTER,
                PERSON,
                PROGRAM,
                OTHER_PROGRAM_STAGE,
                ENCOUNTER_ENTRIES.subList(0, 1)));
    List<String> withIds = new ArrayList<>();
    for (int i = 0; i < mappings.size(); i++) {
      withIds.add(withId("FhirImport" + i, mappings.get(i)));
    }
    String bundle =
        "{\"%s\": [%s]}"
            .formatted(FhirResourceMappingSchemaDescriptor.PLURAL, String.join(", ", withIds));

    JsonWebMessage message =
        POST("/metadata", bundle).content(HttpStatus.CONFLICT).as(JsonWebMessage.class);

    assertEquals(409, message.getHttpStatusCode());
    JsonImportSummary report = message.getResponse().as(JsonImportSummary.class);
    assertEquals("ERROR", report.getStatus());
    List<JsonErrorReport> errors =
        report.getTypeReports().stream()
            .flatMap(typeReport -> typeReport.getObjectReports().stream())
            .flatMap(objectReport -> objectReport.getErrorReports().stream())
            .toList();
    Set<ErrorCode> codes = errors.stream().map(JsonErrorReport::getErrorCode).collect(toSet());
    assertTrue(
        codes.containsAll(EnumSet.of(E4000, E4010, E4014, E4027, E5002, E5003)),
        () -> "error codes " + codes);
    Set<String> messages = errors.stream().map(JsonErrorReport::getMessage).collect(toSet());
    List<String> expected =
        List.of(
            errorMessage(E4000, "target"),
            errorMessage(E5003, "resourceType", PATIENT.name(), "FhirImport0", "FhirImport1"),
            errorMessage(E4010, ATTRIBUTE.name(), ENCOUNTER_TYPE.name()),
            errorMessage(E4014, NOT_A_UID, "source"),
            errorMessage(E4027, "INTEGER", IMMUNIZATION_ADMINISTERED.name()),
            errorMessage(E5002, OTHER_PROGRAM_STAGE, "FhirImport5", "programStage"));
    assertTrue(messages.containsAll(expected), () -> "error messages " + messages);
    assertEquals(0, mappingCount());
  }

  @Test
  void validationBypassOptionsCannotPersistInvalidMapping() {
    String uid = assertStatus(HttpStatus.CREATED, POST(ENDPOINT, storedPatientMapping()));
    String invalid =
        patientMapping(
            "FHIR bypassing Patient",
            replace(PATIENT_ENTRIES, 0, attribute(PATIENT_IDENTIFIER, INTEGER_ATTRIBUTE)));
    String patch = "[{\"op\": \"remove\", \"path\": \"/fieldMappings/0/system\"}]";
    List<String> expected = List.of(errorMessage(E4000, "system"));

    for (String option : List.of("skipValidation=true", "atomicMode=NONE")) {
      assertConflict(POST(ENDPOINT + "?" + option, invalid), expected);
      assertEquals(1, mappingCount(), option);

      assertConflict(PUT(ENDPOINT + "/" + uid + "?" + option, invalid), expected);
      assertStoredPatientUnchanged(uid);

      assertConflict(PATCH(ENDPOINT + "/" + uid + "?" + option, patch), expected);
      assertStoredPatientUnchanged(uid);
    }
  }

  @Test
  void mappingWriteRequiresAuthority() {
    switchToNewUser("fhir-noauth");
    JsonWebMessage denied =
        POST(ENDPOINT, storedPatientMapping())
            .content(HttpStatus.FORBIDDEN)
            .as(JsonWebMessage.class);
    assertEquals("ERROR", denied.getStatus());
    assertEquals(403, denied.getHttpStatusCode());

    switchToNewUser("fhir-admin", "F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD");
    assertStatus(HttpStatus.CREATED, POST(ENDPOINT, storedPatientMapping()));

    switchToAdminUser();
    assertEquals(1, mappingCount());
    assertStoredPatientUnchanged(STORED_ID);
  }

  @Test
  void settingsPageIsServedAsHtml() {
    HttpResponse response = GET(ENDPOINT + "/settings", Accept(MediaType.TEXT_HTML_VALUE));

    assertEquals(HttpStatus.OK, response.status());
    assertTrue(response.content(MediaType.TEXT_HTML_VALUE).contains("id=\"fhir-mapping-form\""));
    String cacheControl = response.header("Cache-Control");
    assertNotNull(cacheControl);
    assertTrue(cacheControl.contains("no-store"), cacheControl);
  }

  /** One mapping per validator rule, each violating that rule, with the messages it must yield. */
  private static Stream<InvalidMapping> invalidMappings() {
    return Stream.of(
        invalid(
            "entry without target",
            patientMapping(INVALID_NAME, with(PATIENT_ENTRIES, entry(null, ATTRIBUTE))),
            errorMessage(E4000, "target")),
        invalid(
            "entry without sourceType",
            patientMapping(INVALID_NAME, with(PATIENT_ENTRIES, entry(PATIENT_ADDRESS_TEXT, null))),
            errorMessage(E4000, "sourceType")),
        invalid(
            "event-derived mapping without program and programStage",
            mapping(INVALID_NAME, ENCOUNTER, PERSON, null, null, ENCOUNTER_ENTRIES),
            errorMessage(E4000, "program"),
            errorMessage(E4000, "programStage")),
        invalid(
            "Encounter without ENCOUNTER_CLASS",
            encounterMapping(INVALID_NAME, ENCOUNTER_ENTRIES.subList(1, 2)),
            errorMessage(E4000, ENCOUNTER_CLASS.name())),
        invalid(
            "Immunization without IMMUNIZATION_VACCINE_CODE",
            immunizationMapping(INVALID_NAME, IMMUNIZATION_ENTRIES.subList(0, 2)),
            errorMessage(E4000, IMMUNIZATION_VACCINE_CODE.name())),
        invalid(
            "Immunization without IMMUNIZATION_ADMINISTERED",
            immunizationMapping(INVALID_NAME, IMMUNIZATION_ENTRIES.subList(1, 3)),
            errorMessage(E4000, IMMUNIZATION_ADMINISTERED.name())),
        invalid(
            "Observation without OBSERVATION_VALUE",
            observationMapping(INVALID_NAME, List.of()),
            errorMessage(E4000, OBSERVATION_VALUE.name())),
        invalid(
            "PATIENT_IDENTIFIER without system",
            patient(0, attribute(PATIENT_IDENTIFIER, INTEGER_ATTRIBUTE)),
            errorMessage(E4000, "system")),
        invalid(
            "CONSTANT entry without code",
            encounterMapping(
                INVALID_NAME,
                replace(
                    ENCOUNTER_ENTRIES, 0, entry(ENCOUNTER_CLASS, CONSTANT, null, "urn:x", null))),
            errorMessage(E4000, "code")),
        invalid(
            "OBSERVATION_VALUE without code",
            observationMapping(
                INVALID_NAME,
                replace(OBSERVATION_ENTRIES, 0, dataElement(OBSERVATION_VALUE, INTEGER_ELEMENT))),
            errorMessage(E4000, "code")),
        invalid(
            "attribute entry without source",
            patient(1, entry(PATIENT_FAMILY_NAME, ATTRIBUTE)),
            errorMessage(E4000, "source")),
        invalid(
            "target of another resource type",
            patientMapping(INVALID_NAME, with(PATIENT_ENTRIES, observation(INTEGER_ELEMENT, "x"))),
            errorMessage(E4010, OBSERVATION_VALUE.name(), PATIENT.name())),
        invalid(
            "source type the target does not allow",
            patient(1, constant(PATIENT_FAMILY_NAME, "family")),
            errorMessage(E4010, CONSTANT.name(), PATIENT_FAMILY_NAME.name())),
        invalid(
            "source that is not a UID",
            patient(1, attribute(PATIENT_FAMILY_NAME, NOT_A_UID)),
            errorMessage(E4014, NOT_A_UID, "source")),
        invalid(
            "PATIENT_GENDER value map value that is no administrative gender",
            patient(2, gender(GIVEN_ATTRIBUTE, "M", "man")),
            errorMessage(E4027, "man", "valueMap")),
        invalid(
            "PATIENT_BIRTH_DATE fed a TEXT attribute",
            patient(2, attribute(PATIENT_BIRTH_DATE, GIVEN_ATTRIBUTE)),
            errorMessage(E4027, "TEXT", PATIENT_BIRTH_DATE.name())),
        invalid(
            "program without registration",
            mapping(
                INVALID_NAME,
                ENCOUNTER,
                PERSON,
                EVENT_PROGRAM,
                EVENT_PROGRAM_STAGE,
                ENCOUNTER_ENTRIES.subList(0, 1)),
            errorMessage(E5002, EVENT_PROGRAM, INVALID_ID, "program")),
        invalid(
            "program of another tracked entity type",
            mapping(
                INVALID_NAME, ENCOUNTER, TYPE_WITHOUT_PROGRAM, PROGRAM, STAGE, ENCOUNTER_ENTRIES),
            errorMessage(E5002, PROGRAM, INVALID_ID, "trackedEntityType")),
        invalid(
            "program stage of another program",
            mapping(
                INVALID_NAME,
                ENCOUNTER,
                PERSON,
                PROGRAM,
                OTHER_PROGRAM_STAGE,
                ENCOUNTER_ENTRIES.subList(0, 1)),
            errorMessage(E5002, OTHER_PROGRAM_STAGE, INVALID_ID, "programStage")),
        invalid(
            "program stage on a Patient mapping",
            mapping(INVALID_NAME, PATIENT, PERSON, null, STAGE, PATIENT_ENTRIES),
            errorMessage(E5002, STAGE, INVALID_ID, "programStage")),
        invalid(
            "attribute not on the tracked entity type",
            patient(1, attribute(PATIENT_FAMILY_NAME, PROGRAM_ONLY_ATTRIBUTE)),
            errorMessage(E5002, PROGRAM_ONLY_ATTRIBUTE, INVALID_ID, PATIENT_FAMILY_NAME.name())),
        invalid(
            "data element not in the program stage",
            observationMapping(
                INVALID_NAME, with(OBSERVATION_ENTRIES, observation(OTHER_STAGE_ELEMENT, "other"))),
            errorMessage(E5002, OTHER_STAGE_ELEMENT, INVALID_ID, OBSERVATION_VALUE.name())),
        new InvalidMapping(
            "second Patient mapping",
            withId(INVALID_ID, patientMapping(INVALID_NAME, PATIENT_ENTRIES)),
            true,
            List.of(errorMessage(E5003, "resourceType", PATIENT.name(), INVALID_ID, STORED_ID))),
        invalid(
            "repeated single-entry target",
            patient(2, attribute(PATIENT_FAMILY_NAME, GIVEN_ATTRIBUTE)),
            errorMessage(E5003, "target", PATIENT_FAMILY_NAME.name(), INVALID_ID, INVALID_ID)),
        invalid(
            "repeated identifier system",
            patient(2, identifier(GIVEN_ATTRIBUTE, IDENTIFIER_SYSTEM)),
            errorMessage(E5003, "system", IDENTIFIER_SYSTEM, INVALID_ID, INVALID_ID)),
        invalid(
            "one attribute on two Patient targets",
            patient(2, attribute(PATIENT_GIVEN_NAME, FAMILY_ATTRIBUTE)),
            errorMessage(E5003, "source", FAMILY_ATTRIBUTE, INVALID_ID, INVALID_ID)),
        invalid(
            "one data element in two OBSERVATION_VALUE entries",
            observationMapping(
                INVALID_NAME, replace(OBSERVATION_ENTRIES, 1, observation(INTEGER_ELEMENT, "y"))),
            errorMessage(E5003, "source", INTEGER_ELEMENT, INVALID_ID, INVALID_ID)));
  }

  /**
   * A mapping body that violates at least one mapping rule, and the messages its rejection must
   * contain. When {@code withStoredPatient} is set, a valid Patient mapping is stored first.
   */
  private record InvalidMapping(
      String rule, String body, boolean withStoredPatient, List<String> messages) {
    @Override
    public String toString() {
      return rule;
    }
  }

  private static InvalidMapping invalid(String rule, String mapping, String... messages) {
    return new InvalidMapping(rule, withId(INVALID_ID, mapping), false, List.of(messages));
  }

  /** Returns the base Patient mapping with the entry at {@code index} replaced by {@code entry}. */
  private static String patient(int index, String entry) {
    return patientMapping(INVALID_NAME, replace(PATIENT_ENTRIES, index, entry));
  }

  /** Returns the message of an error report with the given code and arguments. */
  private static String errorMessage(ErrorCode code, Object... args) {
    return new ErrorMessage(code, args).getMessage();
  }

  /**
   * Asserts a {@code 409} conflict web message without error reports whose message contains every
   * expected text.
   */
  private static void assertConflict(HttpResponse response, List<String> expected) {
    JsonWebMessage conflict = response.content(HttpStatus.CONFLICT).as(JsonWebMessage.class);
    assertEquals("ERROR", conflict.getStatus());
    assertEquals(409, conflict.getHttpStatusCode());
    String actual = conflict.getMessage();
    assertNotNull(actual);
    for (String text : expected) {
      assertTrue(actual.contains(text), () -> "expected <" + text + "> in <" + actual + ">");
    }
    assertTrue(conflict.get("response.errorReports").isUndefined(), conflict::toJson);
  }

  /** Asserts that the stored Patient mapping still has its name and all its entries. */
  private void assertStoredPatientUnchanged(String uid) {
    JsonObject stored = GET(ENDPOINT + "/" + uid).content(HttpStatus.OK);
    assertEquals(STORED_NAME, stored.getString("name").string());
    assertEquals(summaries(PATIENT_ENTRIES), summaries(stored));
  }

  private int mappingCount() {
    return GET(ENDPOINT + "?fields=id&paging=false")
        .content(HttpStatus.OK)
        .getArray(FhirResourceMappingSchemaDescriptor.PLURAL)
        .size();
  }

  /** Returns one summary per entry of the given entry JSON objects. */
  private static List<String> summaries(List<String> entries) {
    return entries.stream().map(entry -> summary(JsonMixed.of(entry))).toList();
  }

  /** Returns one summary per field mapping entry of the given mapping. */
  private static List<String> summaries(JsonObject mapping) {
    return mapping.getList("fieldMappings", JsonObject.class).stream()
        .map(FhirResourceMappingControllerTest::summary)
        .toList();
  }

  private static String summary(JsonObject entry) {
    return Stream.of("target", "sourceType", "source", "system", "code", "valueMap")
        .map(property -> property + "=" + (entry.has(property) ? entry.get(property).toJson() : ""))
        .collect(joining(","));
  }

  private static String storedPatientMapping() {
    return withId(STORED_ID, patientMapping(STORED_NAME, PATIENT_ENTRIES));
  }

  private static String patientMapping(String name, List<String> entries) {
    return mapping(name, PATIENT, PERSON, null, null, entries);
  }

  private static String encounterMapping(String name, List<String> entries) {
    return mapping(name, ENCOUNTER, PERSON, PROGRAM, STAGE, entries);
  }

  private static String immunizationMapping(String name, List<String> entries) {
    return mapping(name, IMMUNIZATION, PERSON, PROGRAM, STAGE, entries);
  }

  private static String observationMapping(String name, List<String> entries) {
    return mapping(name, OBSERVATION, PERSON, PROGRAM, STAGE, entries);
  }

  /** Returns a mapping JSON object; a {@code null} program or program stage is left out. */
  private static String mapping(
      String name,
      FhirResourceType type,
      String trackedEntityType,
      String program,
      String stage,
      List<String> entries) {
    return "{\"name\": \"%s\", \"resourceType\": \"%s\"%s%s%s, \"fieldMappings\": [%s]}"
        .formatted(
            name,
            type.name(),
            reference("trackedEntityType", trackedEntityType),
            reference("program", program),
            reference("programStage", stage),
            String.join(", ", entries));
  }

  /** Returns an identifier reference property, or nothing when {@code uid} is {@code null}. */
  private static String reference(String property, String uid) {
    return uid == null ? "" : ", \"%s\": {\"id\": \"%s\"}".formatted(property, uid);
  }

  /** Returns the mapping JSON object with the given UID as its first property. */
  private static String withId(String id, String mapping) {
    return "{\"id\": \"" + id + "\", " + mapping.substring(1);
  }

  private static String attribute(FhirTargetField target, String source) {
    return entry(target, ATTRIBUTE, source, null, null);
  }

  private static String identifier(String source, String system) {
    return entry(PATIENT_IDENTIFIER, ATTRIBUTE, source, system, null);
  }

  private static String gender(String source, String value, String code) {
    String entry = attribute(PATIENT_GENDER, source);
    return entry.substring(0, entry.length() - 1)
        + ", \"valueMap\": {\"%s\": \"%s\"}}".formatted(value, code);
  }

  private static String dataElement(FhirTargetField target, String source) {
    return entry(target, DATA_ELEMENT, source, null, null);
  }

  private static String constant(FhirTargetField target, String code) {
    return entry(target, CONSTANT, null, CODING_SYSTEM, code);
  }

  private static String observation(String source, String code) {
    return entry(OBSERVATION_VALUE, DATA_ELEMENT, source, CODING_SYSTEM, code);
  }

  private static String entry(FhirTargetField target, FhirSourceType sourceType) {
    return entry(target, sourceType, null, null, null);
  }

  /** Returns a field mapping entry JSON object; {@code null} values are left out. */
  private static String entry(
      FhirTargetField target,
      FhirSourceType sourceType,
      String source,
      String system,
      String code) {
    String[] names = {"target", "sourceType", "source", "system", "code"};
    Object[] values = {target, sourceType, source, system, code};
    List<String> properties = new ArrayList<>();
    for (int i = 0; i < names.length; i++) {
      if (values[i] != null) {
        properties.add("\"%s\": \"%s\"".formatted(names[i], values[i]));
      }
    }
    return "{" + String.join(", ", properties) + "}";
  }

  private static List<String> with(List<String> entries, String entry) {
    List<String> extended = new ArrayList<>(entries);
    extended.add(entry);
    return extended;
  }

  private static List<String> replace(List<String> entries, int index, String entry) {
    List<String> replaced = new ArrayList<>(entries);
    replaced.set(index, entry);
    return replaced;
  }
}
