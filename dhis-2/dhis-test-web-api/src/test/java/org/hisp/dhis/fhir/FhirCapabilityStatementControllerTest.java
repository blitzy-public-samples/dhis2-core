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

import static org.hisp.dhis.http.HttpClientAdapter.Body;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringJoiner;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.fhir.mapping.FhirResourceMapping;
import org.hisp.dhis.http.HttpMethod;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.test.config.H2DhisConfigurationProvider;
import org.hisp.dhis.test.webapi.H2ControllerIntegrationTestBase;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementKind;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Enumerations.FHIRVersion;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests, on H2 with {@code fhir.api.enabled} on, the CapabilityStatement at {@code
 * /api/fhir/metadata}, the {@code 501 not-supported} answers for resource types without a usable
 * mapping, for unbridged R4 types and for write methods, and the {@code 404 not-found} answers for
 * unknown paths. Every test runs in a transaction that is rolled back.
 */
@Transactional
@ContextConfiguration(classes = FhirCapabilityStatementControllerTest.FhirApiEnabledConfig.class)
class FhirCapabilityStatementControllerTest extends H2ControllerIntegrationTestBase {

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

  private static final String FHIR_BASE = "/api/fhir";
  private static final String METADATA_PATH = FHIR_BASE + "/metadata";
  private static final String FHIR_JSON = "application/fhir+json";
  private static final String OBSERVATION_READ =
      FHIR_BASE + "/Observation/TvctPPhpD8z-D9PbzJY8bJM-GieVkTxp4HH";

  /** Reads, malformed-id reads, searches and {@code $everything}, by resource type. */
  private static final Map<String, List<String>> BRIDGED_TYPE_REQUESTS =
      Map.of(
          "Patient",
          List.of(
              "/Patient/dUE514NMOlo",
              "/Patient/bad",
              "/Patient/bad?foo=1",
              "/Patient",
              "/Patient?family=rain&unknown=1",
              "/Patient/dUE514NMOlo/$everything"),
          "Encounter",
          List.of(
              "/Encounter/TvctPPhpD8z-D9PbzJY8bJM",
              "/Encounter/bad",
              "/Encounter",
              "/Encounter?patient=dUE514NMOlo"),
          "Immunization",
          List.of(
              "/Immunization/TvctPPhpD8z-D9PbzJY8bJM-DATAEL00001",
              "/Immunization/x-y",
              "/Immunization?patient=dUE514NMOlo"),
          "Observation",
          List.of(
              "/Observation/TvctPPhpD8z-D9PbzJY8bJM-GieVkTxp4HH",
              "/Observation/bad",
              "/Observation?patient=dUE514NMOlo"));

  @Autowired private TestSetup testSetup;

  @Autowired private DhisConfigurationProvider config;

  @BeforeEach
  void fhirApiIsEnabled() {
    assertTrue(config.isEnabled(ConfigurationKey.FHIR_API_ENABLED));
  }

