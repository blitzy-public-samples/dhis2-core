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
package org.hisp.dhis.fhir.mapping;

import static org.hisp.dhis.fhir.FhirTestFixtures.BODY_WEIGHT_UNIT;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.IDENTIFIER_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_WEIGHT_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.dataElement;
import static org.hisp.dhis.fhir.FhirTestFixtures.lookup;
import static org.hisp.dhis.fhir.FhirTestFixtures.mapping;
import static org.hisp.dhis.fhir.FhirTestFixtures.program;
import static org.hisp.dhis.fhir.FhirTestFixtures.programStage;
import static org.hisp.dhis.fhir.FhirTestFixtures.trackedEntityAttribute;
import static org.hisp.dhis.fhir.FhirTestFixtures.trackedEntityType;
import static org.hisp.dhis.fhir.FhirTestFixtures.uid;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.ENCOUNTER;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.IMMUNIZATION;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.OBSERVATION;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.PATIENT;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.ATTRIBUTE;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.ENCOUNTER_CLASS;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_ADMINISTERED;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_VACCINE_CODE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.OBSERVATION_VALUE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_FAMILY_NAME;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_GIVEN_NAME;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_IDENTIFIER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.feedback.ErrorCode;
import org.hisp.dhis.feedback.ErrorReport;
import org.hisp.dhis.fhir.FhirTestFixtures.Entry;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.program.ProgramType;
import org.hisp.dhis.schema.Schema;
import org.hisp.dhis.schema.SchemaDescriptor;
import org.hisp.dhis.schema.SchemaService;
import org.hisp.dhis.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.trackedentity.TrackedEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests of {@link FhirResourceMappingService}. Stored mappings are valid unless a test rejects
 * them with {@link #rejects}; attribute and data element UIDs resolve to the fixture objects.
 */
@ExtendWith(MockitoExtension.class)
class FhirResourceMappingServiceTest {
  @Mock private FhirResourceMappingStore store;

  @Mock private FhirResourceMappingValidator validator;

  @Mock private SchemaService schemaService;

  @Mock private IdentifiableObjectManager manager;

  private FhirResourceMappingService service;

  private TrackedEntityAttribute textAttribute;
  private TrackedEntityAttribute integerAttribute;
  private TrackedEntityType person;
  private DataElement numberDataElement;
  private DataElement booleanDataElement;
  private Program program;
  private ProgramStage stageA;
  private ProgramStage stageB;

  @BeforeEach
  void setUp() {
    textAttribute = trackedEntityAttribute(uid(), ValueType.TEXT);
    textAttribute.setMinCharactersToSearch(3);
    textAttribute.setBlockedSearchOperators(EnumSet.of(QueryOperator.SW));
    integerAttribute = trackedEntityAttribute(uid(), ValueType.INTEGER);
    person = trackedEntityType(uid(), textAttribute, integerAttribute);
    numberDataElement = dataElement(uid(), ValueType.NUMBER);
    booleanDataElement = dataElement(uid(), ValueType.BOOLEAN);
    program = program(uid(), ProgramType.WITH_REGISTRATION, person);
    stageA = programStage(uid(), program, numberDataElement, booleanDataElement);
    stageB = programStage(uid(), program, numberDataElement, booleanDataElement);

    service = new FhirResourceMappingService(store, validator, schemaService, manager);

    lenient().when(validator.validate(any(), anyCollection())).thenReturn(List.of());
    BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> metadata =
        lookup(textAttribute, integerAttribute, numberDataElement, booleanDataElement);
    lenient()
        .when(manager.getNoAcl(any(), anyString()))
        .thenAnswer(
            invocation -> metadata.apply(invocation.getArgument(0), invocation.getArgument(1)));
  }

  @Test
  void registersSchemaDescriptorOnStartup() {
    service.init();

    ArgumentCaptor<SchemaDescriptor> descriptor = ArgumentCaptor.forClass(SchemaDescriptor.class);
    verify(schemaService).register(descriptor.capture());
    assertInstanceOf(FhirResourceMappingSchemaDescriptor.class, descriptor.getValue());
    Schema schema = descriptor.getValue().getSchema();
    assertEquals(FhirResourceMapping.class, schema.getKlass());
    assertEquals("fhirResourceMapping", schema.getSingular());
    assertEquals("fhirResourceMappings", schema.getPlural());
    verifyNoInteractions(store, validator, manager);
  }

  @Test
  void invalidStoredMappingIsIgnored() {
    FhirResourceMapping valid = encounter(uid(), stageA);
    FhirResourceMapping invalid = encounter(uid(), stageB);
    FhirResourceMapping invalidOnSameStage = encounter(uid(), stageA);
    rejects(invalid);
    rejects(invalidOnSameStage);
    when(store.getByResourceTypeNoAcl(ENCOUNTER))
        .thenReturn(List.of(invalid, valid, invalidOnSameStage));

    // An invalid mapping never counts towards the uniqueness key of a valid one.
    assertEquals(List.of(valid.getUid()), uids(service.resolve(ENCOUNTER)));
  }

  @Test
  void duplicateStoredMappingsAreAllIgnored() {
    FhirResourceMapping first = encounter(uid(), stageA);
    FhirResourceMapping second = encounter(uid(), stageA);
    FhirResourceMapping single = encounter(uid(), stageB);
    assertEquals(
        FhirResourceMappingValidator.uniquenessKey(first),
        FhirResourceMappingValidator.uniquenessKey(second));
    when(store.getByResourceTypeNoAcl(ENCOUNTER)).thenReturn(List.of(first, single, second));

    assertEquals(List.of(single.getUid()), uids(service.resolve(ENCOUNTER)));

    when(store.getByResourceTypeNoAcl(PATIENT)).thenReturn(List.of(patient(uid()), patient(uid())));

    assertEquals(List.of(), service.resolve(PATIENT));
  }

  @Test
  void typeWithoutUsableMappingIsNotSupported() {
    FhirResourceMapping invalid = observation(uid(), stageA);
    rejects(invalid);
    when(store.getByResourceTypeNoAcl(OBSERVATION)).thenReturn(List.of(), List.of(invalid));

    assertTrue(service.resolve(OBSERVATION).isEmpty());
    assertTrue(service.resolve(OBSERVATION).isEmpty());
    verifyNoInteractions(manager);
  }

  @Test
  void resolvedRecordsAreDetached() {
    FhirResourceMapping stored = patient(uid());
    Instant storedLastUpdated = stored.getLastUpdated().toInstant();
    when(store.getByResourceTypeNoAcl(PATIENT)).thenReturn(List.of(stored));

    ResolvedMapping resolved = single(service.resolve(PATIENT));

    stored.setName("Renamed");
    stored.setLastUpdated(new Date());
    stored.getFieldMappings().get(0).setSystem("urn:changed");
    stored.getFieldMappings().get(0).setCode("changed");
    stored
        .getFieldMappings()
        .add(Entry.field(PATIENT_GIVEN_NAME, ATTRIBUTE, textAttribute.getUid()).build());
    stored.setTrackedEntityType(trackedEntityType(uid()));
    textAttribute.getBlockedSearchOperators().add(QueryOperator.EQ);

    assertEquals(stored.getUid(), resolved.uid());
    assertEquals(PATIENT, resolved.resourceType());
    assertEquals(person.getUid(), resolved.trackedEntityType());
    assertNull(resolved.program());
    assertNull(resolved.programStage());
    assertEquals(storedLastUpdated, resolved.lastUpdated());
    assertEquals(2, resolved.entries().size());
    FhirFieldMapping identifier = resolved.entries().get(0);
    assertEquals(PATIENT_IDENTIFIER, identifier.getTarget());
    assertEquals(IDENTIFIER_SYSTEM, identifier.getSystem());
    assertNull(identifier.getCode());
    assertTrue(resolved.entries(PATIENT_GIVEN_NAME).isEmpty());
    assertEquals(
        Set.of(QueryOperator.SW), resolved.blockedSearchOperators().get(textAttribute.getUid()));

    assertThrows(
        UnsupportedOperationException.class, () -> resolved.entries().add(new FhirFieldMapping()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> resolved.valueTypes().put(uid(), ValueType.TEXT));
    assertThrows(
        UnsupportedOperationException.class,
        () -> resolved.blockedSearchOperators().put(uid(), Set.of()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> resolved.blockedSearchOperators().get(textAttribute.getUid()).add(QueryOperator.EQ));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            resolved.blockedSearchOperators().get(integerAttribute.getUid()).add(QueryOperator.EQ));
    assertThrows(
        UnsupportedOperationException.class, () -> resolved.minCharactersToSearch().put(uid(), 1));
  }

  @Test
  void resolvedMappingsAreOrderedByUid() {
    List<String> descending =
        Stream.of(uid(), uid(), uid()).sorted(Comparator.reverseOrder()).toList();
    ProgramStage stageC = programStage(uid(), program, numberDataElement);
    when(store.getByResourceTypeNoAcl(ENCOUNTER))
        .thenReturn(
            List.of(
                encounter(descending.get(0), stageA),
                encounter(descending.get(1), stageB),
                encounter(descending.get(2), stageC)));

    List<ResolvedMapping> resolved = service.resolve(ENCOUNTER);

    assertEquals(descending.stream().sorted().toList(), uids(resolved));
    assertThrows(UnsupportedOperationException.class, resolved::clear);
  }

  @Test
  void resolvedRecordCarriesValueTypesAndSearchConstraints() {
    when(store.getByResourceTypeNoAcl(PATIENT)).thenReturn(List.of(patient(uid())));
    when(store.getByResourceTypeNoAcl(OBSERVATION)).thenReturn(List.of(observation(uid(), stageA)));

    ResolvedMapping patient = single(service.resolve(PATIENT));

    assertEquals(
        Map.of(
            textAttribute.getUid(), ValueType.TEXT, integerAttribute.getUid(), ValueType.INTEGER),
        patient.valueTypes());
    assertEquals(
        Map.of(
            textAttribute.getUid(), Set.of(QueryOperator.SW), integerAttribute.getUid(), Set.of()),
        patient.blockedSearchOperators());
    assertEquals(
        Map.of(textAttribute.getUid(), 3, integerAttribute.getUid(), 0),
        patient.minCharactersToSearch());
    assertEquals(
        Optional.of(textAttribute.getUid()),
        patient.entry(PATIENT_FAMILY_NAME).map(FhirFieldMapping::getSource));

    ResolvedMapping observation = single(service.resolve(OBSERVATION));

    assertEquals(Map.of(numberDataElement.getUid(), ValueType.NUMBER), observation.valueTypes());
    assertEquals(Map.of(), observation.blockedSearchOperators());
    assertEquals(Map.of(), observation.minCharactersToSearch());
    assertEquals(person.getUid(), observation.trackedEntityType());
    assertEquals(program.getUid(), observation.program());
    assertEquals(stageA.getUid(), observation.programStage());
    assertEquals(
        List.of(LOINC_BODY_WEIGHT_CODE),
        observation.entries(OBSERVATION_VALUE).stream().map(FhirFieldMapping::getCode).toList());
  }

  @Test
  void resolveAllAppliesTheSameGuard() {
    FhirResourceMapping patientMapping = patient(uid());
    FhirResourceMapping duplicateA = encounter(uid(), stageA);
    FhirResourceMapping duplicateB = encounter(uid(), stageA);
    FhirResourceMapping invalid = encounter(uid(), stageB);
    FhirResourceMapping observationMapping = observation(uid(), stageB);
    FhirResourceMapping firstVaccine = immunization(uid(), stageA, booleanDataElement);
    FhirResourceMapping secondVaccine =
        immunization(uid(), stageA, dataElement(uid(), ValueType.BOOLEAN));
    rejects(invalid);
    when(store.getAllNoAcl())
        .thenReturn(
            List.of(
                observationMapping,
                duplicateA,
                invalid,
                firstVaccine,
                patientMapping,
                duplicateB,
                secondVaccine));

    List<ResolvedMapping> resolved = service.resolveAll();

    assertEquals(
        Stream.of(patientMapping, observationMapping, firstVaccine, secondVaccine)
            .map(FhirResourceMapping::getUid)
            .sorted()
            .toList(),
        uids(resolved));
    verify(store, never()).getByResourceTypeNoAcl(any());
  }

  /** Makes the validator report a missing target for the given mapping. */
  private void rejects(FhirResourceMapping mapping) {
    when(validator.validate(same(mapping), anyCollection()))
        .thenReturn(List.of(new ErrorReport(FhirResourceMapping.class, ErrorCode.E4000, "target")));
  }

  /** A Patient mapping with an identifier from the INTEGER and a family name from the TEXT TEA. */
  private FhirResourceMapping patient(String uid) {
    return mapping(
        uid,
        PATIENT,
        person,
        null,
        null,
        Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, integerAttribute.getUid())
            .system(IDENTIFIER_SYSTEM)
            .build(),
        Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, textAttribute.getUid()).build());
  }

  private FhirResourceMapping encounter(String uid, ProgramStage stage) {
    return mapping(
        uid,
        ENCOUNTER,
        person,
        program,
        stage,
        Entry.constant(
                ENCOUNTER_CLASS,
                ENCOUNTER_CLASS_SYSTEM,
                ENCOUNTER_CLASS_CODE,
                ENCOUNTER_CLASS_DISPLAY)
            .build());
  }

  /** An Observation mapping of the NUMBER data element as body weight. */
  private FhirResourceMapping observation(String uid, ProgramStage stage) {
    return mapping(
        uid,
        OBSERVATION,
        person,
        program,
        stage,
        Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, numberDataElement.getUid())
            .system(LOINC_SYSTEM)
            .code(LOINC_BODY_WEIGHT_CODE)
            .unit(BODY_WEIGHT_UNIT)
            .build());
  }

  private FhirResourceMapping immunization(
      String uid, ProgramStage stage, DataElement administered) {
    return mapping(
        uid,
        IMMUNIZATION,
        person,
        program,
        stage,
        Entry.field(IMMUNIZATION_ADMINISTERED, DATA_ELEMENT, administered.getUid()).build(),
        Entry.constant(IMMUNIZATION_VACCINE_CODE, CVX_SYSTEM, CVX_CODE, CVX_DISPLAY).build());
  }

  private static ResolvedMapping single(List<ResolvedMapping> resolved) {
    assertEquals(1, resolved.size());
    return resolved.get(0);
  }

  private static List<String> uids(List<ResolvedMapping> resolved) {
    return resolved.stream().map(ResolvedMapping::uid).toList();
  }
}
