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

import static java.util.stream.Collectors.*;
import static org.hisp.dhis.feedback.ErrorCode.*;
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
import java.util.stream.*;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.feedback.*;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.*;
import org.hisp.dhis.test.config.H2DhisConfigurationProvider;
import org.hisp.dhis.test.webapi.H2ControllerIntegrationTestBase;
import org.hisp.dhis.test.webapi.json.domain.*;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests {@code /api/fhirResourceMappings} on H2: CRUD, metadata round trip, {@code 409} for
 * validator-rule cases and representative invalid mappings with bypass options, import reports,
 * write authorities and sharing, CSV exports and the settings page behind CSRF protection.
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
  private static final String PROGRAM = "BFcipDERJnf";
  private static final String STAGE = "NpsdDv6kKSO";
  private static final String EVENT_PROGRAM = "BFcipDERJne";
  private static final String OTHER_PROGRAM_STAGE = "SKNvpoLioON";
  private static final String INTEGER_ATTRIBUTE = "integerAttr";
  private static final String FAMILY_ATTRIBUTE = "toUpdate000";
  private static final String GIVEN_ATTRIBUTE = "dIVt4l5vIOa";
  private static final String PROGRAM_ONLY_ATTRIBUTE = "fRGt4l6yIRb";
  private static final String INTEGER_ELEMENT = "DATAEL00006";
  private static final String OTHER_STAGE_ELEMENT = "FieVkTxp4HE";
  private static final String IDENTIFIER_SYSTEM = "urn:dhis2:fhir-test:integer-attr";
  private static final String CODING_SYSTEM = "urn:dhis2:fhir-test:coding";
  private static final String NOT_A_UID = "not-a-uid";
  private static final String INVALID_ID = "FhirMapBad1";
  private static final String INVALID_NAME = "FHIR invalid mapping";
  private static final String STORED_ID = "FhirMapPat1";
  private static final String STORED_NAME = "FHIR stored Patient";
  private static final String STORED_PATH = ENDPOINT + "/" + STORED_ID;
  private static final List<String> ENTRY_FIELDS =
      List.of("target", "sourceType", "source", "system", "code", "display", "unit", "valueMap");
  private static final ErrorMessage DUPLICATE =
      error(E5003, "resourceType", PATIENT, INVALID_ID, FhirResourceMappingValidator.OTHER_MAPPING);
  private static final String RENAMED = "FHIR renamed Patient";
  private static final String RENAME_PATCH =
      "[{\"op\": \"replace\", \"path\": \"/name\", \"value\": \"" + RENAMED + "\"}]";
  private static final List<String> MAPPING_FIELDS =
      List.of(
          "name", "code", "resourceType", "trackedEntityType.id", "program.id", "programStage.id");
  private static final String STATUS_PATH = "fhir/metadata";
  private static final String LIST_PATH =
      "fhirResourceMappings?fields=id,displayName,resourceType,trackedEntityType[displayName],program[displayName],programStage[displayName],fieldMappings&paging=false";
  private static final String EDIT_FIELDS =
      "?fields=id,name,code,resourceType,trackedEntityType[id],program[id],programStage[id],fieldMappings,sharing";
  private static final String TYPES_PATH =
      "trackedEntityTypes?fields=id,displayName,trackedEntityTypeAttributes[trackedEntityAttribute[id,displayName,valueType]]&paging=false";
  private static final String PROGRAMS_FILTER =
      "programs?filter=programType:eq:WITH_REGISTRATION&filter=trackedEntityType.id:eq:";
  private static final String PROGRAMS_FIELDS =
      "&fields=id,displayName,programTrackedEntityAttributes[trackedEntityAttribute[id,displayName,valueType]],programStages[id,displayName,programStageDataElements[dataElement[id,displayName,valueType]]]&paging=false";
  private static final List<String> PAGE_CALLS =
      List.of(
          "'X-Requested-With': 'XMLHttpRequest'",
          "'XSRF-TOKEN='",
          "headers['X-XSRF-TOKEN'] = token",
          "api('GET', '" + STATUS_PATH + "'",
          "'" + TYPES_PATH + "'",
          "'" + PROGRAMS_FILTER + "'",
          "'" + PROGRAMS_FIELDS + "'",
          "'" + LIST_PATH + "'",
          "'" + EDIT_FIELDS + "'",
          "api('POST', '" + MAPPINGS + "'",
          "api('PUT', '" + MAPPINGS + "/'",
          "api('DELETE', '" + MAPPINGS + "/'");
  private static final Pattern SCRIPT = Pattern.compile("<script[^>]*>([\\s\\S]*?)</script>");
  private static final List<String> PATIENT_ENTRIES =
      List.of(
          entry(PATIENT_IDENTIFIER, ATTRIBUTE, INTEGER_ATTRIBUTE, IDENTIFIER_SYSTEM),
          attribute(PATIENT_FAMILY_NAME, FAMILY_ATTRIBUTE),
          attribute(PATIENT_GIVEN_NAME, GIVEN_ATTRIBUTE));
  private static final List<String> GENDER_ENTRIES =
      put(PATIENT_ENTRIES, 2, gender(GIVEN_ATTRIBUTE, "F", "female", "M", "male"));
  private static final List<String> ENCOUNTER_ENTRIES =
      List.of(constant(ENCOUNTER_CLASS, "AMB"), entry(ENCOUNTER_TYPE, DATA_ELEMENT, "DATAEL00005"));
  private static final List<String> CLASS_ONLY = ENCOUNTER_ENTRIES.subList(0, 1);
  private static final List<String> OBSERVATION_ENTRIES =
      List.of(
          observation(INTEGER_ELEMENT, "integer", "Integer value", "{count}"),
          observation("GieVkTxp4HH", "number", "Number value", "kg"));
  private static final String STORED_PATIENT =
      withId(STORED_ID, mapping(STORED_NAME, PATIENT, PATIENT_ENTRIES));
  private static final String FULL_PATIENT =
      withId(STORED_ID, mapping(STORED_NAME, PATIENT, GENDER_ENTRIES));
  private static final String FULL_OBSERVATION =
      withId("FhirMapObs1", mapping("FHIR stored Observation", OBSERVATION, OBSERVATION_ENTRIES));

  @Autowired private TestSetup testSetup;
  @Autowired private FilterChainProxy springSecurityFilterChain;

  @BeforeEach
  void importTrackerMetadata() throws IOException {
    testSetup.importMetadata();
    manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete);
    manager.flush();
    manager.clear();
  }

  @Test
  void createReadUpdateDeleteMapping() {
    String patient = mapping(STORED_NAME, PATIENT, GENDER_ENTRIES);
    String uid = assertStatus(HttpStatus.CREATED, POST(ENDPOINT, patient));
    assertStoredAsSent(withId(uid, patient));
    String replaced = withId(uid, mapping("FHIR replaced Patient", PATIENT, PATIENT_ENTRIES));
    assertStatus(HttpStatus.OK, PUT(ENDPOINT + "/" + uid, replaced));
    assertStoredAsSent(replaced);
    assertStatus(HttpStatus.OK, PATCH(ENDPOINT + "/" + uid, RENAME_PATCH));
    assertStoredAsSent(replaced.replace("FHIR replaced Patient", RENAMED));
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
    assertStatus(HttpStatus.OK, DELETE(ENDPOINT + "/FhirMapObs1"));
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
    assertConflict(POST(ENDPOINT, invalid.body()), invalid.errors());
    assertEquals(0, mappingCount());
  }

  @Test
  void metadataImportReportsValidatorErrorReports() {
    List<InvalidMapping> rows = invalidMappings().toList();
    String bodies =
        IntStream.range(0, rows.size())
            .mapToObj(i -> imported(i, rows.get(i).body()))
            .collect(joining(", "));
    JsonWebMessage message = JsonMixed.of(importConflict(bodies)).as(JsonWebMessage.class);
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
  void importReportsDoNotDiscloseHiddenMappingsOrReferences() {
    String duplicate = withId(INVALID_ID, bad(PATIENT, PATIENT_ENTRIES));
    String foreign =
        withId(INVALID_ID, patient(1, attribute(PATIENT_FAMILY_NAME, PROGRAM_ONLY_ATTRIBUTE)));
    var importer = switchToNewUser("fhir-importer");
    String denied = importConflict(duplicate);
    assertEquals(denied, importConflict(foreign));
    switchToAdminUser();
    assertStatus(HttpStatus.CREATED, POST(ENDPOINT, shared("--------")));
    switchToNewUser(importer);
    assertEquals(denied, importConflict(duplicate));
    assertTrue(denied.contains("\"E3000\""), denied);
    switchToNewUser("fhir-creator", "F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD");
    assertEquals(0, mappingCount());
    String crud = POST(ENDPOINT, duplicate).content(HttpStatus.CONFLICT).toJson();
    for (String json : List.of(crud, importConflict(duplicate))) {
      assertTrue(json.contains(DUPLICATE.getMessage()), json);
      assertFalse(json.contains(STORED_ID) || json.contains(STORED_NAME), json);
    }
    switchToAdminUser();
    assertEquals(1, mappingCount());
  }

  @Test
  void csvExportsPrefixFormulaCellsWithQuote() {
    String mapping = STORED_PATIENT.replace(STORED_NAME, "=1+1,-2").substring(1);
    assertStatus(HttpStatus.CREATED, POST(ENDPOINT, "{\"code\": \"\\uFEFF@A1\", " + mapping));
    List<String> rows = List.of("'\uFEFF@A1,\"'=1+1,-2\"");
    List<String> listed = csv(ENDPOINT + "?fields=code,name&skipHeader=true");
    assertEquals(rows, listed.stream().map(row -> row.replace("'?", "'\uFEFF")).toList());
    assertEquals(rows, csv(ENDPOINT + "/gist.csv?fields=code,name&headless=true"));
    assertEquals(
        List.of("name,code", "\"'=1+1,-2\"", "'\uFEFF@A1"),
        csv(STORED_PATH + "/gist.csv?fields=name,code"));
    assertEquals(List.of("name", "\"'=1+1,-2\""), csv(STORED_PATH + "/name/gist.csv"));
  }

  @Test
  void validationBypassOptionsCannotPersistInvalidMapping() {
    String uid = assertStatus(HttpStatus.CREATED, POST(ENDPOINT, STORED_PATIENT));
    String invalid = patient(0, attribute(PATIENT_IDENTIFIER, INTEGER_ATTRIBUTE));
    String patch = "[{\"op\": \"remove\", \"path\": \"/fieldMappings/0/system\"}]";
    List<ErrorMessage> expected = List.of(error(E4000, "system"));
    for (String option : List.of("skipValidation=true", "atomicMode=NONE")) {
      assertConflict(POST(ENDPOINT + "?" + option, invalid), expected);
      assertEquals(1, mappingCount(), option);
      assertConflict(PUT(ENDPOINT + "/" + uid + "?" + option, invalid), expected);
      assertStoredAsSent(STORED_PATIENT);
      assertConflict(PATCH(ENDPOINT + "/" + uid + "?" + option, patch), expected);
      assertStoredAsSent(STORED_PATIENT);
    }
  }

  /** As a user with the given authorities, creates or changes a mapping with the given access. */
  @ParameterizedTest
  @CsvSource({
    "POST, , rw------, FORBIDDEN",
    "POST, F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD, rw------, CREATED",
    "POST, F_FHIR_RESOURCE_MAPPING_PRIVATE_ADD, rw------, CONFLICT",
    "POST, F_FHIR_RESOURCE_MAPPING_PRIVATE_ADD, --------, CREATED",
    "PUT, , rw------, FORBIDDEN",
    "PATCH, , rw------, FORBIDDEN",
    "PUT, F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD, r-------, FORBIDDEN",
    "PATCH, F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD, r-------, FORBIDDEN",
    "PATCH, F_FHIR_RESOURCE_MAPPING_PRIVATE_ADD, rw------, FORBIDDEN",
    "PUT, F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD, rw------, OK",
    "PATCH, F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD, rw------, OK",
    "DELETE, F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD, rw------, FORBIDDEN",
    "DELETE, F_FHIR_RESOURCE_MAPPING_DELETE, rw------, FORBIDDEN",
    "DELETE, F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD F_FHIR_RESOURCE_MAPPING_DELETE, r-------, FORBIDDEN",
    "DELETE, F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD F_FHIR_RESOURCE_MAPPING_DELETE, rw------, OK"
  })
  void mappingWriteRequiresAuthority(String verb, String grant, String access, HttpStatus status) {
    if (!verb.equals("POST")) assertStatus(HttpStatus.CREATED, POST(ENDPOINT, shared(access)));
    switchToNewUser("fhir-writer", grant == null ? new String[0] : grant.split(" "));
    HttpResponse response =
        switch (verb) {
          case "POST" -> POST(ENDPOINT, shared(access));
          case "PUT" -> PUT(STORED_PATH, mapping(RENAMED, PATIENT, PATIENT_ENTRIES));
          case "PATCH" -> PATCH(STORED_PATH, RENAME_PATCH);
          default -> DELETE(STORED_PATH);
        };
    boolean written = status.series() == HttpStatus.Series.SUCCESSFUL;
    if (!written) assertEquals("ERROR", response.content(status).getString("status").string());
    assertEquals(status, response.status());
    switchToAdminUser();
    if (verb.equals(written ? "DELETE" : "POST")) assertEquals(0, mappingCount());
    else if (written && !verb.equals("POST"))
      assertStoredAsSent(STORED_PATIENT.replace(STORED_NAME, RENAMED));
    else
      assertEquals(access, assertStoredAsSent(shared(access)).getString("sharing.public").string());
  }

  @Test
  void settingsPageIsServedAsHtml() {
    HttpResponse response = GET(ENDPOINT + "/settings", Accept("text/html"));
    assertEquals(HttpStatus.OK, response.status());
    String html = response.content("text/html");
    assertTrue(html.contains("id=\"fhir-mapping-form\""));
    assertTrue(String.valueOf(response.header("Cache-Control")).contains("no-store"), "no-store");
    List<String> scripts = SCRIPT.matcher(html).results().map(script -> script.group(1)).toList();
    assertEquals(1, scripts.size(), "inline scripts");
    PAGE_CALLS.forEach(call -> assertTrue(scripts.get(0).contains(call), call));
  }

  @Test
  void settingsPageMissingFromClasspathAnswersServerErrorWebMessage() {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    // The request thread's class loader no longer sees the application class path or the page.
    Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());
    try {
      HttpResponse response = GET(ENDPOINT + "/settings", Accept("text/html"));
      JsonObject error = response.content(HttpStatus.INTERNAL_SERVER_ERROR);
      String expected =
          "{\"httpStatus\": \"Internal Server Error\", \"httpStatusCode\": 500, \"status\": \"ERROR\", \"message\": \"The FHIR settings page is not available\"}";
      assertTrue(JsonMixed.of(expected).equivalentTo(error), error::toJson);
      assertEquals("application/json", response.getContentType());
      assertNull(response.header("Cache-Control"));
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  @Test
  void settingsPageRequestsSucceedThroughCsrfProtectedSecurityChain() {
    mvc = settingsPageChain(null);
    HttpResponse page = GET(ENDPOINT + "/settings", Accept("text/html"));
    assertEquals(HttpStatus.OK, page.status());
    String cookie = String.valueOf(page.header("Set-Cookie"));
    assertTrue(cookie.matches("XSRF-TOKEN=[^;]+(;.*)?"), cookie);
    String token = cookie.split("[=;]")[1];
    mvc = settingsPageChain(new Cookie("XSRF-TOKEN", token));
    assertStatus(HttpStatus.OK, GET(STATUS_PATH));
    String gender = gender(GIVEN_ATTRIBUTE, "__proto__", "male", " F ", "female");
    List<String> entries = put(PATIENT_ENTRIES, 2, gender);
    String patient = mapping(STORED_NAME, PATIENT, entries);
    assertStatus(HttpStatus.FORBIDDEN, POST(ENDPOINT, patient));
    assertEquals(0, mappingCount());
    Header csrf = Header("X-XSRF-TOKEN", token);
    String uid = assertStatus(HttpStatus.CREATED, POST(ENDPOINT, Body(patient), csrf));
    JsonObject edited = GET(ENDPOINT + "/" + uid + EDIT_FIELDS).content(HttpStatus.OK);
    assertEquals(summaries(entries), summaries(edited));
    assertStatus(
        HttpStatus.OK, PUT(ENDPOINT + "/" + uid, Body(mapping(RENAMED, PATIENT, entries)), csrf));
    assertTrue(GET(LIST_PATH).content(HttpStatus.OK).toJson().contains(RENAMED));
    assertStatus(HttpStatus.OK, DELETE(ENDPOINT + "/" + uid, csrf));
    assertStatus(HttpStatus.NOT_FOUND, GET(ENDPOINT + "/" + uid + EDIT_FIELDS));
  }

  @Test
  void settingsPagePickerAndListRequestsReturnTheFieldsThePageReads() {
    String typeKeys = "displayName,id,trackedEntityTypeAttributes";
    JsonObject person = byId(GET(TYPES_PATH).content(), "trackedEntityTypes", typeKeys).get(PERSON);
    assertEquals(
        "V66aa7a2122:NUMBER,dIVt4l5vIOa:TEXT,integerAttr:INTEGER,toUpdate000:TEXT",
        targets(person, "trackedEntityTypeAttributes", "trackedEntityAttribute"));
    String keys = "displayName,id,programStages,programTrackedEntityAttributes";
    Map<String, JsonObject> programs =
        byId(GET(PROGRAMS_FILTER + PERSON + PROGRAMS_FIELDS).content(), "programs", keys);
    assertEquals(
        "BFcipDERJnf,SeeUNWLQmZk,UWRnoyBjvqi,YlUmbgnKWkd,pcxIanBWlSY,sLngICFQjvH,shPjYNifvMK",
        String.join(",", new TreeSet<>(programs.keySet())));
    assertEquals(
        "dIVt4l5vIOa:TEXT,fRGt4l6yIRb:TEXT,multitxtAtr:MULTI_TEXT",
        targets(programs.get(PROGRAM), "programTrackedEntityAttributes", "trackedEntityAttribute"));
    String stageKeys = "displayName,id,programStageDataElements";
    Map<String, JsonObject> stages = byId(programs.get(PROGRAM), "programStages", stageKeys);
    assertEquals(Set.of(STAGE, "NpsdDv6kKS2"), stages.keySet());
    assertEquals(
        "DATAEL00001:TEXT,DATAEL00002:TEXT,DATAEL00003:TEXT,DATAEL00004:TEXT,DATAEL00005:TEXT,DATAEL00006:INTEGER,DATAEL00007:TEXT,GieVkTxp4HH:NUMBER",
        targets(stages.get(STAGE), "programStageDataElements", "dataElement"));
    for (String body : List.of(FULL_PATIENT, FULL_OBSERVATION)) {
      String id = assertStatus(HttpStatus.CREATED, POST(ENDPOINT, body));
      String item =
          body.replace("\"name\":", "\"displayName\":")
              .replace("{\"id\": \"" + PERSON + "\"}", "{\"displayName\": \"Person\"}")
              .replace("{\"id\": \"" + PROGRAM + "\"}", "{\"displayName\": \"BFcipDERJnf name\"}")
              .replace("{\"id\": \"" + STAGE + "\"}", "{\"displayName\": \"test-program-stage\"}");
      JsonList<JsonObject> listed = GET(LIST_PATH).content().getList(MAPPINGS, JsonObject.class);
      assertTrue(listed.stream().anyMatch(JsonMixed.of(item)::equivalentTo), listed::toJson);
      JsonObject edited = GET(ENDPOINT + "/" + id + EDIT_FIELDS).content();
      String detail = "{\"sharing\": " + edited.get("sharing").toJson() + ", " + body.substring(1);
      assertTrue(JsonMixed.of(detail).equivalentTo(edited), edited::toJson);
    }
  }

  /** Mappings that violate every rule within one mapping, with the messages each must yield. */
  private static Stream<InvalidMapping> invalidMappings() {
    return Stream.of(
        invalid(bad(PATIENT, put(PATIENT_ENTRIES, 3, entry(null, null))))
            .and(E4000, "target")
            .and(E4000, "sourceType"),
        invalid(bad(ENCOUNTER, ENCOUNTER_ENTRIES, PERSON, null, null))
            .and(E4000, "program")
            .and(E4000, "programStage"),
        invalid(bad(ENCOUNTER, ENCOUNTER_ENTRIES.subList(1, 2))).and(E4000, ENCOUNTER_CLASS),
        invalid(bad(IMMUNIZATION, List.of()))
            .and(E4000, IMMUNIZATION_ADMINISTERED)
            .and(E4000, IMMUNIZATION_VACCINE_CODE),
        invalid(bad(OBSERVATION, List.of())).and(E4000, OBSERVATION_VALUE),
        invalid(patient(0, attribute(PATIENT_IDENTIFIER, INTEGER_ATTRIBUTE))).and(E4000, "system"),
        invalid(bad(ENCOUNTER, List.of(constant(ENCOUNTER_CLASS, null)))).and(E4000, "code"),
        invalid(bad(OBSERVATION, List.of(observation(INTEGER_ELEMENT, null)))).and(E4000, "code"),
        invalid(patient(1, entry(PATIENT_FAMILY_NAME, ATTRIBUTE))).and(E4000, "source"),
        invalid(bad(PATIENT, put(PATIENT_ENTRIES, 3, observation(INTEGER_ELEMENT, "x"))))
            .and(E4010, OBSERVATION_VALUE, PATIENT),
        invalid(patient(1, constant(PATIENT_FAMILY_NAME, "f")))
            .and(E4010, CONSTANT, PATIENT_FAMILY_NAME),
        invalid(patient(1, attribute(PATIENT_FAMILY_NAME, NOT_A_UID)))
            .and(E4014, NOT_A_UID, "source"),
        invalid(patient(2, gender(GIVEN_ATTRIBUTE, "M", "man"))).and(E4027, "man", "valueMap"),
        invalid(patient(2, gender(GIVEN_ATTRIBUTE, "", "male"))).and(E4027, "", "valueMap"),
        invalid(patient(2, attribute(PATIENT_BIRTH_DATE, GIVEN_ATTRIBUTE)))
            .and(E4027, "TEXT", PATIENT_BIRTH_DATE),
        invalid(bad(OBSERVATION, List.of(observation(INTEGER_ELEMENT, "x "))))
            .and(E4027, "x ", "code"),
        invalid(patient(0, entry(PATIENT_IDENTIFIER, ATTRIBUTE, INTEGER_ATTRIBUTE, "urn:bad uri")))
            .and(E4027, "urn:bad uri", "system"),
        invalid(bad(ENCOUNTER, CLASS_ONLY, PERSON, EVENT_PROGRAM, "NpsdDv6kKSe"))
            .and(E5002, EVENT_PROGRAM, INVALID_ID, "program"),
        invalid(bad(ENCOUNTER, ENCOUNTER_ENTRIES, "Ip8NY4PW7Xm", PROGRAM, STAGE))
            .and(E5002, PROGRAM, INVALID_ID, "trackedEntityType"),
        invalid(bad(ENCOUNTER, CLASS_ONLY, PERSON, PROGRAM, OTHER_PROGRAM_STAGE))
            .and(E5002, OTHER_PROGRAM_STAGE, INVALID_ID, "programStage"),
        invalid(bad(PATIENT, PATIENT_ENTRIES, PERSON, null, STAGE))
            .and(E5002, STAGE, INVALID_ID, "programStage"),
        invalid(patient(1, attribute(PATIENT_FAMILY_NAME, PROGRAM_ONLY_ATTRIBUTE)))
            .and(E5002, PROGRAM_ONLY_ATTRIBUTE, INVALID_ID, PATIENT_FAMILY_NAME),
        invalid(bad(OBSERVATION, List.of(observation(OTHER_STAGE_ELEMENT, "x"))))
            .and(E5002, OTHER_STAGE_ELEMENT, INVALID_ID, OBSERVATION_VALUE),
        invalid(patient(2, attribute(PATIENT_FAMILY_NAME, GIVEN_ATTRIBUTE)))
            .and(E5003, "target", PATIENT_FAMILY_NAME, INVALID_ID, INVALID_ID),
        invalid(patient(2, PATIENT_ENTRIES.get(0)))
            .and(E5003, "system", IDENTIFIER_SYSTEM, INVALID_ID, INVALID_ID)
            .and(E5003, "source", INTEGER_ATTRIBUTE, INVALID_ID, INVALID_ID),
        invalid(patient(2, attribute(PATIENT_GIVEN_NAME, FAMILY_ATTRIBUTE)))
            .and(E5003, "source", FAMILY_ATTRIBUTE, INVALID_ID, INVALID_ID),
        invalid(bad(OBSERVATION, put(OBSERVATION_ENTRIES, 1, observation(INTEGER_ELEMENT, "y"))))
            .and(E5003, "source", INTEGER_ELEMENT, INVALID_ID, INVALID_ID),
        invalid(patient(2, gender(GIVEN_ATTRIBUTE, "M", "male", "m", "female")))
            .and(E5003, "valueMap", "m", INVALID_ID, INVALID_ID));
  }

  private record InvalidMapping(String body, List<ErrorMessage> errors) {
    /** Returns this mapping, also expecting the message of {@code code} with {@code args}. */
    InvalidMapping and(ErrorCode code, Object... args) {
      return new InvalidMapping(body, put(errors, errors.size(), error(code, args)));
    }
  }

  private static InvalidMapping invalid(String mapping) {
    return new InvalidMapping(withId(INVALID_ID, mapping), List.of());
  }

  /** Replaces any placeholder ID or name in {@code text} with the values of row {@code index}. */
  private static String imported(int index, String text) {
    return text.replace(INVALID_ID, "FhirImp%04d".formatted(index))
        .replace(INVALID_NAME, "FHIR import " + index);
  }

  /** Returns the base Patient mapping with the entry at {@code index} replaced by {@code entry}. */
  private static String patient(int index, String entry) {
    return bad(PATIENT, put(PATIENT_ENTRIES, index, entry));
  }

  private static ErrorMessage error(ErrorCode code, Object... args) {
    return new ErrorMessage(code, args);
  }

  /** Asserts a {@code 409} web message containing every expected text and no error reports. */
  private static void assertConflict(HttpResponse response, List<ErrorMessage> expected) {
    JsonWebMessage conflict = response.content(HttpStatus.CONFLICT).as(JsonWebMessage.class);
    assertEquals("ERROR", conflict.getStatus());
    assertEquals(409, conflict.getHttpStatusCode());
    String actual = conflict.getMessage();
    expected.forEach(error -> assertTrue(actual.contains(error.getMessage()), actual));
    assertTrue(conflict.get("response.errorReports").isUndefined(), conflict::toJson);
  }

  /** Asserts that the mapping stored under the body's UID has the body's properties and entries. */
  private JsonObject assertStoredAsSent(String body) {
    JsonObject sent = JsonMixed.of(body);
    JsonObject stored = GET(ENDPOINT + "/" + sent.getString("id").string()).content(HttpStatus.OK);
    assertEquals(summary(sent, MAPPING_FIELDS), summary(stored, MAPPING_FIELDS));
    assertEquals(summaries(sent), summaries(stored));
    return stored;
  }

  private int mappingCount() {
    return GET(ENDPOINT + "?fields=id&paging=false").content().getArray(MAPPINGS).size();
  }

  private String importConflict(String mapping) {
    String bundle = "{\"%s\": [%s]}".formatted(MAPPINGS, mapping);
    return POST("/metadata", bundle).content(HttpStatus.CONFLICT).toJson();
  }

  private List<String> csv(String path) {
    return GET(path, Accept("text/csv")).content("text/csv").lines().toList();
  }

  /** Sets X-Requested-With and an optional CSRF cookie on security-chain requests. */
  private MockMvc settingsPageChain(Cookie xsrf) {
    var page = MockMvcRequestBuilders.get("/").header("X-Requested-With", "XMLHttpRequest");
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(SecurityMockMvcConfigurers.springSecurity(springSecurityFilterChain))
        .defaultRequest(xsrf == null ? page : page.cookie(xsrf))
        .build();
  }

  private static List<String> summaries(List<String> entries) {
    return entries.stream().map(entry -> summary(JsonMixed.of(entry), ENTRY_FIELDS)).toList();
  }

  private static List<String> summaries(JsonObject mapping) {
    return mapping.getList("fieldMappings", JsonObject.class).toList(e -> summary(e, ENTRY_FIELDS));
  }

  /** Returns each property as {@code name=minimized JSON}, empty when the object lacks it. */
  private static String summary(JsonObject object, List<String> properties) {
    return properties.stream()
        .map(p -> p + "=" + (object.get(p).exists() ? object.get(p).toMinimizedJson() : ""))
        .collect(joining(","));
  }

  /** Indexes the owner's list by id, asserting that each object's sorted keys join to keys. */
  private static Map<String, JsonObject> byId(JsonObject owner, String list, String keys) {
    return owner.getList(list, JsonObject.class).stream()
        .collect(toMap(object -> object.getString("id").string(), object -> keyed(object, keys)));
  }

  private static JsonObject keyed(JsonObject object, String keys) {
    assertEquals(keys, object.names().stream().sorted().collect(joining(",")), object::toJson);
    return object;
  }

  /** Returns the sorted id:valueType of the object under each link, asserting both key sets. */
  private static String targets(JsonObject owner, String links, String link) {
    return owner.getList(links, JsonObject.class).stream()
        .map(each -> keyed(keyed(each, link).getObject(link), "displayName,id,valueType"))
        .map(t -> t.getString("id").string() + ":" + t.getString("valueType").string())
        .sorted()
        .collect(joining(","));
  }

  private static String shared(String publicAccess) {
    return "{\"sharing\": {\"public\": \"%s\"}, ".formatted(publicAccess)
        + STORED_PATIENT.substring(1);
  }

  private static String bad(FhirResourceType type, List<String> entries, String... references) {
    return mapping(INVALID_NAME, type, entries, references);
  }

  /**
   * Returns a mapping JSON object on the given tracked entity type, program and program stage,
   * leaving out nulls; without references, on PERSON, and on PROGRAM and STAGE unless a PATIENT.
   */
  private static String mapping(
      String name, FhirResourceType type, List<String> entries, String... references) {
    String[] ids = references.length > 0 ? references : new String[] {PERSON, PROGRAM, STAGE};
    String[] names = {"trackedEntityType", "program", "programStage"};
    String refs =
        IntStream.range(0, type == PATIENT && references.length == 0 ? 1 : 3)
            .filter(i -> ids[i] != null)
            .mapToObj(i -> ", \"%s\": {\"id\": \"%s\"}".formatted(names[i], ids[i]))
            .collect(joining());
    return "{\"name\": \"%s\", \"resourceType\": \"%s\"%s, \"fieldMappings\": [%s]}"
        .formatted(name, type.name(), refs, String.join(", ", entries));
  }

  private static String withId(String id, String mapping) {
    return "{\"id\": \"" + id + "\", " + mapping.substring(1);
  }

  private static String attribute(FhirTargetField target, String source) {
    return entry(target, ATTRIBUTE, source);
  }

  /** Returns a PATIENT_GENDER entry whose value map maps each value to the code following it. */
  private static String gender(String source, String... pairs) {
    String entry = attribute(PATIENT_GENDER, source);
    return IntStream.range(0, pairs.length / 2)
        .mapToObj(i -> "\"%s\": \"%s\"".formatted(pairs[2 * i], pairs[2 * i + 1]))
        .collect(joining(", ", entry.substring(0, entry.length() - 1) + ", \"valueMap\": {", "}}"));
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

  /** Returns {@code entries} with {@code entry} at {@code index}, replacing or appending. */
  private static <T> List<T> put(List<T> entries, int index, T entry) {
    Stream<T> rest = Stream.concat(Stream.of(entry), entries.stream().skip(index + 1));
    return Stream.concat(entries.stream().limit(index), rest).toList();
  }
}
