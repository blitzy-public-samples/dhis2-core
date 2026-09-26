/*
 * Copyright (c) 2004-2022, University of Oslo
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
package org.hisp.dhis.fhir.service;

import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.IDENTIFIER_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_HEIGHT_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.entries;
import static org.hisp.dhis.fhir.FhirTestFixtures.resolved;
import static org.hisp.dhis.fhir.FhirTestFixtures.uid;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.ATTRIBUTE;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.FhirTestFixtures.Entry;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.mapping.FhirTargetField;
import org.hisp.dhis.fhir.search.FhirSearchParameters;
import org.hisp.dhis.setting.SystemSettings;
import org.hisp.dhis.setting.SystemSettingsProvider;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementKind;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceOperationComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceSearchParamComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.ResourceInteractionComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests that {@link FhirCapabilityStatementService} derives the CapabilityStatement of {@code GET
 * /api/fhir/metadata} from the usable resource mappings: one resource per mapped type with the
 * {@code read} and {@code search-type} interactions, the search parameters the configured targets
 * support, the {@code Patient/$everything} operation, the fixed fields, the date of the latest
 * mapping update, and a query that accepts only {@code _format}. Every produced statement passes
 * FHIR R4 validation.
 */
@ExtendWith(MockitoExtension.class)
class FhirCapabilityStatementServiceTest {
  private static final String BASE = "https://fhir.example.org:8443/dhis";
  private static final Instant T1 = Instant.parse("2024-05-01T08:00:00Z");
  private static final Instant T2 = Instant.parse("2024-06-01T12:30:00Z");
  private static final String TRACKED_ENTITY = uid();
  private static final String PROGRAM = uid();
  private static final String STAGE = uid();
  private static final String TEA_IDENTIFIER = uid();
  private static final String TEA_FAMILY = uid();
  private static final String TEA_GIVEN = uid();

  @Mock private FhirResourceMappingService mappingService;

  @Mock private SystemSettingsProvider settingsProvider;

  private FhirCapabilityStatementService service;

  @BeforeEach
  void setUp() {
    lenient().when(settingsProvider.getCurrentSettings()).thenReturn(SystemSettings.of(Map.of()));
    service =
        new FhirCapabilityStatementService(
            mappingService, new FhirSearchParameters(settingsProvider));
  }

  @Test
  void listsOnlyMappedResourceTypesWithReadAndSearchInteractions() {
    when(mappingService.resolveAll()).thenReturn(List.of(observation(T1), patient(T1)));

    CapabilityStatement statement = capabilities(request());

    assertEquals(List.of("Patient", "Observation"), resourceTypes(statement));
    for (CapabilityStatementRestResourceComponent resource : rest(statement).getResource()) {
      assertEquals(
          List.of(TypeRestfulInteraction.READ, TypeRestfulInteraction.SEARCHTYPE),
          resource.getInteraction().stream().map(ResourceInteractionComponent::getCode).toList(),
          "interactions of " + resource.getType());
    }
  }

  @Test
  void searchParametersFollowConfiguredTargets() {
    when(mappingService.resolveAll()).thenReturn(allMappings());

    CapabilityStatement statement = capabilities(request());

    assertEquals(Set.of("_id", "identifier", "family"), searchParamNames(statement, "Patient"));
    assertEquals(Set.of("_id", "patient", "subject"), searchParamNames(statement, "Encounter"));
    assertEquals(Set.of("_id", "patient"), searchParamNames(statement, "Immunization"));
    assertEquals(
        Set.of("_id", "patient", "subject", "code"), searchParamNames(statement, "Observation"));
    for (CapabilityStatementRestResourceComponent resource : rest(statement).getResource()) {
      for (CapabilityStatementRestResourceSearchParamComponent param : resource.getSearchParam()) {
        assertEquals(
            FhirSearchParameters.typeOf(param.getName()),
            param.getType(),
            resource.getType() + " search parameter " + param.getName());
      }
    }
  }

