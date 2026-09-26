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
import static org.hisp.dhis.feedback.ErrorCode.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceMappingValidator.OTHER_MAPPING;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.hisp.dhis.http.HttpAssertions.assertStatus;
import static org.hisp.dhis.http.HttpClientAdapter.*;
import static org.junit.jupiter.api.Assertions.*;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.feedback.*;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.*;
import org.hisp.dhis.test.config.H2DhisConfigurationProvider;
import org.hisp.dhis.test.webapi.H2ControllerIntegrationTestBase;
import org.hisp.dhis.test.webapi.json.domain.JsonImportSummary;
import org.hisp.dhis.test.webapi.json.domain.JsonWebMessage;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests {@code /api/fhirResourceMappings} on H2: CRUD, metadata round trip, {@code 409} for every
 * rule violation with or without bypass options, import error reports, write authorities and the
 * settings page behind CSRF protection.
 */
@Transactional
@ContextConfiguration(classes = FhirResourceMappingControllerTest.FhirApiEnabledConfig.class)
class FhirResourceMappingControllerTest extends H2ControllerIntegrationTestBase {
  /** Supplies the H2 test configuration with {@code fhir.api.enabled} and CSRF protection on. */
  public static class FhirApiEnabledConfig {
    @Bean
    public DhisConfigurationProvider dhisConfigurationProvider() {
      H2DhisConfigurationProvider provider = new H2DhisConfigurationProvider();
      provider.getProperties().put(ConfigurationKey.FHIR_API_ENABLED.getKey(), "true");
      provider.getProperties().put(ConfigurationKey.CSRF_ENABLED.getKey(), "on");
      return provider;
    }
  }

  private static final String ENDPOINT = FhirResourceMappingSchemaDescriptor.API_ENDPOINT;
  private static final String MAPPINGS = FhirResourceMappingSchemaDescriptor.PLURAL;
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
  private static final String OBSERVATION_ID = "FhirMapObs1";
  private static final List<String> ENTRY_FIELDS =
      List.of("target", "sourceType", "source", "system", "code", "display", "unit", "valueMap");
  private static final ErrorMessage DUPLICATE =
      errorMessage(E5003, "resourceType", PATIENT.name(), INVALID_ID, OTHER_MAPPING);
  private static final List<String> MAPPING_FIELDS =
      List.of("name", "resourceType", "trackedEntityType.id", "program.id", "programStage.id");
  private static final String STATUS_PATH = "fhir/metadata";
  private static final String LIST_PATH =
      "fhirResourceMappings?fields=id,displayName,resourceType,trackedEntityType[displayName],program[displayName],programStage[displayName],fieldMappings&paging=false";
  private static final String EDIT_FIELDS =
      "?fields=id,name,code,resourceType,trackedEntityType[id],program[id],programStage[id],fieldMappings,sharing";
  private static final List<String> PAGE_CALLS =
      List.of(
          "'X-Requested-With': 'XMLHttpRequest'",
          "'XSRF-TOKEN='",
          "headers['X-XSRF-TOKEN'] = token",
          "api('GET', '" + STATUS_PATH + "'",
          "'trackedEntityTypes?fields=id,displayName,trackedEntityTypeAttributes[trackedEntityAttribute[id,displayName,valueType]]&paging=false'",
          "'programs?filter=programType:eq:WITH_REGISTRATION&filter=trackedEntityType.id:eq:'",
          "'&fields=id,displayName,programTrackedEntityAttributes[trackedEntityAttribute[id,displayName,valueType]],programStages[id,displayName,programStageDataElements[dataElement[id,displayName,valueType]]]&paging=false'",
          "'" + LIST_PATH + "'",
          "'" + EDIT_FIELDS + "'",
          "api('POST', '" + MAPPINGS + "'",
          "api('PUT', '" + MAPPINGS + "/'",
          "api('DELETE', '" + MAPPINGS + "/'");
  private static final Pattern SCRIPT = Pattern.compile("<script[^>]*>([\\s\\S]*?)</script>");
  private static final List<String> PATIENT_ENTRIES =
      List.of(
          identifier(INTEGER_ATTRIBUTE, IDENTIFIER_SYSTEM),
          attribute(PATIENT_FAMILY_NAME, FAMILY_ATTRIBUTE),
          attribute(PATIENT_GIVEN_NAME, GIVEN_ATTRIBUTE));
  private static final List<String> GENDER_ENTRIES =
      replace(PATIENT_ENTRIES, 2, gender(GIVEN_ATTRIBUTE, "F", "female", "M", "male"));
  private static final List<String> ENCOUNTER_ENTRIES =
      List.of(constant(ENCOUNTER_CLASS, "AMB"), dataElement(ENCOUNTER_TYPE, "DATAEL00005"));
  private static final List<String> CLASS_ONLY = ENCOUNTER_ENTRIES.subList(0, 1);
  private static final List<String> IMMUNIZATION_ENTRIES =
      List.of(
          dataElement(IMMUNIZATION_ADMINISTERED, "DATAEL00001"),
          dataElement(IMMUNIZATION_LOT_NUMBER, "DATAEL00002"),
          constant(IMMUNIZATION_VACCINE_CODE, "08"));
  private static final List<String> OBSERVATION_ENTRIES =
      List.of(
          observation(INTEGER_ELEMENT, "integer", "Integer value", "{count}"),
          observation(NUMBER_ELEMENT, "number", "Number value", "kg"));
  private static final String FULL_PATIENT =
      withId(STORED_ID, mapping(STORED_NAME, PATIENT, GENDER_ENTRIES));
  private static final String FULL_OBSERVATION =
      withId(OBSERVATION_ID, mapping("FHIR stored Observation", OBSERVATION, OBSERVATION_ENTRIES));

