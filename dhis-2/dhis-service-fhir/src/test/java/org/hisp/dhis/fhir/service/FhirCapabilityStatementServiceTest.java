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

import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.search.FhirSearchParameters;
import org.hisp.dhis.setting.*;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CapabilityStatement.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class FhirCapabilityStatementServiceTest {
  private static final String BASE = "https://fhir.example.org:8443/dhis";
  private static final Instant T1 = Instant.parse("2024-05-01T08:00:00Z");
  private static final Instant T2 = Instant.parse("2024-06-01T12:30:00Z");
  private static final String TEA_GIVEN = uid();
  private static final String TEA_BIRTH_DATE = uid();
  private static final String TEA_GENDER = uid();
  private static final Entry[] PATIENT_ENTRIES = {
    Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, uid()).system(IDENTIFIER_SYSTEM),
    Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, uid()),
    Entry.field(PATIENT_GIVEN_NAME, ATTRIBUTE, TEA_GIVEN),
    Entry.field(PATIENT_BIRTH_DATE, ATTRIBUTE, TEA_BIRTH_DATE),
    Entry.field(PATIENT_GENDER, ATTRIBUTE, TEA_GENDER).valueMap(Map.of("M", "male"))
  };
  @Mock private FhirResourceMappingService mappingService;
  @Mock private SystemSettingsProvider settingsProvider;
  private FhirCapabilityStatementService service;

  @Test
  void listsMappedTypesWithInteractionsAndSearchParametersFollowingConfiguredTargets() {
    lenient().when(settingsProvider.getCurrentSettings()).thenReturn(SystemSettings.of(Map.of()));
    var parameters = new FhirSearchParameters(settingsProvider);
    service = new FhirCapabilityStatementService(mappingService, parameters);
    var listed = rest(statementFor(List.of(observation(T1), patient(T1)))).getResource();
    assertEquals(List.of("Patient", "Observation"), listed.stream().map(r -> r.getType()).toList());
    for (CapabilityStatementRestResourceComponent resource : listed) {
      var codes = resource.getInteraction().stream().map(ResourceInteractionComponent::getCode);
      assertEquals(List.of(READ, SEARCHTYPE), codes.toList(), resource.getType());
    }
    CapabilityStatement statement = statementFor(List.of(patient(T1)));
    assertEquals(Enumerations.PublicationStatus.ACTIVE, statement.getStatus());
    assertEquals(CapabilityStatementKind.INSTANCE, statement.getKind());
    assertEquals(Enumerations.FHIRVersion._4_0_1, statement.getFhirVersion());
    assertEquals(List.of("json"), statement.getFormat().stream().map(CodeType::getValue).toList());
    assertEquals("DHIS2 FHIR R4 read-only API", statement.getImplementation().getDescription());
    assertEquals(BASE + "/api/fhir", statement.getImplementation().getUrl(), "X-Forwarded-*");
    assertEquals(1, statement.getRest().size());
    assertEquals(RestfulCapabilityMode.SERVER, rest(statement).getMode());
    List<ResolvedMapping> mappings = List.of(patient(T2), encounter(null), observation(T1));
    assertEquals(Date.from(T2), statementFor(mappings).getDate());
    Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    CapabilityStatement unmapped = statementFor(List.of());
    Instant after = Instant.now();
    Instant date = unmapped.getDate().toInstant();
    assertFalse(date.isBefore(before) || date.isAfter(after), date + " outside the request");
    assertTrue(rest(unmapped).getResource().isEmpty());
    Entry administered = Entry.field(IMMUNIZATION_ADMINISTERED, DATA_ELEMENT, uid());
    Entry vaccine = Entry.constant(IMMUNIZATION_VACCINE_CODE, CVX_SYSTEM, CVX_CODE, null);
    var immunization = mapping(IMMUNIZATION, T1, Map.of(), administered, vaccine);
    var full = statementFor(List.of(patient(T1), encounter(T1), immunization, observation(T1)));
    assertEquals("_id:token identifier:token family:string", params(full, "Patient"));
    String required = " required:_id required:patient";
    assertEquals("_id:token patient:reference" + required, params(full, "Immunization"));
    String subject = "_id:token patient:reference subject:reference";
    assertEquals(subject + required + " required:subject", params(full, "Encounter"));
    String coded = " code:token required:_id+optional:code required:patient+optional:code";
    assertEquals(subject + coded + " required:subject+optional:code", params(full, "Observation"));
    String url = "http://hl7.org/fhir/StructureDefinition/capabilitystatement-search-parameter-";
    assertEquals(url + "combination", resource(full, "Encounter").getExtension().get(0).getUrl());
    String text = "_id:token identifier:token family:string given:string";
    String all = text + " birthdate:date gender:token";
    var configured = mapping(PATIENT, T1, Map.of(), PATIENT_ENTRIES);
    assertEquals(all, params(statementFor(List.of(configured)), "Patient"));
    Set<QueryOperator> eq = Set.of(QueryOperator.EQ);
    var blocked = mapping(PATIENT, T1, Map.of(TEA_BIRTH_DATE, eq, TEA_GENDER, eq), PATIENT_ENTRIES);
    assertEquals(text, params(statementFor(List.of(blocked)), "Patient"));
    var operations = resource(full, "Patient").getOperation().stream();
    assertEquals(
        List.of("everything http://hl7.org/fhir/OperationDefinition/Patient-everything"),
        operations.map(o -> o.getName() + " " + o.getDefinition()).toList());
    List.of("Encounter", "Immunization", "Observation")
        .forEach(type -> assertTrue(resource(full, type).getOperation().isEmpty(), type));
  }

  private CapabilityStatement statementFor(List<ResolvedMapping> mappings) {
    when(mappingService.resolveAll()).thenReturn(mappings);
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/dhis/api/fhir/metadata");
    request.setScheme("https");
    request.addHeader("Host", "fhir.example.org:8443");
    request.setContextPath("/dhis");
    request.addHeader("X-Forwarded-Host", "evil.example/#");
    request.addHeader("X-Forwarded-Proto", "http");
    CapabilityStatement statement = service.capabilities(request);
    FhirR4Validation.assertValid(statement);
    return statement;
  }

  private static CapabilityStatementRestComponent rest(CapabilityStatement statement) {
    return statement.getRest().get(0);
  }

  private static CapabilityStatementRestResourceComponent resource(
      CapabilityStatement statement, String type) {
    var matching =
        rest(statement).getResource().stream().filter(r -> type.equals(r.getType())).toList();
    assertEquals(1, matching.size(), type);
    return matching.get(0);
  }

  private static String params(CapabilityStatement statement, String type) {
    var search = resource(statement, type).getSearchParam().stream();
    var names = search.map(p -> p.getName() + ":" + p.getTypeElement().getValueAsString());
    var combos = resource(statement, type).getExtension().stream().map(Extension::getExtension);
    var parts = combos.map(c -> c.stream().map(e -> e.getUrl() + ":" + e.getValue()).toList());
    return String.join(" ", Stream.concat(names, parts.map(p -> String.join("+", p))).toList());
  }

  private static ResolvedMapping patient(Instant lastUpdated) {
    var blocked = Map.of(TEA_GIVEN, Set.of(QueryOperator.SW));
    return mapping(PATIENT, lastUpdated, blocked, Arrays.copyOf(PATIENT_ENTRIES, 3));
  }

  private static ResolvedMapping encounter(Instant lastUpdated) {
    var amb = Entry.constant(ENCOUNTER_CLASS, ENCOUNTER_CLASS_SYSTEM, ENCOUNTER_CLASS_CODE, null);
    return mapping(ENCOUNTER, lastUpdated, Map.of(), amb);
  }

  private static ResolvedMapping observation(Instant lastUpdated) {
    Entry value = Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, uid()).system(LOINC_SYSTEM);
    return mapping(OBSERVATION, lastUpdated, Map.of(), value.code(LOINC_BODY_HEIGHT_CODE));
  }

  private static ResolvedMapping mapping(
      FhirResourceType type, Instant updated, Map<String, Set<QueryOperator>> blocked, Entry... e) {
    String program = type.isEventDerived() ? PROGRAM : null;
    String stage = type.isEventDerived() ? STAGE : null;
    return new ResolvedMapping(
        uid(), type, TE_TYPE, program, stage, entries(e), Map.of(), blocked, Map.of(), updated);
  }
}
