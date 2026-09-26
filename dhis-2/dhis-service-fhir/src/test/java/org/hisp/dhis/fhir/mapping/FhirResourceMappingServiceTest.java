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

import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceMappingService.escapeControlCharacters;
import static org.hisp.dhis.fhir.mapping.FhirResourceMappingValidator.uniquenessKey;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.stream.*;
import org.hisp.dhis.common.*;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dxf2.metadata.objectbundle.*;
import org.hisp.dhis.feedback.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.preheat.Preheat;
import org.hisp.dhis.program.*;
import org.hisp.dhis.schema.*;
import org.hisp.dhis.trackedentity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FhirResourceMappingServiceTest {
  private static final Entry AMBULATORY =
      Entry.constant(
          ENCOUNTER_CLASS, ENCOUNTER_CLASS_SYSTEM, ENCOUNTER_CLASS_CODE, ENCOUNTER_CLASS_DISPLAY);
  @Mock private FhirResourceMappingStore store;
  @Mock private FhirResourceMappingValidator validator;
  @Mock private SchemaService schemaService;
  @Mock private IdentifiableObjectManager manager;
  @Captor private ArgumentCaptor<Collection<FhirResourceMapping>> others;
  private final List<IdentifiableObject> metadata = new ArrayList<>();
  private FhirResourceMappingService service;
  private TrackedEntityAttribute textAttribute, integerAttribute, genderAttribute;
  private TrackedEntityType person;
  private DataElement numberDataElement, booleanDataElement;
  private Program program;
  private ProgramStage stageA, stageB;

  @BeforeEach
  void setUp() {
    textAttribute = register(trackedEntityAttribute(uid(), ValueType.TEXT));
    textAttribute.setMinCharactersToSearch(3);
    textAttribute.setBlockedSearchOperators(EnumSet.of(QueryOperator.SW));
    integerAttribute = register(trackedEntityAttribute(uid(), ValueType.INTEGER));
    genderAttribute = register(trackedEntityAttribute(uid(), ValueType.TEXT));
    person = register(trackedEntityType(uid(), textAttribute, integerAttribute, genderAttribute));
    numberDataElement = register(dataElement(uid(), ValueType.NUMBER));
    booleanDataElement = register(dataElement(uid(), ValueType.BOOLEAN));
    program = register(program(uid(), ProgramType.WITH_REGISTRATION, person));
    stageA = register(programStage(uid(), program, numberDataElement, booleanDataElement));
    stageB = register(programStage(uid(), program, numberDataElement, booleanDataElement));
    service = spy(new FhirResourceMappingService(store, validator, schemaService, manager));
    lenient().when(validator.validate(any(), anyCollection(), any())).thenReturn(List.of());
    lenient()
        .when(manager.getNoAcl(any(), anyCollection()))
        .thenAnswer(
            call -> metadata.stream().filter(call.<Class<?>>getArgument(0)::isInstance).toList());
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
    FhirResourceMapping forged = encounter("forged\r\nWARN line", stageA);
    rejects(invalid);
    rejects(forged);
    when(store.getByResourceTypeNoAcl(ENCOUNTER)).thenReturn(List.of(invalid, valid, forged));
    assertEquals(List.of(valid.getUid()), uids(service.resolve(ENCOUNTER)));
    verify(service).logIgnored(List.of(invalid.getUid()), List.of(ErrorCode.E4000));
    verify(service).logIgnored(List.of("forged\\u000D\\u000AWARN line"), List.of(ErrorCode.E4000));
    assertNull(escapeControlCharacters(null));
    assertEquals("\\u0085\\u2029", escapeControlCharacters("\u0085\u2029"));
  }

  @Test
  void duplicateStoredMappingsAreAllIgnored() {
    FhirResourceMapping first = encounter(uid(), stageA);
    FhirResourceMapping second = encounter("second\u2028", stageA);
    FhirResourceMapping single = encounter(uid(), stageB);
    assertEquals(uniquenessKey(first), uniquenessKey(second));
    when(store.getByResourceTypeNoAcl(ENCOUNTER)).thenReturn(List.of(first, single, second));
    assertEquals(List.of(single.getUid()), uids(service.resolve(ENCOUNTER)));
    verify(service).logIgnored(List.of(first.getUid(), "second\\u2028"), List.of(ErrorCode.E5003));
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
    verify(manager, never()).getNoAcl(any(), anyString());
  }

  @Test
  void resolvedRecordsAreDetached() {
    FhirResourceMapping stored = patient(uid());
    Map<String, String> genders = Map.of("M", "male", "F", "female");
    Entry gender = Entry.field(PATIENT_GENDER, ATTRIBUTE, genderAttribute.getUid());
    stored.getFieldMappings().add(gender.valueMap(genders).build());
    when(store.getByResourceTypeNoAcl(PATIENT)).thenReturn(List.of(stored));
    ResolvedMapping resolved = single(service.resolve(PATIENT));
    stored.setLastUpdated(new Date());
    List<FhirFieldMapping> storedEntries = stored.getFieldMappings();
    storedEntries.get(0).setSystem("urn:changed");
    storedEntries.get(2).getValueMap().put("M", "other");
    storedEntries.add(Entry.field(PATIENT_GIVEN_NAME, ATTRIBUTE, textAttribute.getUid()).build());
    stored.setTrackedEntityType(trackedEntityType(uid()));
    textAttribute.getBlockedSearchOperators().add(QueryOperator.EQ);
    Map<String, Set<QueryOperator>> blocked = resolved.blockedSearchOperators();
    assertEquals(PATIENT, resolved.resourceType());
    assertEquals(person.getUid(), resolved.trackedEntityType());
    assertEquals(UPDATED, resolved.lastUpdated());
    assertEquals(3, resolved.entries().size());
    assertEquals(IDENTIFIER_SYSTEM, resolved.entries().get(0).getSystem());
    assertEquals(Set.of(QueryOperator.SW), blocked.get(textAttribute.getUid()));
    FhirFieldMapping resolvedGender = resolved.entry(PATIENT_GENDER).orElseThrow();
    assertEquals(genders, resolvedGender.getValueMap());
    Class<UnsupportedOperationException> immutable = UnsupportedOperationException.class;
    FhirFieldMapping byTarget = resolved.entries(PATIENT_GENDER).get(0);
    for (FhirFieldMapping entry : List.of(resolved.entries().get(2), byTarget, resolvedGender)) {
      assertThrows(immutable, () -> entry.setCode("changed"));
      assertThrows(immutable, () -> entry.getValueMap().put("M", "other"));
    }
    new FhirFieldMapping(resolvedGender).getValueMap().put("M", "other");
    assertEquals(genders, resolved.entries(PATIENT_GENDER).get(0).getValueMap());
    assertThrows(immutable, () -> resolved.entries().add(new FhirFieldMapping()));
    assertThrows(immutable, () -> resolved.valueTypes().put(uid(), ValueType.TEXT));
    assertThrows(immutable, () -> blocked.put(uid(), Set.of()));
    assertThrows(immutable, () -> blocked.get(textAttribute.getUid()).add(QueryOperator.EQ));
    assertThrows(immutable, () -> blocked.get(integerAttribute.getUid()).add(QueryOperator.EQ));
    assertThrows(immutable, () -> resolved.minCharactersToSearch().put(uid(), 1));
  }

  @Test
  void resolvedMappingsAreOrderedByUid() {
    List<String> desc = Stream.of(uid(), uid(), uid()).sorted(Comparator.reverseOrder()).toList();
    List<ProgramStage> stages = List.of(stageA, stageB, programStage(uid(), program));
    when(store.getByResourceTypeNoAcl(ENCOUNTER))
        .thenReturn(Stream.of(0, 1, 2).map(i -> encounter(desc.get(i), stages.get(i))).toList());
    List<ResolvedMapping> resolved = service.resolve(ENCOUNTER);
    assertEquals(desc.stream().sorted().toList(), uids(resolved));
    assertThrows(UnsupportedOperationException.class, resolved::clear);
  }

  @Test
  void resolvedRecordCarriesValueTypesAndSearchConstraints() {
    when(store.getByResourceTypeNoAcl(PATIENT)).thenReturn(List.of(patient(uid())));
    when(store.getByResourceTypeNoAcl(OBSERVATION)).thenReturn(List.of(observation(uid(), stageA)));
    String text = textAttribute.getUid();
    String integer = integerAttribute.getUid();
    ResolvedMapping patient = single(service.resolve(PATIENT));
    assertEquals(Map.of(text, ValueType.TEXT, integer, ValueType.INTEGER), patient.valueTypes());
    assertEquals(
        Map.of(text, Set.of(QueryOperator.SW), integer, Set.of()),
        patient.blockedSearchOperators());
    assertEquals(Map.of(text, 3, integer, 0), patient.minCharactersToSearch());
    ResolvedMapping observation = single(service.resolve(OBSERVATION));
    assertEquals(Map.of(numberDataElement.getUid(), ValueType.NUMBER), observation.valueTypes());
    assertEquals(program.getUid(), observation.program());
    assertEquals(stageA.getUid(), observation.programStage());
    assertEquals(LOINC_BODY_WEIGHT_CODE, observation.entries(OBSERVATION_VALUE).get(0).getCode());
  }

  @Test
  void resolveAllAppliesTheSameGuard() {
    var realValidator = new FhirResourceMappingValidator(manager);
    service = new FhirResourceMappingService(store, realValidator, schemaService, manager);
    DataElement trueOnly = register(dataElement(uid(), ValueType.TRUE_ONLY));
    stageA.getProgramStageDataElements().add(new ProgramStageDataElement(stageA, trueOnly));
    DataElement missing = dataElement(uid(), ValueType.BOOLEAN);
    FhirResourceMapping patient = patient(uid());
    patient.getFieldMappings().get(0).setSystem("ldap://directory.example.org/ids");
    FhirResourceMapping dupA = encounter(uid(), stageA);
    FhirResourceMapping dupB = encounter(uid(), stageA);
    FhirResourceMapping invalid = immunization(uid(), stageB, missing);
    FhirResourceMapping observation = observation(uid(), stageB);
    FhirResourceMapping badCode = observation(uid(), stageA);
    badCode.getFieldMappings().get(0).setCode("8302-2 ");
    FhirResourceMapping vaccineA = immunization(uid(), stageA, booleanDataElement);
    FhirResourceMapping vaccineB = immunization(uid(), stageA, trueOnly);
    when(store.getAllNoAcl())
        .thenReturn(
            List.of(observation, dupA, invalid, vaccineA, patient, dupB, vaccineB, badCode));
    List<ResolvedMapping> resolved = service.resolveAll();
    List<FhirResourceMapping> usable = List.of(patient, observation, vaccineA, vaccineB);
    assertEquals(
        usable.stream().map(FhirResourceMapping::getUid).sorted().toList(), uids(resolved));
    String text = textAttribute.getUid();
    String integer = integerAttribute.getUid();
    String number = numberDataElement.getUid();
    String bool = booleanDataElement.getUid();
    verify(store, never()).getByResourceTypeNoAcl(any());
    verify(manager).getNoAcl(TrackedEntityType.class, Set.of(person.getUid()));
    verify(manager).getNoAcl(Program.class, Set.of(program.getUid()));
    verify(manager).getNoAcl(ProgramStage.class, Set.of(stageA.getUid(), stageB.getUid()));
    verify(manager).getNoAcl(TrackedEntityAttribute.class, Set.of(text, integer));
    Set<String> dataElements = Set.of(number, bool, trueOnly.getUid(), missing.getUid());
    verify(manager).getNoAcl(DataElement.class, dataElements);
    verify(manager, never()).getNoAcl(any(), anyString());
  }

  @Test
  void bundleMappingsAreComparedWithSameKeyMappingsOfOneStoreRead() {
    var hook = new FhirResourceMappingObjectBundleHook(store, validator);
    FhirResourceMapping storedPatient = patient(uid());
    FhirResourceMapping storedEncounter = encounter(uid(), stageA);
    FhirResourceMapping patientA = patient(uid());
    FhirResourceMapping patientB = patient(uid());
    FhirResourceMapping encounter = encounter(storedEncounter.getUid(), stageA);
    FhirResourceMapping keyless = mapping(uid(), OBSERVATION, person, program, null);
    var report = new ErrorReport(FhirResourceMapping.class, ErrorCode.E4000, "trackedEntityType");
    when(validator.validate(same(patientA), anyCollection(), any())).thenReturn(List.of(report));
    when(store.getAllNoAcl())
        .thenReturn(List.of(storedPatient, storedEncounter, observation(uid(), stageA)));
    ObjectBundle bundle = bundle(patientA, patientB, encounter, keyless);
    List<ErrorReport> received = new ArrayList<>();
    for (FhirResourceMapping imported : List.of(patientA, patientB, encounter, keyless)) {
      hook.validate(imported, bundle, received::add);
    }
    hook.validate(storedPatient, bundle(storedPatient), received::add);
    verify(store, times(2)).getAllNoAcl();
    assertEquals(List.of(report), received);
    assertEquals(List.of(storedPatient, patientB), othersOf(patientA));
    assertEquals(List.of(storedPatient, patientA), othersOf(patientB));
    assertEquals(List.of(), othersOf(encounter));
    assertEquals(List.of(), othersOf(keyless));
  }

  private <T extends IdentifiableObject> T register(T object) {
    metadata.add(object);
    return object;
  }

  private void rejects(FhirResourceMapping mapping) {
    when(validator.validate(same(mapping), anyCollection(), any()))
        .thenReturn(List.of(new ErrorReport(FhirResourceMapping.class, ErrorCode.E4000, "target")));
  }

  private FhirResourceMapping patient(String uid) {
    Entry identifier = Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, integerAttribute.getUid());
    identifier.system(IDENTIFIER_SYSTEM);
    Entry family = Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, textAttribute.getUid());
    return mapping(uid, PATIENT, person, null, null, identifier.build(), family.build());
  }

  private FhirResourceMapping encounter(String uid, ProgramStage stage) {
    return mapping(uid, ENCOUNTER, person, program, stage, AMBULATORY.build());
  }

  private FhirResourceMapping observation(String uid, ProgramStage stage) {
    Entry weight = Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, numberDataElement.getUid());
    weight.system(LOINC_SYSTEM).code(LOINC_BODY_WEIGHT_CODE).unit(BODY_WEIGHT_UNIT);
    return mapping(uid, OBSERVATION, person, program, stage, weight.build());
  }

  private FhirResourceMapping immunization(String uid, ProgramStage stage, DataElement given) {
    Entry dose = Entry.field(IMMUNIZATION_ADMINISTERED, DATA_ELEMENT, given.getUid());
    Entry vaccine = Entry.constant(IMMUNIZATION_VACCINE_CODE, CVX_SYSTEM, CVX_CODE, CVX_DISPLAY);
    return mapping(uid, IMMUNIZATION, person, program, stage, dose.build(), vaccine.build());
  }

  private static ObjectBundle bundle(FhirResourceMapping... mappings) {
    Map<Class<? extends IdentifiableObject>, List<IdentifiableObject>> objects =
        Map.of(FhirResourceMapping.class, List.of(mappings));
    return new ObjectBundle(new ObjectBundleParams(), new Preheat(), objects);
  }

  private List<FhirResourceMapping> othersOf(FhirResourceMapping mapping) {
    verify(validator).validate(same(mapping), others.capture(), any());
    return List.copyOf(others.getValue());
  }

  private static ResolvedMapping single(List<ResolvedMapping> resolved) {
    assertEquals(1, resolved.size());
    return resolved.get(0);
  }

  private static List<String> uids(List<ResolvedMapping> resolved) {
    return resolved.stream().map(ResolvedMapping::uid).toList();
  }
}