  @Test
  void capabilityStatementListsOnlyMappedResourcesAndParameters() throws IOException {
    deleteAllMappings();
    testSetup.importMetadata();
    testSetup.importMetadata("fhir/fhir_resource_mappings.json");
    manager.flush();
    manager.clear();

    CapabilityStatement statement = readCapabilityStatement("");

    assertEquals(PublicationStatus.ACTIVE, statement.getStatus());
    assertEquals(CapabilityStatementKind.INSTANCE, statement.getKind());
    assertEquals(FHIRVersion._4_0_1, statement.getFhirVersion());
    assertEquals(List.of("json"), statement.getFormat().stream().map(CodeType::getValue).toList());
    assertNotNull(statement.getDate());
    assertEquals("DHIS2 FHIR R4 read-only API", statement.getImplementation().getDescription());
    String url = statement.getImplementation().getUrl();
    assertTrue(url != null && url.endsWith(FHIR_BASE), "implementation.url " + url);
    assertEquals(1, statement.getRest().size());
    assertEquals(RestfulCapabilityMode.SERVER, statement.getRestFirstRep().getMode());

    List<String> expected =
        new ArrayList<>(
            List.of(
                "Patient _id:token identifier:token family:string given:string"
                    + " | everything=http://hl7.org/fhir/OperationDefinition/Patient-everything",
                "Encounter _id:token patient:reference subject:reference |",
                "Immunization _id:token patient:reference |",
                "Observation _id:token patient:reference subject:reference code:token |"));
    assertEquals(expected, describeResources(statement));

    FhirResourceMapping observation = manager.get(FhirResourceMapping.class, "FhirMapObs1");
    assertNotNull(observation);
    manager.delete(observation);
    manager.flush();
    manager.clear();

    expected.remove(3);
    assertEquals(expected, describeResources(readCapabilityStatement("")));
    assertOutcome(
        HttpMethod.GET,
        OBSERVATION_READ,
        HttpStatus.NOT_IMPLEMENTED,
        IssueType.NOTSUPPORTED,
        "Observation");
  }

  @Test
  void metadataAcceptsFormatAndRejectsOtherParameters() {
    // The mock request builder percent-encodes the query itself, so '+' is written literally.
    for (String format : List.of("json", "application/json", "application/fhir+json")) {
      assertEquals(
          FHIRVersion._4_0_1, readCapabilityStatement("?_format=" + format).getFhirVersion());
    }

    Map<String, String> rejected = new LinkedHashMap<>();
    rejected.put("?_format=xml", "_format");
    rejected.put("?_format=application/fhir+xml", "_format");
    rejected.put("?_format=json&_format=json", "_format");
    rejected.put("?foo=1", "foo");
    rejected.put("?_format=json&foo=1", "foo");
    rejected.put("?_count=10", "_count");
    rejected.forEach(
        (query, parameter) ->
            assertOutcome(
                HttpMethod.GET,
                METADATA_PATH + query,
                HttpStatus.BAD_REQUEST,
                IssueType.INVALID,
                "Invalid parameter '" + parameter + "'"));
  }

  @Test
  void unmappedResourceTypesReturnNotSupported() {
    deleteAllMappings();

    BRIDGED_TYPE_REQUESTS.forEach(
        (type, requests) ->
            requests.forEach(
                request ->
                    assertOutcome(
                        HttpMethod.GET,
                        FHIR_BASE + request,
                        HttpStatus.NOT_IMPLEMENTED,
                        IssueType.NOTSUPPORTED,
                        type)));
  }

  @Test
  void unbridgedTypeAndWriteMethodsReturnNotSupported() {
    for (String path :
        List.of(
            FHIR_BASE + "/Condition",
            FHIR_BASE + "/Condition/x",
            FHIR_BASE + "/Condition?patient=dUE514NMOlo",
            "/api/44/fhir/Condition")) {
      assertOutcome(
          HttpMethod.GET,
          path,
          HttpStatus.NOT_IMPLEMENTED,
          IssueType.NOTSUPPORTED,
          "Resource type Condition is not supported");
    }

    for (HttpMethod method :
        List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
      for (String path :
          List.of(
              FHIR_BASE,
              FHIR_BASE + "/Patient",
              FHIR_BASE + "/Patient/dUE514NMOlo",
              OBSERVATION_READ,
              METADATA_PATH)) {
        assertOutcome(
            method,
            path,
            HttpStatus.NOT_IMPLEMENTED,
            IssueType.NOTSUPPORTED,
            "Write interactions are not supported");
      }
    }
  }

