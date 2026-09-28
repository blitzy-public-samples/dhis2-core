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

import static org.hisp.dhis.fhir.FhirResourceMappingStoreTest.FhirResponses.*;
import static org.hisp.dhis.http.HttpClientAdapter.*;
import static org.hisp.dhis.http.HttpMethod.*;
import static org.hisp.dhis.http.HttpStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMapping;
import org.hisp.dhis.http.*;
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
  private static final String BASE = "/api/fhir";
  private static final String METADATA = BASE + "/metadata";
  private static final String OBSERVATION_READ = "/Observation/TvctPPhpD8z-D9PbzJY8bJM-GieVkTxp4HH";
  private static final String OPENAPI_PATHS =
      "fhir/openapi.json fhir/openapi.yaml fhir/openapi.html 44/fhir/openapi.html";
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
    dbmsManager.clearSession();
    CapabilityStatement statement = capabilities("");
    assertEquals(SERVER_ORIGIN + BASE, statement.getImplementation().getUrl());
    var proxied = parseOk(GET(METADATA, FORWARDED), CapabilityStatement.class);
    assertEquals(SERVER_ORIGIN + BASE, proxied.getImplementation().getUrl());
    String resources =
        """
        Patient read search-type _id:token identifier:token family:string given:string | everything=http://hl7.org/fhir/OperationDefinition/Patient-everything
        Encounter read search-type _id:token patient:reference subject:reference |
        Immunization read search-type _id:token patient:reference |
        Observation read search-type _id:token patient:reference subject:reference code:token |
        """;
    List<String> expected = resources.lines().toList();
    assertEquals(expected, describeResources(statement));
    manager.delete(manager.get(FhirResourceMapping.class, "FhirMapObs1"));
    dbmsManager.clearSession();
    assertEquals(expected.subList(0, 3), describeResources(capabilities("")));
    assertIssue(GET, BASE + OBSERVATION_READ, NOT_IMPLEMENTED, "Observation");
    String openApi =
        "/openapi/openapi.json?failOnNameClash=true&failOnInconsistency=true"
            + " Patient Encounter Immunization Observation CapabilityStatement"
                .replaceAll(" (\\w+)", "&scope=controller:Fhir$1Controller");
    OpenApiObject doc = GET(openApi).content().as(OpenApiObject.class);
    String operations =
        """
        /api/fhir/Encounter/ bundle.html _count _format _id _page patient subject
        /api/fhir/Encounter/{id} encounter.html _format id*
        /api/fhir/Immunization/ bundle.html _count _format _id _page patient
        /api/fhir/Immunization/{id} immunization.html _format id*
        /api/fhir/Observation/ bundle.html _count _format _id _page code patient subject
        /api/fhir/Observation/{id} observation.html _format id*
        /api/fhir/Patient/ bundle.html _count _format _id _page birthdate family gender given identifier
        /api/fhir/Patient/{id} patient.html _format id*
        /api/fhir/Patient/{id}/$everything bundle.html _format id*
        /api/fhir/metadata capabilitystatement.html _format
        """;
    String hl7 = "(?s).*https://hl7\\.org/fhir/R4/(\\w+\\.html).*";
    List<String> described = new ArrayList<>();
    for (String path : doc.$paths().names().stream().sorted().toList()) {
      OperationObject get = doc.$paths().get(path).get();
      JsonMap<MediaTypeObject> content = get.responses().get("200").content();
      assertEquals(List.of("application/fhir+json"), content.names(), path);
      SchemaObject schema = content.get("application/fhir+json").schema();
      assertTrue(!schema.isRef() && schema.isObjectType(), path + " " + schema);
      StringJoiner line = new StringJoiner(" ").add(path);
      line.add(String.valueOf(get.description()).replaceAll(hl7, "$1"));
      get.parameters(In.query).stream().map(ParameterObject::name).sorted().forEach(line::add);
      get.parameters(In.path).forEach(id -> line.add(id.name() + (id.required() ? "*" : "?")));
      described.add(line.toString());
    }
    assertEquals(operations.strip().lines().toList(), described);
    List<String> schemas = doc.components().schemas().names();
    assertEquals(List.of(), schemas.stream().filter(name -> name.startsWith("Fhir")).toList());
  }

  @Test
  void metadataAcceptsFormatAndRejectsOtherParameters() {
    for (String f : "json,application/json,application/fhir+json,application/fhir json".split(","))
      assertEquals(FHIRVersion._4_0_1, capabilities("?_format=" + f).getFhirVersion());
    assertIssue(GET, METADATA + "?_format=xml", BAD_REQUEST, "Invalid parameter '_format'");
    assertIssue(GET, METADATA + "?foo=1", BAD_REQUEST, "Invalid parameter 'foo'");
    assertIssue(GET, METADATA + "?_format=json&foo=1", BAD_REQUEST, "Invalid parameter 'foo'");
  }

  @Test
  void unmappedResourceTypesReturnNotSupported() {
    deleteAllMappings();
    for (String request : BRIDGED_TYPE_REQUESTS.strip().split("\\s+"))
      assertIssue(GET, BASE + request, NOT_IMPLEMENTED, request.split("[/?]")[1]);
  }

  @Test
  void unbridgedTypeAndWriteMethodsReturnNotSupported() {
    for (String path : List.of("/Condition", "/Condition/x", "/Condition?patient=dUE514NMOlo"))
      assertIssue(GET, BASE + path, NOT_IMPLEMENTED, "Resource type Condition is not supported");
    for (HttpMethod method : List.of(POST, PUT, PATCH, DELETE))
      for (String path : List.of("", "/Patient", "/Patient/dUE514NMOlo", "/metadata"))
        assertIssue(method, BASE + path, NOT_IMPLEMENTED, "Write interactions are not supported");
  }

  @Test
  void unknownPathReturnsNotFound() {
    for (var p : ",/NotAResource,/NotAResource/abc,/Patient/dUE514NMOlo/extra/segment".split(","))
      assertIssue(GET, BASE + p, NOT_FOUND, "The requested resource was not found");
    List<String> accepts = List.of("text/html", "application/x-yaml", "application/json", "*/*");
    for (String path : OPENAPI_PATHS.split(" ")) {
      assertAll(path, () -> assertNotFound(GET(path)));
      for (String accept : accepts)
        assertAll(path + " Accept " + accept, () -> assertNotFound(GET(path, Accept(accept))));
    }
  }

  private void deleteAllMappings() {
    manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete);
    dbmsManager.clearSession();
  }

  private CapabilityStatement capabilities(String query) {
    return parseOk(GET(METADATA + query), CapabilityStatement.class);
  }

  private static List<String> describeResources(CapabilityStatement statement) {
    List<String> resources = new ArrayList<>();
    for (var resource : statement.getRestFirstRep().getResource()) {
      StringJoiner line = new StringJoiner(" ").add(resource.getType());
      resource.getInteraction().forEach(i -> line.add(i.getCode().toCode()));
      resource.getSearchParam().forEach(p -> line.add(p.getName() + ":" + p.getType().toCode()));
      line.add("|");
      resource.getOperation().forEach(o -> line.add(o.getName() + "=" + o.getDefinition()));
      resources.add(line.toString());
    }
    return resources;
  }

  private void assertIssue(HttpMethod method, String path, HttpStatus status, String text) {
    IssueType code =
        Map.of(BAD_REQUEST, IssueType.INVALID, NOT_FOUND, IssueType.NOTFOUND)
            .getOrDefault(status, IssueType.NOTSUPPORTED);
    String body = method == GET || method == DELETE ? "" : "{'resourceType':'Patient'}";
    HttpResponse reply = perform(method, path, Body(body));
    assertAll(method + " " + path, () -> assertOutcome(reply, status, code, d -> d.contains(text)));
  }
}