  @Test
  void patientDeclaresEverythingOperation() {
    when(mappingService.resolveAll()).thenReturn(allMappings());

    CapabilityStatement statement = capabilities(request());

    List<CapabilityStatementRestResourceOperationComponent> operations =
        resource(statement, "Patient").getOperation();
    assertEquals(1, operations.size());
    assertEquals("everything", operations.get(0).getName());
    assertEquals(
        "http://hl7.org/fhir/OperationDefinition/Patient-everything",
        operations.get(0).getDefinition());
    for (String type : List.of("Encounter", "Immunization", "Observation")) {
      assertTrue(resource(statement, type).getOperation().isEmpty(), type + " has no operation");
    }
  }

  @Test
  void fixedFieldsAreSet() {
    when(mappingService.resolveAll()).thenReturn(List.of(patient(T1)));

    CapabilityStatement statement = capabilities(request());

    assertEquals(Enumerations.PublicationStatus.ACTIVE, statement.getStatus());
    assertEquals(CapabilityStatementKind.INSTANCE, statement.getKind());
    assertEquals(Enumerations.FHIRVersion._4_0_1, statement.getFhirVersion());
    assertEquals(List.of("json"), statement.getFormat().stream().map(CodeType::getValue).toList());
    assertEquals("DHIS2 FHIR R4 read-only API", statement.getImplementation().getDescription());
    assertEquals(BASE + "/api/fhir", statement.getImplementation().getUrl());
    assertEquals(1, statement.getRest().size());
    assertEquals(RestfulCapabilityMode.SERVER, rest(statement).getMode());
  }

  @Test
  void dateIsLatestMappingUpdateOrRequestTime() {
    when(mappingService.resolveAll())
        .thenReturn(List.of(patient(T2), encounter(null), observation(T1)));

    assertEquals(Date.from(T2), capabilities(request()).getDate());

    when(mappingService.resolveAll()).thenReturn(List.of());
    Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    CapabilityStatement unmapped = capabilities(request());
    Instant after = Instant.now();

    Instant date = unmapped.getDate().toInstant();
    assertFalse(date.isBefore(before), date + " is not before " + before);
    assertFalse(date.isAfter(after), date + " is not after " + after);
    assertTrue(rest(unmapped).getResource().isEmpty());
  }

  @Test
  void acceptsFormatAndRejectsOtherParameters() {
    when(mappingService.resolveAll()).thenReturn(List.of(patient(T1)));

    for (String format : List.of("json", "application/json", "application/fhir+json")) {
      CapabilityStatement statement = capabilities(request("_format", format));
      assertEquals(List.of("Patient"), resourceTypes(statement), "_format=" + format);
    }

    assertInvalid("_format", request("_format", "xml"));
    assertInvalid("foo", request("foo", "bar"));
    MockHttpServletRequest repeated = request();
    repeated.addParameter("_format", "json", "json");
    assertInvalid("_format", repeated);
  }

  /**
   * Asserts that the request is rejected with {@code 400 invalid} naming the parameter, before any
   * mapping is resolved.
   */
  private void assertInvalid(String parameter, MockHttpServletRequest request) {
    clearInvocations(mappingService);
    FhirApiException exception =
        assertThrows(FhirApiException.class, () -> service.capabilities(request));
    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    assertEquals(IssueType.INVALID, exception.getIssueType());
    assertTrue(
        exception.getDiagnostics().contains("'" + parameter + "'"), exception.getDiagnostics());
    verify(mappingService, never()).resolveAll();
  }

  /** Builds the statement and asserts that it is valid FHIR R4. */
  private CapabilityStatement capabilities(MockHttpServletRequest request) {
    CapabilityStatement statement = service.capabilities(request);
    FhirR4Validation.assertValid(statement);
    return statement;
  }