  @Autowired private TestSetup testSetup;
  @Autowired private FhirResourceMappingStore store;
  @Autowired private FilterChainProxy springSecurityFilterChain;

  @BeforeEach
  void importTrackerMetadata() throws IOException {
    testSetup.importMetadata();
    store.getAllNoAcl().forEach(store::delete);
    manager.flush();
    manager.clear();
  }

  @Test
  void createReadUpdateDeleteMapping() {
    String patient = mapping(STORED_NAME, PATIENT, GENDER_ENTRIES);
    String uid = assertStatus(HttpStatus.CREATED, POST(ENDPOINT, patient));
    assertStoredAsSent(withId(uid, patient));
    String replacedName = "FHIR replaced Patient";
    assertStatus(
        HttpStatus.OK, PUT(ENDPOINT + "/" + uid, mapping(replacedName, PATIENT, PATIENT_ENTRIES)));
    assertStored(uid, replacedName, PATIENT_ENTRIES);
    String patch = "[{\"op\": \"replace\", \"path\": \"/name\", \"value\": \"FHIR patched\"}]";
    assertStatus(HttpStatus.OK, PATCH(ENDPOINT + "/" + uid, patch));
    assertStored(uid, "FHIR patched", PATIENT_ENTRIES);
    assertStatus(HttpStatus.OK, DELETE(ENDPOINT + "/" + uid));
    assertStatus(HttpStatus.NOT_FOUND, GET(ENDPOINT + "/" + uid));
    assertEquals(0, mappingCount());
  }

  @Test
  void metadataExportAndImportRoundTripsMappings() {
    List<String> mappings = List.of(FULL_PATIENT, FULL_OBSERVATION);
    mappings.forEach(mapping -> assertStatus(HttpStatus.CREATED, POST(ENDPOINT, mapping)));
    JsonObject export = GET("/metadata?" + MAPPINGS + "=true").content(HttpStatus.OK);
    assertStatus(HttpStatus.OK, DELETE(ENDPOINT + "/" + STORED_ID));
    assertStatus(HttpStatus.OK, DELETE(ENDPOINT + "/" + OBSERVATION_ID));
    assertEquals(0, mappingCount());
    JsonWebMessage imported =
        POST("/metadata", export.toJson()).content(HttpStatus.OK).as(JsonWebMessage.class);
    JsonImportSummary report = imported.getResponse().as(JsonImportSummary.class);
    assertEquals("OK", report.getStatus());
    assertEquals(2, report.getStats().getCreated());
    mappings.forEach(this::assertStoredAsSent);
  }

