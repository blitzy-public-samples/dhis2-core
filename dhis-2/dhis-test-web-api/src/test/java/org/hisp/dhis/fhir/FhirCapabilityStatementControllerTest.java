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

import static org.hisp.dhis.fhir.FhirPostgresControllerTestBase.parseOk;
import static org.hisp.dhis.http.HttpClientAdapter.Body;
import static org.hisp.dhis.http.HttpMethod.*;
import static org.hisp.dhis.http.HttpStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMapping;
import org.hisp.dhis.http.HttpMethod;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.*;
import org.hisp.dhis.test.webapi.H2ControllerIntegrationTestBase;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.hisp.dhis.webapi.openapi.OpenApiObject;
import org.hisp.dhis.webapi.openapi.OpenApiObject.*;
import org.hisp.dhis.webapi.openapi.OpenApiObject.ParameterObject.In;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.*;
import org.hl7.fhir.r4.model.Enumerations.FHIRVersion;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

/** Tests the FHIR CapabilityStatement, OpenAPI contract and 400, 404 and 501 outcomes on H2. */
@Transactional
@ContextConfiguration(classes = FhirResourceMappingControllerTest.FhirApiEnabledConfig.class)
class FhirCapabilityStatementControllerTest extends H2ControllerIntegrationTestBase {
  private static final String FHIR_BASE = "/api/fhir";
  private static final String METADATA_PATH = FHIR_BASE + "/metadata";
  private static final String FHIR_JSON = "application/fhir+json";
  private static final String OBSERVATION_READ = "/Observation/TvctPPhpD8z-D9PbzJY8bJM-GieVkTxp4HH";

  /** Reads, malformed-id reads, searches and {@code $everything}, one row per bridged type. */
  private static final String BRIDGED_TYPE_REQUESTS =
      """
      /Patient/dUE514NMOlo /Patient/bad /Patient/bad?foo=1 /Patient /Patient?family=rain&unknown=1 /Patient/dUE514NMOlo/$everything
      /Encounter/TvctPPhpD8z-D9PbzJY8bJM /Encounter/bad /Encounter /Encounter?patient=dUE514NMOlo
      /Immunization/TvctPPhpD8z-D9PbzJY8bJM-DATAEL00001 /Immunization/x-y /Immunization?patient=dUE514NMOlo
      /Observation/TvctPPhpD8z-D9PbzJY8bJM-GieVkTxp4HH /Observation/bad /Observation?patient=dUE514NMOlo
      """;

  @Autowired private TestSetup testSetup;

  @Test
  void capabilityStatementListsOnlyMappedResourcesAndParameters() throws IOException {
    deleteAllMappings();
    testSetup.importMetadata();
    testSetup.importMetadata("fhir/fhir_resource_mappings.json");
    manager.flush();
    manager.clear();
    CapabilityStatement statement = readCapabilityStatement("");
    String url = statement.getImplementation().getUrl();
    assertTrue(url != null && url.endsWith(FHIR_BASE), "implementation.url " + url);
    List<String> expected =
        List.of(
            "Patient read search-type _id:token identifier:token family:string given:string"
                + " | everything=http://hl7.org/fhir/OperationDefinition/Patient-everything",
            "Encounter read search-type _id:token patient:reference subject:reference |",
            "Immunization read search-type _id:token patient:reference |",
            "Observation read search-type _id:token patient:reference subject:reference"
                + " code:token |");
    assertEquals(expected, describeResources(statement));
    manager.delete(manager.get(FhirResourceMapping.class, "FhirMapObs1"));
    manager.flush();
    manager.clear();
    assertEquals(expected.subList(0, 3), describeResources(readCapabilityStatement("")));
    assertOutcome(GET, FHIR_BASE + OBSERVATION_READ, NOT_IMPLEMENTED, "Observation");
  }

  @Test
  void metadataAcceptsFormatAndRejectsOtherParameters() {
    for (String format : List.of("json", "application/json", "application/fhir+json")) {
      assertEquals(
          FHIRVersion._4_0_1, readCapabilityStatement("?_format=" + format).getFhirVersion());
    }
    assertOutcome(GET, METADATA_PATH + "?_format=xml", BAD_REQUEST, "Invalid parameter '_format'");
    assertOutcome(GET, METADATA_PATH + "?foo=1", BAD_REQUEST, "Invalid parameter 'foo'");
    assertOutcome(
        GET, METADATA_PATH + "?_format=json&foo=1", BAD_REQUEST, "Invalid parameter 'foo'");
  }

  @Test
  void unmappedResourceTypesReturnNotSupported() {
    deleteAllMappings();
    for (String request : BRIDGED_TYPE_REQUESTS.strip().split("\\s+")) {
      assertOutcome(GET, FHIR_BASE + request, NOT_IMPLEMENTED, request.split("[/?]")[1]);
    }
  }

  @Test
  void unbridgedTypeAndWriteMethodsReturnNotSupported() {
    for (String path : List.of("/Condition", "/Condition/x", "/Condition?patient=dUE514NMOlo")) {
      assertOutcome(
          GET, FHIR_BASE + path, NOT_IMPLEMENTED, "Resource type Condition is not supported");
    }
    for (HttpMethod method : List.of(POST, PUT, PATCH, DELETE)) {
      for (String path : List.of("", "/Patient", "/Patient/dUE514NMOlo", "/metadata")) {
        assertOutcome(
            method, FHIR_BASE + path, NOT_IMPLEMENTED, "Write interactions are not supported");
      }
    }
  }

  @Test
  void unknownPathReturnsNotFound() {
    for (String path :
        List.of("", "/NotAResource", "/NotAResource/abc", "/Patient/dUE514NMOlo/extra/segment")) {
      assertOutcome(GET, FHIR_BASE + path, NOT_FOUND, "The requested resource was not found");
    }
  }

