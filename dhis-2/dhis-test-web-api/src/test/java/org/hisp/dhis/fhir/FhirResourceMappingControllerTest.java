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
import static org.hisp.dhis.fhir.FhirResourceMappingStoreTest.FhirPostgresControllerTestBase.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.hisp.dhis.http.HttpAssertions.assertStatus;
import static org.hisp.dhis.http.HttpClientAdapter.*;
import static org.hisp.dhis.http.HttpStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.*;
import org.hisp.dhis.attribute.Attribute;
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
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;

/** Tests {@code /api/fhirResourceMappings}: CRUD, validation, metadata, sharing, settings page. */
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
  private static final String INTEGER_ATTRIBUTE = "integerAttr";
  private static final String PROGRAM_ONLY_ATTRIBUTE = "fRGt4l6yIRb";
  private static final String INTEGER_ELEMENT = "DATAEL00006";
  private static final String CODING_SYSTEM = "urn:dhis2:fhir-test:coding";
  private static final String INVALID_ID = "FhirMapBad1";
  private static final String INVALID_NAME = "FHIR invalid mapping";
  private static final String INVALID = INVALID_NAME + " [FhirMapBad1] (FhirResourceMapping)";
  private static final String STORED_ID = "FhirMapPat1";
  private static final String STORED_NAME = "FHIR stored Patient";
  private static final String STORED_PATH = ENDPOINT + "/" + STORED_ID;
  private static final String OBSERVATION_NAME = "FHIR stored Observation";
  private static final List<String> STORED_IDS = List.of(STORED_ID, "FhirMapObs1");
  private static final List<String> ENTRY_FIELDS =
      List.of("target", "sourceType", "source", "system", "code", "display", "unit", "valueMap");
  private static final String OTHER = FhirResourceMappingValidator.OTHER_MAPPING;
  private static final ErrorMessage DUPLICATE =
      new ErrorMessage(E5003, "resourceType", PATIENT, INVALID, OTHER);
  private static final String RENAMED = "FHIR renamed Patient";
  private static final String RENAME_PATCH =
      "[{\"op\": \"replace\", \"path\": \"/name\", \"value\": \"" + RENAMED + "\"}]";
  private static final List<String> MAPPING_FIELDS =
      List.of("name code resourceType trackedEntityType.id program.id programStage.id".split(" "));
  private static final String STATUS_PATH = "fhir/metadata";
  private static final String LIST_PATH =
      "fhirResourceMappings?fields=id,displayName,resourceType,trackedEntityType[displayName],program[displayName],programStage[displayName],fieldMappings&paging=false";
  private static final String EDIT_FIELDS =
      "?fields=id,name,code,resourceType,trackedEntityType[id],program[id],programStage[id],fieldMappings,sharing,translations,attributeValues";
  private static final String TYPES_PATH =
      "trackedEntityTypes?fields=id,displayName,trackedEntityTypeAttributes[trackedEntityAttribute[id,displayName,valueType]]&paging=false";
  private static final String PROGRAMS_FILTER =
      "programs?filter=programType:eq:WITH_REGISTRATION&filter=trackedEntityType.id:eq:";
  private static final String PROGRAMS_FIELDS =
      "&fields=id,displayName,programTrackedEntityAttributes[trackedEntityAttribute[id,displayName,valueType]],programStages[id,displayName,programStageDataElements[dataElement[id,displayName,valueType]]]&paging=false";
  private static final List<String> PAGE_CALLS =
      List.of(
          "api('GET', '" + STATUS_PATH + "'", "'X-Requested-With': 'XMLHttpRequest'",
          "'" + TYPES_PATH + "'", "'XSRF-TOKEN='",
          "'" + PROGRAMS_FILTER + "'", "headers['X-XSRF-TOKEN'] = token",
          "'" + PROGRAMS_FIELDS + "'",
              "signal: method === 'GET' ? AbortSignal.timeout(READ_TIMEOUT_MS) : undefined",
          "'" + LIST_PATH + "'", "el('option', {value: wanted}, 'Unavailable: ' + wanted)",
          "'" + EDIT_FIELDS + "'", "api('POST', '" + MAPPINGS + "'",
          "api('PUT', '" + MAPPINGS + "/'", "api('DELETE', '" + MAPPINGS + "/'");
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
  private static final String HIDDEN_PATIENT = shared("--------");
  private static final String FULL_PATIENT =
      withId(STORED_ID, mapping(STORED_NAME, PATIENT, GENDER_ENTRIES));
  private static final String FULL_OBSERVATION =
      withId("FhirMapObs1", mapping(OBSERVATION_NAME, OBSERVATION, OBSERVATION_ENTRIES));
  private static final String REFERENCED_BUNDLE =
      """
      {"trackedEntityTypes": [{"id": "FhirDelTet1", "name": "FHIR del 1", "shortName": "FHIR del 1"},
        {"id": "FhirDelTet2", "name": "FHIR del 2", "shortName": "FHIR del 2"}],
      "programs": [{"id": "FhirDelPrg1", "name": "FHIR del", "shortName": "FHIR del",
        "programType": "WITH_REGISTRATION", "trackedEntityType": {"id": "FhirDelTet1"},
        "programStages": [{"id": "FhirDelStg1"}]}],
      "programStages": [{"id": "FhirDelStg1", "name": "FHIR del", "program": {"id": "FhirDelPrg1"}}]}
      """;
  @Autowired private TestSetup testSetup;

  @BeforeEach
  void importTrackerMetadata() throws IOException {
    testSetup.importMetadata();
    manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete);
    dbmsManager.clearSession();
  }

  @Test
  void createReadUpdateDeleteMapping() {
    manager.getAllNoAcl(Attribute.class).forEach(attribute -> attribute.setUnique(false));
    assertStatus(OK, POST("/metadata", REFERENCED_BUNDLE));
    String[] refs = {"FhirDelTet1", "FhirDelPrg1", "FhirDelStg1"};
    String encounter = mapping("FHIR delete Encounter", ENCOUNTER, CLASS_ONLY, refs);
    String patient = mapping("FHIR delete Patient", PATIENT, List.of(), "FhirDelTet2", null, null);
    String encounterId = assertStatus(CREATED, POST(ENDPOINT, encounter));
    String patientId = assertStatus(CREATED, POST(ENDPOINT, patient));
    String types = "/trackedEntityTypes/FhirDelTet";
    var referenced = List.of("/programStages/FhirDelStg1", "/programs/FhirDelPrg1", types + "2");
    referenced.forEach(this::assertDeleteVetoed);
    var editor = switchToNewUser("fhir-editor", "F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD");
    assertStatus(OK, PATCH(ENDPOINT + "/" + patientId, RENAME_PATCH));
    switchToAdminUser();
    String editorPath = "/users/" + editor.getUid();
    assertDeleteVetoed(editorPath);
    assertEquals(2, mappingCount());
    for (String id : List.of(encounterId, patientId)) assertStatus(OK, DELETE(ENDPOINT + "/" + id));
    Stream.concat(referenced.stream(), Stream.of(types + "1", editorPath))
        .forEach(path -> assertStatus(OK, DELETE(path)));
    String created = mapping(STORED_NAME, PATIENT, GENDER_ENTRIES);
    String uid = assertStatus(CREATED, POST(ENDPOINT, created));
    assertStoredAsSent(withId(uid, created));
    String replaced = withId(uid, mapping("FHIR replaced Patient", PATIENT, PATIENT_ENTRIES));
    assertStatus(OK, PUT(ENDPOINT + "/" + uid, replaced));
    assertStoredAsSent(replaced);
    assertStatus(OK, PATCH(ENDPOINT + "/" + uid, RENAME_PATCH));
    assertStoredAsSent(replaced.replace("FHIR replaced Patient", RENAMED));
    assertStatus(OK, DELETE(ENDPOINT + "/" + uid));
    assertStatus(NOT_FOUND, GET(ENDPOINT + "/" + uid));
    assertEquals(0, mappingCount());
    List<String> mappings = List.of(FULL_PATIENT, FULL_OBSERVATION);
    mappings.forEach(mapping -> assertStatus(CREATED, POST(ENDPOINT, mapping)));
    JsonObject all = GET("/metadata?" + MAPPINGS + "=true").content(OK);
    for (String id : STORED_IDS) assertStatus(OK, DELETE(ENDPOINT + "/" + id));
    assertEquals(0, mappingCount());
    JsonMixed imported = POST("/metadata", all.toJson()).content(OK);
    JsonImportSummary report = imported.get("response", JsonImportSummary.class);
    assertEquals("OK", report.getStatus());
    assertEquals(2, report.getStats().getCreated());
    mappings.forEach(this::assertStoredAsSent);
    for (String id : STORED_IDS) assertStatus(OK, DELETE(ENDPOINT + "/" + id));
    String formula = STORED_PATIENT.replace(STORED_NAME, "=1+1,-2").substring(1);
    assertStatus(CREATED, POST(ENDPOINT, "{\"code\": \"\\uFEFF@A1\", " + formula));
    List<String> rows = List.of("'\uFEFF@A1,\"'=1+1,-2\"");
    List<String> listed = csv(ENDPOINT + "?fields=code,name&skipHeader=true");
    assertEquals(rows, listed.stream().map(row -> row.replace("'?", "'\uFEFF")).toList());
    assertEquals(rows, csv(ENDPOINT + "/gist.csv?fields=code,name&headless=true"));
    assertEquals(
        List.of("name,code", "\"'=1+1,-2\"", "'\uFEFF@A1"),
        csv(STORED_PATH + "/gist.csv?fields=name,code"));
    assertEquals(List.of("name", "\"'=1+1,-2\""), csv(STORED_PATH + "/name/gist.csv"));
    assertStatus(OK, DELETE(STORED_PATH));
    for (String mapping : List.of(HIDDEN_PATIENT, FULL_OBSERVATION))
      assertStatus(CREATED, POST(ENDPOINT, mapping));
    JsonObject export = GET(STORED_PATH + "/metadata?dataElements=true").content(OK);
    assertEquals(List.of(MAPPINGS, "system"), export.names().stream().sorted().toList());
    JsonList<JsonObject> exported = export.getList(MAPPINGS, JsonObject.class);
    assertEquals(List.of(STORED_ID), exported.toList(m -> m.getString("id").string()));
    JsonObject sent = JsonMixed.of(HIDDEN_PATIENT);
    assertEquals(summary(sent, MAPPING_FIELDS), summary(exported.get(0), MAPPING_FIELDS));
    assertEquals(summaries(sent), summaries(exported.get(0)));
    String disposition = GET(STORED_PATH + "/metadata?download=true").header("Content-Disposition");
    assertTrue(String.valueOf(disposition).startsWith("attachment"), disposition);
    assertMappingNotFound("FhirMapNone");
    switchToNewUser("fhir-reader");
    assertMappingNotFound(STORED_ID);
    assertStatus(OK, GET(ENDPOINT + "/FhirMapObs1/metadata"));
    switchToAdminUser();
    assertStatus(OK, DELETE(STORED_PATH));
    assertStatus(OK, POST("/metadata", export.toJson()));
    assertStoredAsSent(HIDDEN_PATIENT);
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
            .flatMap(t -> t.getObjectReports().stream().flatMap(o -> o.getErrorReports().stream()))
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
    String duplicate = withId(INVALID_ID, bad(PATIENT, PATIENT_ENTRIES));
    String foreign =
        withId(INVALID_ID, patient(1, attribute(PATIENT_FAMILY_NAME, PROGRAM_ONLY_ATTRIBUTE)));
    var importer = switchToNewUser("fhir-importer");
    String denied = importConflict(duplicate);
    assertEquals(denied, importConflict(foreign));
    switchToAdminUser();
    assertStatus(CREATED, POST(ENDPOINT, HIDDEN_PATIENT));
    switchToNewUser(importer);
    assertEquals(denied, importConflict(duplicate));
    assertTrue(denied.contains("\"E3000\""), denied);
    switchToNewUser("fhir-creator", "F_FHIR_RESOURCE_MAPPING_PUBLIC_ADD");
    assertEquals(0, mappingCount());
    String crud = POST(ENDPOINT, duplicate).content(CONFLICT).toJson();
    for (String json : List.of(crud, importConflict(duplicate))) {
      assertTrue(json.contains(DUPLICATE.getMessage()), json);
      assertFalse(json.contains(STORED_ID) || json.contains(STORED_NAME), json);
    }
    switchToAdminUser();
    assertEquals(1, mappingCount());
  }

  @Test
  void validationBypassOptionsCannotPersistInvalidMapping() {
    String uid = assertStatus(CREATED, POST(ENDPOINT, STORED_PATIENT));
    String invalid = patient(0, attribute(PATIENT_IDENTIFIER, INTEGER_ATTRIBUTE));
    String patch = "[{\"op\": \"remove\", \"path\": \"/fieldMappings/0/system\"}]";
    List<ErrorMessage> expected = List.of(new ErrorMessage(E4000, "fieldMappings[0].system"));
    for (String option : List.of("skipValidation=true", "atomicMode=NONE")) {
      assertConflict(POST(ENDPOINT + "?" + option, invalid), expected);
      assertEquals(1, mappingCount(), option);
      assertConflict(PUT(ENDPOINT + "/" + uid + "?" + option, invalid), expected);
      assertStoredAsSent(STORED_PATIENT);
      assertConflict(PATCH(ENDPOINT + "/" + uid + "?" + option, patch), expected);
      assertStoredAsSent(STORED_PATIENT);
    }
  }

  @Test
  void nameOrCodeConflictDoesNotRevealTheHiddenHolder() {
    String code = "FHIR-hidden";
    assertStatus(CREATED, POST(ENDPOINT, "{'code':'" + code + "', " + HIDDEN_PATIENT.substring(1)));
    switchToNewUser("fhir-private", "F_FHIR_RESOURCE_MAPPING_PRIVATE_ADD");
    assertStatus(NOT_FOUND, GET(STORED_PATH));
    String own = "{\"sharing\": {\"public\": \"--------\"}, " + FULL_OBSERVATION.substring(1);
    String named = own.replace(OBSERVATION_NAME, STORED_NAME);
    String coded = "{\"code\": \"" + code + "\", " + own.substring(1);
    String self = " [FhirMapObs1] (FhirResourceMapping)";
    var name = new ErrorMessage(E5003, "name", STORED_NAME, STORED_NAME + self, OTHER);
    var codeTaken = new ErrorMessage(E5003, "code", code, OBSERVATION_NAME + self, OTHER);
    assertHiddenConflict(POST(ENDPOINT, named), name);
    assertHiddenConflict(POST(ENDPOINT, coded), codeTaken);
    assertStatus(CREATED, POST(ENDPOINT, own));
    String ownPath = ENDPOINT + "/FhirMapObs1";
    assertHiddenConflict(PUT(ownPath, named), name);
    assertHiddenConflict(PATCH(ownPath, RENAME_PATCH.replace(RENAMED, STORED_NAME)), name);
    String recode = "[{\"op\": \"add\", \"path\": \"/code\", \"value\": \"" + code + "\"}]";
    assertHiddenConflict(PATCH(ownPath, recode), codeTaken);
    String imported = importConflict(named);
    assertTrue(imported.contains(name.getMessage()), imported);
    assertFalse(imported.contains(STORED_ID), imported);
    switchToNewUser("fhir-outsider");
    String denied = importConflict(named.replace("FhirMapObs1", "FhirMapObs3"));
    assertFalse(denied.contains(STORED_ID) || denied.contains("\"E5003\""), denied);
    switchToAdminUser();
    assertEquals(2, mappingCount());
    assertStoredAsSent(FULL_OBSERVATION);
  }

  @Test
  void blankNameIsRejectedWhateverTheRequestOptions() {
    assertStatus(CREATED, POST(ENDPOINT, STORED_PATIENT));
    List<ErrorMessage> unnamed = List.of(new ErrorMessage(E4000, "name"));
    String broken = entry(PATIENT_IDENTIFIER, ATTRIBUTE, INTEGER_ATTRIBUTE, "urn:a\\nb");
    String nameless = patient(0, broken).replace("\"name\": \"" + INVALID_NAME + "\", ", "");
    var system = new ErrorMessage(E4027, "urn:a b", "fieldMappings[0].system");
    var dupe = new ErrorMessage(E5003, "resourceType", PATIENT, "(new FhirResourceMapping)", OTHER);
    assertConflict(POST(ENDPOINT, nameless), List.of(unnamed.get(0), system, dupe));
    String bypass = "?skipValidation=true&atomicMode=NONE";
    String blank = FULL_OBSERVATION.replace(OBSERVATION_NAME, "\u00a0 ");
    assertConflict(POST(ENDPOINT + bypass, blank), unnamed);
    assertConflict(PUT(STORED_PATH + bypass, STORED_PATIENT.replace(STORED_NAME, "   ")), unnamed);
    assertConflict(PATCH(STORED_PATH + bypass, RENAME_PATCH.replace(RENAMED, "\\uFEFF")), unnamed);
    assertStoredAsSent(STORED_PATIENT);
    switchToNewUser("fhir-private", "F_FHIR_RESOURCE_MAPPING_PRIVATE_ADD");
    String hidden = "{\"sharing\": {\"public\": \"--------\"}, " + FULL_OBSERVATION.substring(1);
    assertConflict(POST(ENDPOINT + bypass, hidden.replace(OBSERVATION_NAME, "   ")), unnamed);
    String bundle = "{\"%s\": [%s]}".formatted(MAPPINGS, hidden.replace(OBSERVATION_NAME, "  "));
    String query = "/metadata?importStrategy=CREATE&" + bypass.substring(1);
    String imported = POST(query, bundle).content(CONFLICT).toJson();
    assertTrue(imported.contains(unnamed.get(0).getMessage()), imported);
    switchToAdminUser();
    assertEquals(1, mappingCount());
  }

  /** As a user with the given authorities, creates or changes a mapping with the given access. */
  @ParameterizedTest
  @CsvSource({
    "POST, , rw------, FORBIDDEN", "POST, PUBLIC_ADD, rw------, CREATED",
    "POST, PRIVATE_ADD, rw------, CONFLICT", "POST, PRIVATE_ADD, --------, CREATED",
    "PUT, , rw------, FORBIDDEN", "PATCH, , rw------, FORBIDDEN",
    "PUT, PUBLIC_ADD, r-------, FORBIDDEN", "PATCH, PUBLIC_ADD, r-------, FORBIDDEN",
    "PATCH, PRIVATE_ADD, rw------, FORBIDDEN", "PUT, PUBLIC_ADD, rw------, OK",
    "PATCH, PUBLIC_ADD, rw------, OK", "DELETE, PUBLIC_ADD, rw------, FORBIDDEN",
    "DELETE, DELETE, rw------, FORBIDDEN", "DELETE, PUBLIC_ADD DELETE, r-------, FORBIDDEN",
    "DELETE, PUBLIC_ADD DELETE, rw------, OK"
  })
  void mappingWriteRequiresAuthority(String verb, String grant, String access, HttpStatus status) {
    if (!verb.equals("POST")) assertStatus(CREATED, POST(ENDPOINT, shared(access)));
    String prefix = "F_FHIR_RESOURCE_MAPPING_";
    String[] authorities =
        grant == null ? new String[0] : (prefix + grant.replace(" ", " " + prefix)).split(" ");
    switchToNewUser("fhir-writer", authorities);
    HttpResponse response =
        switch (verb) {
          case "POST" -> POST(ENDPOINT, shared(access));
          case "PUT" -> PUT(STORED_PATH, mapping(RENAMED, PATIENT, PATIENT_ENTRIES));
          case "PATCH" -> PATCH(STORED_PATH, RENAME_PATCH);
          default -> DELETE(STORED_PATH);
        };
    boolean written = status.series() == Series.SUCCESSFUL;
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
    assertEquals(OK, response.status());
    String html = response.content("text/html");
    assertTrue(html.contains("id=\"fhir-mapping-form\""));
    assertTrue(html.contains("Log in again in another browser tab, then return"), "401 text");
    assertTrue(String.valueOf(response.header("Cache-Control")).contains("no-store"), "no-store");
    List<String> scripts = SCRIPT.matcher(html).results().map(script -> script.group(1)).toList();
    assertEquals(1, scripts.size(), "inline scripts");
    PAGE_CALLS.forEach(call -> assertTrue(scripts.get(0).contains(call), call));
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
      String id = assertStatus(CREATED, POST(ENDPOINT, body));
      String item =
          body.replace("\"name\":", "\"displayName\":")
              .replace("{\"id\": \"" + PERSON + "\"}", "{\"displayName\": \"Person\"}")
              .replace("{\"id\": \"" + PROGRAM + "\"}", "{\"displayName\": \"BFcipDERJnf name\"}")
              .replace("{\"id\": \"" + STAGE + "\"}", "{\"displayName\": \"test-program-stage\"}");
      JsonList<JsonObject> listed = GET(LIST_PATH).content().getList(MAPPINGS, JsonObject.class);
      assertTrue(listed.stream().anyMatch(JsonMixed.of(item)::equivalentTo), listed::toJson);
      JsonObject edited = GET(ENDPOINT + "/" + id + EDIT_FIELDS).content();
      String empty = ", \"translations\": [], \"attributeValues\": [], ";
      String detail = "{\"sharing\": " + edited.get("sharing").toJson() + empty + body.substring(1);
      assertTrue(JsonMixed.of(detail).equivalentTo(edited), edited::toJson);
    }
    var french = "'translations': [{'property': 'NAME', 'locale': 'fr', 'value': 'Patient FHIR'}]";
    String values = "'attributeValues': [{'attribute': {'id': 'j45AR9cBQKc'}, 'value': 'note'}]";
    assertStatus(OK, DELETE(STORED_PATH));
    assertStatus(CREATED, POST(ENDPOINT, STORED_PATIENT));
    assertStatus(NO_CONTENT, PUT(STORED_PATH + "/translations", "{" + french + "}"));
    JsonObject translated = GET(STORED_PATH + EDIT_FIELDS).content(OK);
    assertEquals(1, translated.getArray("translations").size(), translated::toJson);
    assertStatus(OK, PUT(STORED_PATH, pageBody(translated)));
    JsonObject kept = GET(STORED_PATH + EDIT_FIELDS).content(OK);
    assertTrue(translated.equivalentTo(kept), kept::toJson);
    String bundle =
        "{\"%s\": [{%s, %s, %s]}".formatted(MAPPINGS, french, values, STORED_PATIENT.substring(1));
    assertStatus(OK, POST("/metadata?skipValidation=true", bundle));
    JsonObject noted = GET(STORED_PATH + EDIT_FIELDS).content(OK);
    assertEquals(1, noted.getArray("translations").size(), noted::toJson);
    assertEquals(1, noted.getArray("attributeValues").size(), noted::toJson);
    JsonObject report = PUT(STORED_PATH, pageBody(noted)).content(CONFLICT).getObject("response");
    JsonList<JsonErrorReport> errors = report.getList("errorReports", JsonErrorReport.class);
    assertEquals(List.of(E6012), errors.toList(JsonErrorReport::getErrorCode), report::toJson);
    kept = GET(STORED_PATH + EDIT_FIELDS).content(OK);
    assertTrue(noted.equivalentTo(kept), kept::toJson);
    for (String id : STORED_IDS) assertStatus(OK, DELETE(ENDPOINT + "/" + id));
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());
    try {
      HttpResponse missing = GET(ENDPOINT + "/settings", Accept("text/html"));
      JsonObject error = missing.content(INTERNAL_SERVER_ERROR);
      String expected =
          "{\"httpStatus\": \"Internal Server Error\", \"httpStatusCode\": 500, \"status\": \"ERROR\", \"message\": \"The FHIR settings page is not available\"}";
      assertTrue(JsonMixed.of(expected).equivalentTo(error), error::toJson);
      assertEquals("application/json", missing.getContentType());
      assertNull(missing.header("Cache-Control"));
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
    mvc = settingsPageChain(null);
    HttpResponse page = GET(ENDPOINT + "/settings", Accept("text/html"));
    assertEquals(OK, page.status());
    String cookie = String.valueOf(page.header("Set-Cookie"));
    assertTrue(cookie.matches("XSRF-TOKEN=[^;]+(;.*)?"), cookie);
    String token = cookie.split("[=;]")[1];
    mvc = settingsPageChain(new Cookie("XSRF-TOKEN", token));
    assertStatus(OK, GET(STATUS_PATH));
    String gender = gender(GIVEN_ATTRIBUTE, "__proto__", "male", " F ", "female");
    List<String> entries = put(PATIENT_ENTRIES, 2, gender);
    String patient = mapping(STORED_NAME, PATIENT, entries);
    assertStatus(FORBIDDEN, POST(ENDPOINT, patient));
    assertEquals(0, mappingCount());
    Header csrf = Header("X-XSRF-TOKEN", token);
    String uid = assertStatus(CREATED, POST(ENDPOINT, Body(patient), csrf));
    JsonObject edited = GET(ENDPOINT + "/" + uid + EDIT_FIELDS).content(OK);
    assertEquals(summaries(JsonMixed.of(patient)), summaries(edited));
    assertStatus(OK, PUT(ENDPOINT + "/" + uid, Body(mapping(RENAMED, PATIENT, entries)), csrf));
    assertTrue(GET(LIST_PATH).content(OK).toJson().contains(RENAMED));
    assertStatus(OK, DELETE(ENDPOINT + "/" + uid, csrf));
    assertStatus(NOT_FOUND, GET(ENDPOINT + "/" + uid + EDIT_FIELDS));
  }

  private static Stream<InvalidMapping> invalidMappings() {
    return Stream.of(
        invalid(bad(PATIENT, put(PATIENT_ENTRIES, 3, entry(null, null))))
            .and(E4000, "fieldMappings[3].target")
            .and(E4000, "fieldMappings[3].sourceType"),
        invalid(bad(ENCOUNTER, ENCOUNTER_ENTRIES, PERSON, null, null))
            .and(E4000, "program")
            .and(E4000, "programStage"),
        invalid(bad(ENCOUNTER, ENCOUNTER_ENTRIES.subList(1, 2))).and(E4000, ENCOUNTER_CLASS),
        invalid(bad(IMMUNIZATION, List.of()))
            .and(E4000, IMMUNIZATION_ADMINISTERED)
            .and(E4000, IMMUNIZATION_VACCINE_CODE),
        invalid(bad(OBSERVATION, List.of())).and(E4000, OBSERVATION_VALUE),
        invalid(patient(0, attribute(PATIENT_IDENTIFIER, INTEGER_ATTRIBUTE)))
            .and(E4000, "fieldMappings[0].system"),
        invalid(bad(ENCOUNTER, List.of(constant(ENCOUNTER_CLASS, null))))
            .and(E4000, "fieldMappings[0].code"),
        invalid(bad(OBSERVATION, List.of(observation(INTEGER_ELEMENT, null))))
            .and(E4000, "fieldMappings[0].code"),
        invalid(patient(1, entry(PATIENT_FAMILY_NAME, ATTRIBUTE)))
            .and(E4000, "fieldMappings[1].source"),
        invalid(bad(PATIENT, put(PATIENT_ENTRIES, 3, observation(INTEGER_ELEMENT, "x"))))
            .and(E4010, OBSERVATION_VALUE, PATIENT),
        invalid(patient(1, constant(PATIENT_FAMILY_NAME, "f")))
            .and(E4010, CONSTANT, PATIENT_FAMILY_NAME),
        invalid(patient(1, attribute(PATIENT_FAMILY_NAME, "not-a-uid")))
            .and(E4014, "not-a-uid", "fieldMappings[1].source"),
        invalid(patient(2, gender(GIVEN_ATTRIBUTE, "M", "man")))
            .and(E4027, "man", "fieldMappings[2].valueMap"),
        invalid(patient(2, gender(GIVEN_ATTRIBUTE, "", "male")))
            .and(E4027, "", "fieldMappings[2].valueMap"),
        invalid(patient(2, attribute(PATIENT_BIRTH_DATE, GIVEN_ATTRIBUTE)))
            .and(E4027, "TEXT", PATIENT_BIRTH_DATE),
        invalid(bad(OBSERVATION, List.of(observation(INTEGER_ELEMENT, "x "))))
            .and(E4027, "x ", "fieldMappings[0].code"),
        invalid(patient(0, entry(PATIENT_IDENTIFIER, ATTRIBUTE, INTEGER_ATTRIBUTE, "urn:bad uri")))
            .and(E4027, "urn:bad uri", "fieldMappings[0].system"),
        invalid(bad(ENCOUNTER, CLASS_ONLY, PERSON, "BFcipDERJne", "NpsdDv6kKSe"))
            .and(E5002, "BFcipDERJne", INVALID, "program"),
        invalid(bad(ENCOUNTER, ENCOUNTER_ENTRIES, "Ip8NY4PW7Xm", PROGRAM, STAGE))
            .and(E5002, PROGRAM, INVALID, "trackedEntityType"),
        invalid(bad(ENCOUNTER, CLASS_ONLY, PERSON, PROGRAM, "SKNvpoLioON"))
            .and(E5002, "SKNvpoLioON", INVALID, "programStage"),
        invalid(bad(PATIENT, PATIENT_ENTRIES, PERSON, null, STAGE))
            .and(E5002, STAGE, INVALID, "programStage"),
        invalid(patient(1, attribute(PATIENT_FAMILY_NAME, PROGRAM_ONLY_ATTRIBUTE)))
            .and(E5002, PROGRAM_ONLY_ATTRIBUTE, INVALID, "fieldMappings[1].source"),
        invalid(bad(OBSERVATION, List.of(observation("FieVkTxp4HE", "x"))))
            .and(E5002, "FieVkTxp4HE", INVALID, "fieldMappings[0].source"),
        invalid(patient(2, attribute(PATIENT_FAMILY_NAME, GIVEN_ATTRIBUTE)))
            .and(E5003, "target", PATIENT_FAMILY_NAME, "fieldMappings[2]", "fieldMappings[1]"),
        invalid(patient(2, PATIENT_ENTRIES.get(0)))
            .and(E5003, "system", IDENTIFIER_SYSTEM, "fieldMappings[2]", "fieldMappings[0]")
            .and(E5003, "source", INTEGER_ATTRIBUTE, "fieldMappings[2]", "fieldMappings[0]"),
        invalid(patient(2, attribute(PATIENT_GIVEN_NAME, FAMILY_ATTRIBUTE)))
            .and(E5003, "source", FAMILY_ATTRIBUTE, "fieldMappings[2]", "fieldMappings[1]"),
        invalid(bad(OBSERVATION, put(OBSERVATION_ENTRIES, 1, observation(INTEGER_ELEMENT, "y"))))
            .and(E5003, "source", INTEGER_ELEMENT, "fieldMappings[1]", "fieldMappings[0]"),
        invalid(patient(2, gender(GIVEN_ATTRIBUTE, "M", "male", "m", "female")))
            .and(E5003, "valueMap", "m", "fieldMappings[2]", "fieldMappings[2] key `M`"));
  }

  private record InvalidMapping(String body, List<ErrorMessage> errors) {
    InvalidMapping and(ErrorCode code, Object... args) {
      return new InvalidMapping(body, put(errors, errors.size(), new ErrorMessage(code, args)));
    }
  }

  private static InvalidMapping invalid(String mapping) {
    return new InvalidMapping(withId(INVALID_ID, mapping), List.of());
  }

  private static String imported(int index, String text) {
    return text.replace(INVALID_ID, "FhirImp%04d".formatted(index))
        .replace(INVALID_NAME, "FHIR import " + index);
  }

  private static String patient(int index, String entry) {
    return bad(PATIENT, put(PATIENT_ENTRIES, index, entry));
  }

  private static void assertConflict(HttpResponse response, List<ErrorMessage> expected) {
    JsonWebMessage conflict = response.content(CONFLICT).as(JsonWebMessage.class);
    assertEquals("ERROR", conflict.getStatus());
    assertEquals(409, conflict.getHttpStatusCode());
    List<String> lines = expected.stream().map(ErrorMessage::getMessage).toList();
    assertEquals(lines, conflict.getMessage().lines().filter(lines::contains).toList());
    assertTrue(conflict.get("response.errorReports").isUndefined(), conflict::toJson);
  }

  private static void assertHiddenConflict(HttpResponse response, ErrorMessage expected) {
    assertConflict(response, List.of(expected));
    String json = response.content(CONFLICT).toJson();
    assertFalse(json.contains(STORED_ID), json);
  }

  private void assertDeleteVetoed(String path) {
    JsonWebMessage vetoed = DELETE(path).content(CONFLICT).as(JsonWebMessage.class);
    var errors = vetoed.getResponse().getList("errorReports", JsonErrorReport.class);
    assertEquals(List.of(E4030), errors.toList(JsonErrorReport::getErrorCode), vetoed::toJson);
    String message = new ErrorMessage(E4030, "FhirResourceMapping").getMessage();
    assertEquals(message, errors.get(0).getMessage());
    String body = vetoed.toJson();
    assertFalse(body.matches("(?s).*(fhirresourcemapping|(?i:foreign key|constraint)).*"), body);
    assertStatus(OK, GET(path));
  }

  private void assertMappingNotFound(String uid) {
    HttpResponse response = GET(ENDPOINT + "/" + uid + "/metadata");
    JsonWebMessage error = response.content(NOT_FOUND).as(JsonWebMessage.class);
    assertEquals(E1005, error.getErrorCode(), error::toJson);
    assertEquals("FhirResourceMapping with id " + uid + " could not be found.", error.getMessage());
  }

  private JsonObject assertStoredAsSent(String body) {
    JsonObject sent = JsonMixed.of(body);
    JsonObject stored = GET(ENDPOINT + "/" + sent.getString("id").string()).content(OK);
    assertEquals(summary(sent, MAPPING_FIELDS), summary(stored, MAPPING_FIELDS));
    assertEquals(summaries(sent), summaries(stored));
    return stored;
  }

  private int mappingCount() {
    return GET(ENDPOINT + "?fields=id&paging=false").content().getArray(MAPPINGS).size();
  }

  private String importConflict(String mapping) {
    String bundle = "{\"%s\": [%s]}".formatted(MAPPINGS, mapping);
    return POST("/metadata", bundle).content(CONFLICT).toJson();
  }

  private List<String> csv(String path) {
    return GET(path, Accept("text/csv")).content("text/csv").lines().toList();
  }

  private MockMvc settingsPageChain(Cookie xsrf) {
    var page = MockMvcRequestBuilders.get("/").header("X-Requested-With", "XMLHttpRequest");
    return MockMvcBuilders.webAppContextSetup(webApplicationContext)
        .apply(springSecurity())
        .defaultRequest(xsrf == null ? page : page.cookie(xsrf))
        .build();
  }

  private static List<String> summaries(JsonObject mapping) {
    return mapping.getList("fieldMappings", JsonObject.class).toList(e -> summary(e, ENTRY_FIELDS));
  }

  private static String summary(JsonObject object, List<String> properties) {
    return properties.stream()
        .map(p -> p + "=" + (object.get(p).exists() ? object.get(p).toMinimizedJson() : ""))
        .collect(joining(","));
  }

  private static Map<String, JsonObject> byId(JsonObject owner, String list, String keys) {
    return owner.getList(list, JsonObject.class).stream()
        .collect(toMap(object -> object.getString("id").string(), object -> keyed(object, keys)));
  }

  private static JsonObject keyed(JsonObject object, String keys) {
    assertEquals(keys, object.names().stream().sorted().collect(joining(",")), object::toJson);
    return object;
  }

  private static String targets(JsonObject owner, String links, String link) {
    return owner.getList(links, JsonObject.class).stream()
        .map(each -> keyed(keyed(each, link).getObject(link), "displayName,id,valueType"))
        .map(t -> t.getString("id").string() + ":" + t.getString("valueType").string())
        .sorted()
        .collect(joining(","));
  }

  private static String shared(String access) {
    return "{\"sharing\": {\"public\": \"%s\"}, %s".formatted(access, STORED_PATIENT.substring(1));
  }

  private static String bad(FhirResourceType type, List<String> entries, String... references) {
    return mapping(INVALID_NAME, type, entries, references);
  }

  /** Mapping JSON without nulls; by default on PERSON and, unless a PATIENT, PROGRAM and STAGE. */
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

  private static String pageBody(JsonObject edited) {
    return edited.names().stream()
        .filter(name -> !name.equals("id"))
        .map(name -> "\"%s\": %s".formatted(name, edited.get(name).toJson()))
        .collect(joining(", ", "{", "}"));
  }

  private static String attribute(FhirTargetField target, String source) {
    return entry(target, ATTRIBUTE, source);
  }

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

  private static String entry(Object... values) {
    return IntStream.range(0, values.length)
        .filter(i -> values[i] != null)
        .mapToObj(i -> "\"%s\": \"%s\"".formatted(ENTRY_FIELDS.get(i), values[i]))
        .collect(joining(", ", "{", "}"));
  }

  private static <T> List<T> put(List<T> entries, int index, T entry) {
    Stream<T> rest = Stream.concat(Stream.of(entry), entries.stream().skip(index + 1));
    return Stream.concat(entries.stream().limit(index), rest).toList();
  }
}