  @ParameterizedTest(name = "[{index}] {0}")
  @MethodSource("invalidMappings")
  void invalidMappingsAreRejectedWithConflictMessage(InvalidMapping invalid) {
    if (invalid.withStoredPatient()) {
      assertStatus(HttpStatus.CREATED, POST(ENDPOINT, storedPatientMapping()));
    }
    int stored = mappingCount();
    assertConflict(POST(ENDPOINT, invalid.body()), invalid.errors());
    assertEquals(stored, mappingCount());
    assertStatus(HttpStatus.NOT_FOUND, GET(ENDPOINT + "/" + INVALID_ID));
  }

  @Test
  void metadataImportReportsValidatorErrorReports() {
    List<InvalidMapping> rows = invalidMappings().filter(row -> !row.withStoredPatient()).toList();
    String bundle =
        IntStream.range(0, rows.size())
            .mapToObj(i -> imported(i, rows.get(i).body()))
            .collect(joining(", ", "{\"" + MAPPINGS + "\": [", "]}"));
    JsonWebMessage message =
        POST("/metadata", bundle).content(HttpStatus.CONFLICT).as(JsonWebMessage.class);
    assertEquals(409, message.getHttpStatusCode());
    JsonImportSummary report = message.getResponse().as(JsonImportSummary.class);
    assertEquals("ERROR", report.getStatus());
    Set<String> reported =
        report.getTypeReports().stream()
            .flatMap(typeReport -> typeReport.getObjectReports().stream())
            .flatMap(objectReport -> objectReport.getErrorReports().stream())
            .map(error -> error.getErrorCode() + " " + error.getMessage())
            .collect(toSet());
    for (int i = 0; i < rows.size(); i++) {
      List<ErrorMessage> errors = new ArrayList<>(rows.get(i).errors());
      if (rows.get(i).body().contains("\"PATIENT\"")) errors.add(DUPLICATE);
      for (ErrorMessage error : errors) {
        String expected = imported(i, error.getErrorCode() + " " + error.getMessage());
        assertTrue(reported.contains(expected), () -> expected + " not in " + reported);
      }
    }
    assertEquals(0, mappingCount());
  }

  @Test
  void duplicateReportsDoNotIdentifyHiddenMapping() {
    String hidden =
        "{\"sharing\": {\"public\": \"--------\"}, " + storedPatientMapping().substring(1);
    assertStatus(HttpStatus.CREATED, POST(ENDPOINT, hidden));
    switchToNewUser("fhir-creator", "F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD");
    assertEquals(0, mappingCount());
    String duplicate = withId(INVALID_ID, mapping(INVALID_NAME, PATIENT, PATIENT_ENTRIES));
    String expected = DUPLICATE.getMessage();
    String bundle = "{\"%s\": [%s]}".formatted(MAPPINGS, duplicate);
    for (HttpResponse response : List.of(POST(ENDPOINT, duplicate), POST("/metadata", bundle))) {
      String json = response.content(HttpStatus.CONFLICT).toJson();
      assertTrue(json.contains(expected), json);
      assertFalse(json.contains(STORED_ID) || json.contains(STORED_NAME), json);
    }
    switchToAdminUser();
    assertEquals(1, mappingCount());
  }