  @Test
  void openApiDocumentsFhirJsonResponsesAndParameters() {
    String url =
        "/openapi/openapi.json?failOnNameClash=true&failOnInconsistency=true"
            + " Patient Encounter Immunization Observation CapabilityStatement"
                .replaceAll(" (\\w+)", "&scope=controller:Fhir$1Controller");
    OpenApiObject doc = GET(url).content().as(OpenApiObject.class);
    String operations =
        """
        /api/fhir/Encounter/ #FhirSearchsetBundle _count _format _id _page patient subject
        /api/fhir/Encounter/{id} #FhirEncounterResource _format id*
        /api/fhir/Immunization/ #FhirSearchsetBundle _count _format _id _page patient
        /api/fhir/Immunization/{id} #FhirImmunizationResource _format id*
        /api/fhir/Observation/ #FhirSearchsetBundle _count _format _id _page code patient subject
        /api/fhir/Observation/{id} #FhirObservationResource _format id*
        /api/fhir/Patient/ #FhirSearchsetBundle _count _format _id _page birthdate family gender given identifier
        /api/fhir/Patient/{id} #FhirPatientResource _format id*
        /api/fhir/Patient/{id}/$everything #FhirSearchsetBundle _format id*
        /api/fhir/metadata #FhirCapabilityStatementResource _format
        """;
    List<String> described = new ArrayList<>();
    for (String path : doc.$paths().names().stream().sorted().toList()) {
      OperationObject get = doc.$paths().get(path).get();
      JsonMap<MediaTypeObject> content = get.responses().get("200").content();
      assertEquals(List.of(FHIR_JSON), content.names(), path);
      StringJoiner line = new StringJoiner(" ").add(path).add(ref(content.get(FHIR_JSON).schema()));
      get.parameters(In.query).stream().map(ParameterObject::name).sorted().forEach(line::add);
      get.parameters(In.path).forEach(id -> line.add(id.name() + (id.required() ? "*" : "?")));
      described.add(line.toString());
    }
    assertEquals(operations.strip().lines().toList(), described);
    String requiredMembers =
        """
        FhirPatientResource Patient resourceType
        FhirEncounterResource Encounter class resourceType status
        FhirImmunizationResource Immunization occurrenceDateTime patient resourceType status vaccineCode
        FhirObservationResource Observation code resourceType status
        FhirSearchsetBundle Bundle resourceType type
        FhirCapabilityStatementResource CapabilityStatement date fhirVersion format kind resourceType status
        """;
    JsonMap<SchemaObject> schemas = doc.components().schemas();
    for (String line : requiredMembers.strip().lines().toList()) {
      SchemaObject schema = schemas.get(line.split(" ")[0]);
      StringJoiner members = new StringJoiner(" ").add(line.split(" ")[0]);
      schema.properties().get("resourceType").resolve().$enum().forEach(members::add);
      schema.required().stream().map(String::valueOf).sorted().forEach(members::add);
      assertEquals(line, members.toString());
    }
    SchemaObject entry = schemas.get("FhirSearchsetBundle").properties().get("entry").items();
    StringJoiner oneOf = new StringJoiner(" ");
    entry.resolve().properties().get("resource").oneOf().forEach(s -> oneOf.add(ref(s)));
    String resources = "#FhirPatientResource #FhirEncounterResource #FhirImmunizationResource";
    assertEquals(resources + " #FhirObservationResource", oneOf.toString());
    JsonMixed served = JsonMixed.of(GET(METADATA_PATH).content(FHIR_JSON));
    List<Text> members = schemas.get("FhirCapabilityStatementResource").required();
    assertTrue(served.has(members), members::toString);
    assertEquals("CapabilityStatement", served.getString("resourceType").string());
  }

  private static String ref(SchemaObject schema) {
    return schema.getString("$ref").string().replace("#/components/schemas/", "#");
  }

  private void deleteAllMappings() {
    manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete);
    manager.flush();
    manager.clear();
  }

  private CapabilityStatement readCapabilityStatement(String query) {
    return parseOk(GET(METADATA_PATH + query), CapabilityStatement.class);
  }

  /** Describes each resource as {@code "Type interactions params | operations"}. */
  private static List<String> describeResources(CapabilityStatement statement) {
    List<String> resources = new ArrayList<>();
    for (CapabilityStatementRestResourceComponent resource :
        statement.getRestFirstRep().getResource()) {
      StringJoiner line = new StringJoiner(" ").add(resource.getType());
      resource.getInteraction().forEach(i -> line.add(i.getCode().toCode()));
      resource.getSearchParam().forEach(p -> line.add(p.getName() + ":" + p.getType().toCode()));
      line.add("|");
      resource.getOperation().forEach(o -> line.add(o.getName() + "=" + o.getDefinition()));
      resources.add(line.toString());
    }
    return resources;
  }

  /** Sends a request, with a body unless GET or DELETE, and asserts its OperationOutcome. */
  private void assertOutcome(HttpMethod method, String path, HttpStatus status, String text) {
    IssueType code =
        Map.of(BAD_REQUEST, IssueType.INVALID, NOT_FOUND, IssueType.NOTFOUND)
            .getOrDefault(status, IssueType.NOTSUPPORTED);
    HttpResponse response =
        method == GET || method == DELETE
            ? perform(method, path)
            : perform(method, path, Body("{'resourceType':'Patient'}"));
    assertAll(
        method + " " + path,
        () ->
            FhirPostgresControllerTestBase.assertOutcome(
                response, status, code, d -> d.contains(text)));
  }
}