  /** Builds a request to {@code GET BASE/api/fhir/metadata} with the given name-value pairs. */
  private static MockHttpServletRequest request(String... nameValuePairs) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dhis/api/fhir/metadata");
    request.setScheme("https");
    request.setServerName("fhir.example.org");
    request.setServerPort(8443);
    request.setContextPath("/dhis");
    for (int i = 0; i < nameValuePairs.length; i += 2) {
      request.addParameter(nameValuePairs[i], nameValuePairs[i + 1]);
    }
    return request;
  }

  private static CapabilityStatementRestComponent rest(CapabilityStatement statement) {
    return statement.getRest().get(0);
  }

  private static List<String> resourceTypes(CapabilityStatement statement) {
    return rest(statement).getResource().stream()
        .map(CapabilityStatementRestResourceComponent::getType)
        .toList();
  }

  private static CapabilityStatementRestResourceComponent resource(
      CapabilityStatement statement, String type) {
    List<CapabilityStatementRestResourceComponent> matching =
        rest(statement).getResource().stream().filter(r -> type.equals(r.getType())).toList();
    assertEquals(1, matching.size(), "resources of type " + type);
    return matching.get(0);
  }

  private static Set<String> searchParamNames(CapabilityStatement statement, String type) {
    return resource(statement, type).getSearchParam().stream()
        .map(CapabilityStatementRestResourceSearchParamComponent::getName)
        .collect(Collectors.toSet());
  }

  private static List<ResolvedMapping> allMappings() {
    return List.of(patient(T1), encounter(T1), immunization(T1), observation(T1));
  }

  /**
   * A Patient mapping with an identifier, a family name and a given name whose attribute blocks the
   * {@code sw} operator; birth date and gender are unmapped.
   */
  private static ResolvedMapping patient(Instant lastUpdated) {
    return mapping(
        FhirResourceType.PATIENT,
        lastUpdated,
        Map.of(TEA_GIVEN, Set.of(QueryOperator.SW)),
        Entry.field(FhirTargetField.PATIENT_IDENTIFIER, ATTRIBUTE, TEA_IDENTIFIER)
            .system(IDENTIFIER_SYSTEM),
        Entry.field(FhirTargetField.PATIENT_FAMILY_NAME, ATTRIBUTE, TEA_FAMILY),
        Entry.field(FhirTargetField.PATIENT_GIVEN_NAME, ATTRIBUTE, TEA_GIVEN));
  }

  private static ResolvedMapping encounter(Instant lastUpdated) {
    return mapping(
        FhirResourceType.ENCOUNTER,
        lastUpdated,
        Map.of(),
        Entry.constant(
            FhirTargetField.ENCOUNTER_CLASS, ENCOUNTER_CLASS_SYSTEM, ENCOUNTER_CLASS_CODE, null));
  }

  private static ResolvedMapping immunization(Instant lastUpdated) {
    return mapping(
        FhirResourceType.IMMUNIZATION,
        lastUpdated,
        Map.of(),
        Entry.field(FhirTargetField.IMMUNIZATION_ADMINISTERED, DATA_ELEMENT, uid()),
        Entry.constant(FhirTargetField.IMMUNIZATION_VACCINE_CODE, CVX_SYSTEM, CVX_CODE, null));
  }

  private static ResolvedMapping observation(Instant lastUpdated) {
    return mapping(
        FhirResourceType.OBSERVATION,
        lastUpdated,
        Map.of(),
        Entry.field(FhirTargetField.OBSERVATION_VALUE, DATA_ELEMENT, uid())
            .system(LOINC_SYSTEM)
            .code(LOINC_BODY_HEIGHT_CODE));
  }

  /**
   * Builds a resolved mapping on one tracked entity type; event-derived mappings share one program
   * and program stage.
   */
  private static ResolvedMapping mapping(
      FhirResourceType type,
      Instant lastUpdated,
      Map<String, Set<QueryOperator>> blockedSearchOperators,
      Entry... entries) {
    boolean eventDerived = type.isEventDerived();
    return resolved(
        uid(),
        lastUpdated,
        type,
        TRACKED_ENTITY,
        eventDerived ? PROGRAM : null,
        eventDerived ? STAGE : null,
        entries(entries),
        Map.of(),
        blockedSearchOperators,
        Map.of());
  }
}