  @Test
  void validationBypassOptionsCannotPersistInvalidMapping() {
    String uid = assertStatus(HttpStatus.CREATED, POST(ENDPOINT, storedPatientMapping()));
    String invalid = patient(0, attribute(PATIENT_IDENTIFIER, INTEGER_ATTRIBUTE));
    String patch = "[{\"op\": \"remove\", \"path\": \"/fieldMappings/0/system\"}]";
    List<ErrorMessage> expected = List.of(errorMessage(E4000, "system"));
    for (String option : List.of("skipValidation=true", "atomicMode=NONE")) {
      assertConflict(POST(ENDPOINT + "?" + option, invalid), expected);
      assertEquals(1, mappingCount(), option);
      assertConflict(PUT(ENDPOINT + "/" + uid + "?" + option, invalid), expected);
      assertStored(uid, STORED_NAME, PATIENT_ENTRIES);
      assertConflict(PATCH(ENDPOINT + "/" + uid + "?" + option, patch), expected);
      assertStored(uid, STORED_NAME, PATIENT_ENTRIES);
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
    assertStatus(HttpStatus.FORBIDDEN, DELETE(ENDPOINT + "/" + STORED_ID));
    switchToAdminUser();
    assertEquals(1, mappingCount());
    assertStored(STORED_ID, STORED_NAME, PATIENT_ENTRIES);
    switchToNewUser(
        "fhir-deleter", "F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD", "F_FHIR_RESOURCE_MAPPING_DELETE");
    assertStatus(HttpStatus.OK, DELETE(ENDPOINT + "/" + STORED_ID));
    switchToAdminUser();
    assertEquals(0, mappingCount());
  }

  @Test
  void settingsPageIsServedAsHtml() {
    HttpResponse response = GET(ENDPOINT + "/settings", Accept(MediaType.TEXT_HTML_VALUE));
    assertEquals(HttpStatus.OK, response.status());
    String html = response.content(MediaType.TEXT_HTML_VALUE);
    assertTrue(html.contains("id=\"fhir-mapping-form\""));
    String cacheControl = response.header("Cache-Control");
    assertTrue(cacheControl != null && cacheControl.contains("no-store"), cacheControl);
    List<String> scripts = SCRIPT.matcher(html).results().map(script -> script.group(1)).toList();
    assertEquals(1, scripts.size(), "inline scripts");
    PAGE_CALLS.forEach(call -> assertTrue(scripts.get(0).contains(call), call));
  }

  @Test
  void settingsPageRequestsSucceedThroughCsrfProtectedSecurityChain() {
    mvc = settingsPageChain(null);
    HttpResponse page = GET(ENDPOINT + "/settings", Accept(MediaType.TEXT_HTML_VALUE));
    assertEquals(HttpStatus.OK, page.status());
    String cookie = String.valueOf(page.header("Set-Cookie"));
    assertTrue(cookie.matches("XSRF-TOKEN=[^;]+(;.*)?"), cookie);
    String token = cookie.split("[=;]")[1];
    mvc = settingsPageChain(new Cookie("XSRF-TOKEN", token));
    assertStatus(HttpStatus.OK, GET(STATUS_PATH));
    String gender = gender(GIVEN_ATTRIBUTE, "__proto__", "male", " F ", "female");
    List<String> entries = with(PATIENT_ENTRIES.subList(0, 2), gender);
    String patient = mapping(STORED_NAME, PATIENT, entries);
    assertStatus(HttpStatus.FORBIDDEN, POST(ENDPOINT, patient));
    assertEquals(0, mappingCount());
    Header csrf = Header("X-XSRF-TOKEN", token);
    String uid = assertStatus(HttpStatus.CREATED, POST(ENDPOINT, Body(patient), csrf));
    JsonObject edited = GET(ENDPOINT + "/" + uid + EDIT_FIELDS).content(HttpStatus.OK);
    assertEquals(summaries(entries), summaries(edited));
    String renamed = mapping("FHIR renamed Patient", PATIENT, entries);
    assertStatus(HttpStatus.OK, PUT(ENDPOINT + "/" + uid, Body(renamed), csrf));
    assertTrue(GET(LIST_PATH).content(HttpStatus.OK).toJson().contains("FHIR renamed Patient"));
    assertStatus(HttpStatus.OK, DELETE(ENDPOINT + "/" + uid, csrf));
    assertStatus(HttpStatus.NOT_FOUND, GET(ENDPOINT + "/" + uid + EDIT_FIELDS));
  }

  /** Mappings that together violate every validator rule, with the messages each must yield. */
  private static Stream<InvalidMapping> invalidMappings() {
    return Stream.of(
        invalid(
            mapping(INVALID_NAME, PATIENT, with(PATIENT_ENTRIES, entry(null, null))),
            errorMessage(E4000, "target"),
            errorMessage(E4000, "sourceType")),
        invalid(
            mapping(INVALID_NAME, ENCOUNTER, PERSON, null, null, ENCOUNTER_ENTRIES),
            errorMessage(E4000, "program"),
            errorMessage(E4000, "programStage")),
        invalid(
            mapping(INVALID_NAME, ENCOUNTER, ENCOUNTER_ENTRIES.subList(1, 2)),
            errorMessage(E4000, ENCOUNTER_CLASS.name())),
        invalid(
            mapping(INVALID_NAME, IMMUNIZATION, IMMUNIZATION_ENTRIES.subList(1, 2)),
            errorMessage(E4000, IMMUNIZATION_ADMINISTERED.name()),
            errorMessage(E4000, IMMUNIZATION_VACCINE_CODE.name())),
        invalid(
            mapping(INVALID_NAME, OBSERVATION, List.of()),
            errorMessage(E4000, OBSERVATION_VALUE.name())),
        invalid(
            patient(0, attribute(PATIENT_IDENTIFIER, INTEGER_ATTRIBUTE)),
            errorMessage(E4000, "system")),
        invalid(
            mapping(INVALID_NAME, ENCOUNTER, List.of(constant(ENCOUNTER_CLASS, null))),
            errorMessage(E4000, "code")),
        invalid(
            mapping(INVALID_NAME, OBSERVATION, List.of(observation(INTEGER_ELEMENT, null))),
            errorMessage(E4000, "code")),
        invalid(patient(1, entry(PATIENT_FAMILY_NAME, ATTRIBUTE)), errorMessage(E4000, "source")),
        invalid(
            mapping(INVALID_NAME, ENCOUNTER, List.of(constant(ENCOUNTER_CLASS, "c".repeat(1025)))),
            errorMessage(E4001, "code", "1024", "1025")),
        invalid(
            mapping(
                INVALID_NAME, PATIENT, with(PATIENT_ENTRIES, observation(INTEGER_ELEMENT, "x"))),
            errorMessage(E4010, OBSERVATION_VALUE.name(), PATIENT.name())),
        invalid(
            patient(1, constant(PATIENT_FAMILY_NAME, "family")),
            errorMessage(E4010, CONSTANT.name(), PATIENT_FAMILY_NAME.name())),
        invalid(
            patient(1, attribute(PATIENT_FAMILY_NAME, NOT_A_UID)),
            errorMessage(E4014, NOT_A_UID, "source")),
        invalid(
            patient(2, gender(GIVEN_ATTRIBUTE, "M", "man")),
            errorMessage(E4027, "man", "valueMap")),
        invalid(
            patient(2, gender(GIVEN_ATTRIBUTE, "", "male")), errorMessage(E4027, "", "valueMap")),
        invalid(
            patient(2, gender(GIVEN_ATTRIBUTE, "A;B", "male")),
            errorMessage(E4027, "A;B", "valueMap")),
        invalid(
            patient(2, attribute(PATIENT_BIRTH_DATE, GIVEN_ATTRIBUTE)),
            errorMessage(E4027, "TEXT", PATIENT_BIRTH_DATE.name())),
        invalid(
            mapping(INVALID_NAME, OBSERVATION, List.of(observation(INTEGER_ELEMENT, "x "))),
            errorMessage(E4027, "x ", "code")),
        invalid(
            patient(0, identifier(INTEGER_ATTRIBUTE, "urn:bad uri")),
            errorMessage(E4027, "urn:bad uri", "system")),
        invalid(
            mapping(
                INVALID_NAME, ENCOUNTER, PERSON, EVENT_PROGRAM, EVENT_PROGRAM_STAGE, CLASS_ONLY),
            errorMessage(E5002, EVENT_PROGRAM, INVALID_ID, "program")),
        invalid(
            mapping(
                INVALID_NAME, ENCOUNTER, TYPE_WITHOUT_PROGRAM, PROGRAM, STAGE, ENCOUNTER_ENTRIES),
            errorMessage(E5002, PROGRAM, INVALID_ID, "trackedEntityType")),
        invalid(
            mapping(INVALID_NAME, ENCOUNTER, PERSON, PROGRAM, OTHER_PROGRAM_STAGE, CLASS_ONLY),
            errorMessage(E5002, OTHER_PROGRAM_STAGE, INVALID_ID, "programStage")),
        invalid(
            mapping(INVALID_NAME, PATIENT, PERSON, null, STAGE, PATIENT_ENTRIES),
            errorMessage(E5002, STAGE, INVALID_ID, "programStage")),
        invalid(
            patient(1, attribute(PATIENT_FAMILY_NAME, PROGRAM_ONLY_ATTRIBUTE)),
            errorMessage(E5002, PROGRAM_ONLY_ATTRIBUTE, INVALID_ID, PATIENT_FAMILY_NAME.name())),
        invalid(
            mapping(INVALID_NAME, OBSERVATION, List.of(observation(OTHER_STAGE_ELEMENT, "x"))),
            errorMessage(E5002, OTHER_STAGE_ELEMENT, INVALID_ID, OBSERVATION_VALUE.name())),
        new InvalidMapping(
            withId(INVALID_ID, mapping(INVALID_NAME, PATIENT, PATIENT_ENTRIES)),
            true,
            List.of(DUPLICATE)),
        invalid(
            patient(2, attribute(PATIENT_FAMILY_NAME, GIVEN_ATTRIBUTE)),
            errorMessage(E5003, "target", PATIENT_FAMILY_NAME.name(), INVALID_ID, INVALID_ID)),
        invalid(
            patient(2, identifier(GIVEN_ATTRIBUTE, IDENTIFIER_SYSTEM)),
            errorMessage(E5003, "system", IDENTIFIER_SYSTEM, INVALID_ID, INVALID_ID)),
        invalid(
            patient(2, attribute(PATIENT_GIVEN_NAME, FAMILY_ATTRIBUTE)),
            errorMessage(E5003, "source", FAMILY_ATTRIBUTE, INVALID_ID, INVALID_ID)),
        invalid(
            mapping(
                INVALID_NAME,
                OBSERVATION,
                replace(OBSERVATION_ENTRIES, 1, observation(INTEGER_ELEMENT, "y"))),
            errorMessage(E5003, "source", INTEGER_ELEMENT, INVALID_ID, INVALID_ID)),
        invalid(
            patient(2, gender(GIVEN_ATTRIBUTE, "M", "male", "m", "female")),
            errorMessage(E5003, "valueMap", "m", INVALID_ID, INVALID_ID)));
  }

  private record InvalidMapping(String body, boolean withStoredPatient, List<ErrorMessage> errors) {
    @Override
    public String toString() {
      return errors.stream().map(ErrorMessage::getMessage).collect(joining(", "));
    }
  }

  private static InvalidMapping invalid(String mapping, ErrorMessage... errors) {
    return new InvalidMapping(withId(INVALID_ID, mapping), false, List.of(errors));
  }

  /** Gives the text of row {@code index} its own import id and mapping name. */
  private static String imported(int index, String text) {
    return text.replace(INVALID_ID, "FhirImp%04d".formatted(index))
        .replace(INVALID_NAME, "FHIR import " + index);
  }

  /** Returns the base Patient mapping with the entry at {@code index} replaced by {@code entry}. */
  private static String patient(int index, String entry) {
    return mapping(INVALID_NAME, PATIENT, replace(PATIENT_ENTRIES, index, entry));
  }

  private static ErrorMessage errorMessage(ErrorCode code, Object... args) {
    return new ErrorMessage(code, args);
  }

  /** Asserts a {@code 409} web message containing every expected text and no error reports. */
  private static void assertConflict(HttpResponse response, List<ErrorMessage> expected) {
    JsonWebMessage conflict = response.content(HttpStatus.CONFLICT).as(JsonWebMessage.class);
    assertEquals("ERROR", conflict.getStatus());
    assertEquals(409, conflict.getHttpStatusCode());
    String actual = conflict.getMessage();
    assertNotNull(actual);
    for (ErrorMessage error : expected) {
      String text = error.getMessage();
      assertTrue(actual.contains(text), () -> "expected <" + text + "> in <" + actual + ">");
    }
    assertTrue(conflict.get("response.errorReports").isUndefined(), conflict::toJson);
  }

  private void assertStored(String uid, String name, List<String> entries) {
    JsonObject stored = GET(ENDPOINT + "/" + uid).content(HttpStatus.OK);
    assertEquals(name, stored.getString("name").string());
    assertEquals(summaries(entries), summaries(stored));
  }

  /** Asserts that the mapping stored under the body's UID has the body's properties and entries. */
  private void assertStoredAsSent(String body) {
    JsonObject sent = JsonMixed.of(body);
    JsonObject stored = GET(ENDPOINT + "/" + sent.getString("id").string()).content(HttpStatus.OK);
    assertEquals(summary(sent, MAPPING_FIELDS), summary(stored, MAPPING_FIELDS));
    assertEquals(summaries(sent), summaries(stored));
  }

  private int mappingCount() {
    return GET(ENDPOINT + "?fields=id&paging=false").content().getArray(MAPPINGS).size();
  }

  /** Routes requests through the security chain with the settings page script's headers. */
  private MockMvc settingsPageChain(Cookie xsrf) {
    MockHttpServletRequestBuilder page =
        MockMvcRequestBuilders.get("/").header("X-Requested-With", "XMLHttpRequest");
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity(springSecurityFilterChain))
        .defaultRequest(xsrf == null ? page : page.cookie(xsrf))
        .build();
  }