  @Test
  void unknownPathReturnsNotFound() {
    for (String path :
        List.of(
            FHIR_BASE,
            FHIR_BASE + "/NotAResource",
            FHIR_BASE + "/NotAResource/abc",
            FHIR_BASE + "/patient",
            FHIR_BASE + "/Patient/dUE514NMOlo/extra/segment",
            FHIR_BASE + "/Patient/dUE514NMOlo/$unknown",
            "/api/44/fhir/NotAResource")) {
      assertOutcome(
          HttpMethod.GET,
          path,
          HttpStatus.NOT_FOUND,
          IssueType.NOTFOUND,
          "The requested resource was not found");
    }
  }

  /** Deletes every stored FHIR resource mapping within the test transaction. */
  private void deleteAllMappings() {
    manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete);
    manager.flush();
    manager.clear();
  }

  /** Requests the CapabilityStatement, asserts {@code 200} FHIR JSON and parses it strictly. */
  private CapabilityStatement readCapabilityStatement(String query) {
    HttpResponse response = GET(METADATA_PATH + query);
    assertEquals(HttpStatus.OK, response.status(), query);
    assertFhirJson("GET " + METADATA_PATH + query, response);
    return parse(response, CapabilityStatement.class);
  }

  /**
   * Describes each resource of the single {@code rest} entry, in order, as {@code "Type name:type
   * ... | operation=definition ..."}, after asserting its interactions are exactly {@code read} and
   * {@code search-type}.
   */
  private static List<String> describeResources(CapabilityStatement statement) {
    List<String> resources = new ArrayList<>();
    for (CapabilityStatementRestResourceComponent resource :
        statement.getRestFirstRep().getResource()) {
      assertEquals(
          List.of(TypeRestfulInteraction.READ, TypeRestfulInteraction.SEARCHTYPE),
          resource.getInteraction().stream().map(ResourceInteractionComponent::getCode).toList(),
          resource.getType());
      StringJoiner description = new StringJoiner(" ").add(resource.getType());
      resource
          .getSearchParam()
          .forEach(p -> description.add(p.getName() + ":" + p.getType().toCode()));
      description.add("|");
      resource.getOperation().forEach(o -> description.add(o.getName() + "=" + o.getDefinition()));
      resources.add(description.toString());
    }
    return resources;
  }

  /**
   * Sends a request (with a JSON body unless it is a {@code GET} or {@code DELETE}) and asserts a
   * FHIR JSON {@code OperationOutcome} with the status and a single {@code error} issue with the
   * code and diagnostics containing the given text.
   */
  private void assertOutcome(
      HttpMethod method, String path, HttpStatus status, IssueType code, String diagnostics) {
    String request = method + " " + path;
    HttpResponse response =
        method == HttpMethod.GET || method == HttpMethod.DELETE
            ? perform(method, path)
            : perform(method, path, Body("{'resourceType':'Patient'}"));
    assertFhirJson(request, response);
    OperationOutcome outcome = parse(response, OperationOutcome.class);
    assertEquals(1, outcome.getIssue().size(), request);
    OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
    String actual = issue.getDiagnostics();
    assertEquals(status, response.status(), () -> request + ": " + actual);
    assertEquals(IssueSeverity.ERROR, issue.getSeverity(), request);
    assertEquals(code, issue.getCode(), request);
    assertTrue(actual != null && actual.contains(diagnostics), () -> request + ": " + actual);
  }

  /** Asserts that the response carries the FHIR JSON content type. */
  private static void assertFhirJson(String request, HttpResponse response) {
    String contentType = response.getContentType();
    assertTrue(
        contentType != null
            && contentType.startsWith(FhirResourceSerializer.FHIR_JSON_CONTENT_TYPE),
        () -> request + " answered " + response.status() + " with Content-Type " + contentType);
  }

  /** Parses the FHIR JSON body strictly, failing on unknown elements and invalid values. */
  private static <T extends Resource> T parse(HttpResponse response, Class<T> type) {
    return FhirContext.forR4Cached()
        .newJsonParser()
        .setParserErrorHandler(new StrictErrorHandler())
        .parseResource(type, response.content(FHIR_JSON));
  }
}