  private static List<String> summaries(List<String> entries) {
    return entries.stream().map(entry -> summary(JsonMixed.of(entry), ENTRY_FIELDS)).toList();
  }

  private static List<String> summaries(JsonObject mapping) {
    return mapping.getList("fieldMappings", JsonObject.class).stream()
        .map(entry -> summary(entry, ENTRY_FIELDS))
        .toList();
  }

  /** Returns each property as {@code name=minimized JSON}, empty when the object lacks it. */
  private static String summary(JsonObject object, List<String> properties) {
    return properties.stream()
        .map(p -> p + "=" + (object.get(p).exists() ? object.get(p).toMinimizedJson() : ""))
        .collect(joining(","));
  }

  private static String storedPatientMapping() {
    return withId(STORED_ID, mapping(STORED_NAME, PATIENT, PATIENT_ENTRIES));
  }

  /** Returns a mapping on PERSON, and on PROGRAM and STAGE unless the type is PATIENT. */
  private static String mapping(String name, FhirResourceType type, List<String> entries) {
    boolean patient = type == PATIENT;
    return mapping(name, type, PERSON, patient ? null : PROGRAM, patient ? null : STAGE, entries);
  }

  /** Returns a mapping JSON object; a {@code null} program or program stage is left out. */
  private static String mapping(
      String name,
      FhirResourceType type,
      String trackedEntityType,
      String program,
      String stage,
      List<String> entries) {
    String references =
        reference("trackedEntityType", trackedEntityType)
            + reference("program", program)
            + reference("programStage", stage);
    return "{\"name\": \"%s\", \"resourceType\": \"%s\"%s, \"fieldMappings\": [%s]}"
        .formatted(name, type.name(), references, String.join(", ", entries));
  }

  private static String reference(String property, String uid) {
    return uid == null ? "" : ", \"%s\": {\"id\": \"%s\"}".formatted(property, uid);
  }

  private static String withId(String id, String mapping) {
    return "{\"id\": \"" + id + "\", " + mapping.substring(1);
  }

  private static String attribute(FhirTargetField target, String source) {
    return entry(target, ATTRIBUTE, source);
  }

  private static String identifier(String source, String system) {
    return entry(PATIENT_IDENTIFIER, ATTRIBUTE, source, system);
  }

  /** Returns a PATIENT_GENDER entry whose value map maps each value to the code following it. */
  private static String gender(String source, String... pairs) {
    String entry = attribute(PATIENT_GENDER, source);
    return IntStream.range(0, pairs.length / 2)
        .mapToObj(i -> "\"%s\": \"%s\"".formatted(pairs[2 * i], pairs[2 * i + 1]))
        .collect(joining(", ", entry.substring(0, entry.length() - 1) + ", \"valueMap\": {", "}}"));
  }

  private static String dataElement(FhirTargetField target, String source) {
    return entry(target, DATA_ELEMENT, source);
  }

  private static String constant(FhirTargetField target, String code) {
    return entry(target, CONSTANT, null, CODING_SYSTEM, code);
  }

  private static String observation(String source, String code) {
    return entry(OBSERVATION_VALUE, DATA_ELEMENT, source, CODING_SYSTEM, code);
  }

  private static String observation(String source, String code, String display, String unit) {
    return entry(OBSERVATION_VALUE, DATA_ELEMENT, source, CODING_SYSTEM, code, display, unit);
  }

  /** Returns a field mapping entry JSON object of values in ENTRY_FIELDS order, without nulls. */
  private static String entry(Object... values) {
    return IntStream.range(0, values.length)
        .filter(i -> values[i] != null)
        .mapToObj(i -> "\"%s\": \"%s\"".formatted(ENTRY_FIELDS.get(i), values[i]))
        .collect(joining(", ", "{", "}"));
  }

  private static List<String> with(List<String> entries, String entry) {
    return Stream.concat(entries.stream(), Stream.of(entry)).toList();
  }

  private static List<String> replace(List<String> entries, int index, String entry) {
    return IntStream.range(0, entries.size())
        .mapToObj(i -> i == index ? entry : entries.get(i))
        .toList();
  }
}
