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

import static org.hisp.dhis.common.ValueType.*;
import static org.hisp.dhis.feedback.ErrorCode.E4000;
import static org.hisp.dhis.feedback.ErrorCode.E4010;
import static org.hisp.dhis.feedback.ErrorCode.E4014;
import static org.hisp.dhis.feedback.ErrorCode.E4027;
import static org.hisp.dhis.feedback.ErrorCode.E5002;
import static org.hisp.dhis.feedback.ErrorCode.E5003;
import static org.hisp.dhis.fhir.FhirTestFixtures.BODY_HEIGHT_UNIT;
import static org.hisp.dhis.fhir.FhirTestFixtures.BODY_WEIGHT_UNIT;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_HEIGHT_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_HEIGHT_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_WEIGHT_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_WEIGHT_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.dataElement;
import static org.hisp.dhis.fhir.FhirTestFixtures.entries;
import static org.hisp.dhis.fhir.FhirTestFixtures.lookup;
import static org.hisp.dhis.fhir.FhirTestFixtures.mapping;
import static org.hisp.dhis.fhir.FhirTestFixtures.program;
import static org.hisp.dhis.fhir.FhirTestFixtures.programStage;
import static org.hisp.dhis.fhir.FhirTestFixtures.trackedEntityAttribute;
import static org.hisp.dhis.fhir.FhirTestFixtures.trackedEntityType;
import static org.hisp.dhis.fhir.FhirTestFixtures.uid;
import static org.hisp.dhis.fhir.mapping.FhirResourceMappingValidator.uniquenessKey;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.ENCOUNTER;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.IMMUNIZATION;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.OBSERVATION;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.PATIENT;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.ATTRIBUTE;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.hisp.dhis.program.ProgramType.WITHOUT_REGISTRATION;
import static org.hisp.dhis.program.ProgramType.WITH_REGISTRATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.feedback.ErrorCode;
import org.hisp.dhis.feedback.ErrorReport;
import org.hisp.dhis.fhir.FhirTestFixtures.Entry;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.program.ProgramStageDataElement;
import org.hisp.dhis.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.trackedentity.TrackedEntityType;
import org.hisp.dhis.trackedentity.TrackedEntityTypeAttribute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests of {@link FhirResourceMappingValidator}. Each case starts from a valid mapping of one
 * resource type over an in-memory metadata world, breaks one rule, and expects reports of that
 * rule's error code only.
 */
@ExtendWith(MockitoExtension.class)
class FhirResourceMappingValidatorTest {
  private static final String SYSTEM = "urn:test:id";

  private static final Map<String, String> GENDER_MAP = Map.of("M", "male", "F", "female");

  private static final Set<ErrorCode> VALIDATOR_CODES =
      EnumSet.of(E4000, E4010, E4014, E4027, E5002, E5003);

  /** The value types accepted by each target that takes attribute or data element sources. */
  private static final Map<FhirTargetField, Set<ValueType>> ACCEPTED =
      new EnumMap<>(FhirTargetField.class);

  static {
    Set<ValueType> text = EnumSet.of(TEXT, LONG_TEXT, LETTER);
    Set<ValueType> integer =
        EnumSet.of(INTEGER, INTEGER_POSITIVE, INTEGER_NEGATIVE, INTEGER_ZERO_OR_POSITIVE);
    Set<ValueType> identifier = EnumSet.of(USERNAME, EMAIL, PHONE_NUMBER, URL);
    identifier.addAll(text);
    identifier.addAll(integer);
    Set<ValueType> doseNumber = EnumSet.of(TEXT);
    doseNumber.addAll(integer);
    ACCEPTED.put(PATIENT_IDENTIFIER, identifier);
    EnumSet.of(PATIENT_FAMILY_NAME, PATIENT_GIVEN_NAME, PATIENT_GENDER, PATIENT_ADDRESS_TEXT)
        .forEach(target -> ACCEPTED.put(target, text));
    EnumSet.of(ENCOUNTER_TYPE, ENCOUNTER_REASON, IMMUNIZATION_LOT_NUMBER)
        .forEach(target -> ACCEPTED.put(target, text));
    ACCEPTED.put(PATIENT_BIRTH_DATE, EnumSet.of(DATE, AGE));
    ACCEPTED.put(PATIENT_PHONE, EnumSet.of(PHONE_NUMBER, TEXT));
    ACCEPTED.put(PATIENT_EMAIL, EnumSet.of(EMAIL, TEXT));
    ACCEPTED.put(IMMUNIZATION_ADMINISTERED, EnumSet.of(BOOLEAN, TRUE_ONLY, TEXT));
    ACCEPTED.put(IMMUNIZATION_DOSE_NUMBER, doseNumber);
    ACCEPTED.put(
        OBSERVATION_VALUE,
        EnumSet.complementOf(
            EnumSet.of(FILE_RESOURCE, IMAGE, COORDINATE, GEOJSON, ORGANISATION_UNIT, REFERENCE)));
  }

  @Mock private IdentifiableObjectManager manager;

  private final List<IdentifiableObject> metadata = new ArrayList<>();
  private FhirResourceMappingValidator validator;
  private TrackedEntityType person;
  private TrackedEntityAttribute textTea;
  private TrackedEntityAttribute text2Tea;
  private TrackedEntityAttribute genderTea;
  private TrackedEntityAttribute integerTea;
  private TrackedEntityAttribute dateTea;
  private TrackedEntityAttribute phoneTea;
  private TrackedEntityAttribute emailTea;
  private TrackedEntityAttribute addressTea;
  private TrackedEntityAttribute programTea;
  private TrackedEntityAttribute strayTea;
  private Program program;
  private Program withoutRegistrationProgram;
  private Program otherTypeProgram;
  private ProgramStage stage;
  private ProgramStage otherStage;
  private DataElement booleanDe;
  private DataElement textDe;
  private DataElement integerDe;
  private DataElement numberDe;
  private DataElement number2De;
  private DataElement strayDe;

  /**
   * Registers tracked entity type {@code person} with its attributes; the registration {@code
   * program} on {@code person} with attribute {@code programTea} and stage {@code stage} with its
   * data elements; a stage of another program; a program without registration; a program of another
   * type; and the attribute {@code strayTea} and data element {@code strayDe} of nothing.
   */
  @BeforeEach
  void setUp() {
    validator = new FhirResourceMappingValidator(manager);
    person = register(trackedEntityType(uid()));
    textTea = personAttribute(TEXT);
    text2Tea = personAttribute(TEXT);
    genderTea = personAttribute(TEXT);
    integerTea = personAttribute(INTEGER);
    dateTea = personAttribute(DATE);
    phoneTea = personAttribute(PHONE_NUMBER);
    emailTea = personAttribute(EMAIL);
    addressTea = personAttribute(LONG_TEXT);
    programTea = register(trackedEntityAttribute(uid(), TEXT));
    strayTea = register(trackedEntityAttribute(uid(), TEXT));
    program = register(program(uid(), WITH_REGISTRATION, person, programTea));
    stage = register(programStage(uid(), program));
    booleanDe = stageDataElement(BOOLEAN);
    textDe = stageDataElement(TEXT);
    integerDe = stageDataElement(INTEGER);
    numberDe = stageDataElement(NUMBER);
    number2De = stageDataElement(NUMBER);
    strayDe = register(dataElement(uid(), TEXT));
    Program otherProgram = register(program(uid(), WITH_REGISTRATION, person));
    otherStage = register(programStage(uid(), otherProgram, textDe));
    withoutRegistrationProgram = register(program(uid(), WITHOUT_REGISTRATION, person));
    TrackedEntityType otherType = register(trackedEntityType(uid()));
    otherTypeProgram = register(program(uid(), WITH_REGISTRATION, otherType));
  }

  @ParameterizedTest
  @EnumSource(FhirResourceType.class)
  void validMappingOfEachTypeHasNoReports(FhirResourceType type) {
    assertNoReports(validate(validMapping(type)));

    if (type == PATIENT) {
      FhirResourceMapping withProgram =
          withEntries(PATIENT_ADDRESS_TEXT, entry(PATIENT_ADDRESS_TEXT, programTea));
      withProgram.setProgram(program);
      assertNoReports(validate(withProgram));
    }
  }

  @Test
  void validateWithoutLookupResolvesMetadataThroughManagerWithoutAcl() {
    BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup =
        lookup(metadata.toArray(IdentifiableObject[]::new));
    when(manager.getNoAcl(any(), anyString()))
        .thenAnswer(call -> lookup.apply(call.getArgument(0), call.getArgument(1)));

    assertNoReports(validator.validate(validMapping(ENCOUNTER), null));
    verify(manager).getNoAcl(Program.class, program.getUid());
    verify(manager).getNoAcl(ProgramStage.class, stage.getUid());
  }

  // E4000: missing required properties

  @Test
  void nullEntryIsMissingRequiredProperty() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    mapping.getFieldMappings().add(null);
    assertOnly(validate(mapping), E4000, "fieldMappings");
  }

  @Test
  void entryWithoutTargetIsMissingRequiredProperty() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    mapping.getFieldMappings().add(Entry.field(null, ATTRIBUTE, textTea.getUid()).build());
    assertOnly(validate(mapping), E4000, "target");
  }

  @Test
  void entryWithoutSourceTypeIsMissingRequiredProperty() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    entryOf(mapping, PATIENT_FAMILY_NAME).setSourceType(null);
    assertOnly(validate(mapping), E4000, "sourceType");
  }

  @ParameterizedTest
  @CsvSource({
    "ENCOUNTER, program",
    "ENCOUNTER, programStage",
    "IMMUNIZATION, program",
    "IMMUNIZATION, programStage",
    "OBSERVATION, program",
    "OBSERVATION, programStage"
  })
  void eventMappingWithoutProgramOrStageIsMissingRequiredProperty(
      FhirResourceType type, String property) {
    FhirResourceMapping mapping = validMapping(type);
    if ("program".equals(property)) {
      mapping.setProgram(null);
    } else {
      mapping.setProgramStage(null);
    }
    assertOnly(validate(mapping), E4000, property);
  }

  @ParameterizedTest
  @EnumSource(
      value = FhirTargetField.class,
      names = {
        "ENCOUNTER_CLASS",
        "IMMUNIZATION_VACCINE_CODE",
        "IMMUNIZATION_ADMINISTERED",
        "OBSERVATION_VALUE"
      })
  void missingRequiredTargetIsMissingRequiredProperty(FhirTargetField target) {
    assertOnly(validate(withEntries(target)), E4000, target.name());
  }

  @Test
  void identifierWithoutSystemIsMissingRequiredProperty() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    entryOf(mapping, PATIENT_IDENTIFIER).setSystem(" ");
    assertOnly(validate(mapping), E4000, "system");
  }

  @Test
  void constantWithoutCodeIsMissingRequiredProperty() {
    FhirResourceMapping mapping = validMapping(ENCOUNTER);
    entryOf(mapping, ENCOUNTER_CLASS).setCode(null);
    assertOnly(validate(mapping), E4000, "code");
  }

  @Test
  void observationValueWithoutCodeIsMissingRequiredProperty() {
    FhirResourceMapping mapping = validMapping(OBSERVATION);
    entryOf(mapping, OBSERVATION_VALUE).setCode(null);
    assertOnly(validate(mapping), E4000, "code");
  }

  @Test
  void nonConstantEntryWithoutSourceIsMissingRequiredProperty() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    entryOf(mapping, PATIENT_FAMILY_NAME).setSource(null);
    assertOnly(validate(mapping), E4000, "source");
  }

  // E4010: targets and source types the mapping does not support

  @Test
  void targetOfAnotherResourceTypeIsNotSupported() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    mapping.getFieldMappings().add(observation(numberDe, LOINC_BODY_HEIGHT_CODE, null).build());
    assertOnly(validate(mapping), E4010, "OBSERVATION_VALUE", "PATIENT");
  }

  @ParameterizedTest
  @EnumSource(
      value = FhirTargetField.class,
      names = {"ENCOUNTER_CLASS", "PATIENT_FAMILY_NAME"})
  void sourceTypeNotAllowedForTargetIsNotSupported(FhirTargetField target) {
    FhirResourceMapping mapping =
        withEntries(target, Entry.field(target, DATA_ELEMENT, textDe.getUid()));
    assertOnly(validate(mapping), E4010, "DATA_ELEMENT", target.name());
  }

  // E4014 and E4027: invalid values

  @Test
  void sourceThatIsNotAUidIsInvalid() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    entryOf(mapping, PATIENT_FAMILY_NAME).setSource("not-a-uid");
    assertOnly(validate(mapping), E4014, "not-a-uid", "source");
  }

  @Test
  void genderValueMapOutsideAdministrativeGenderIsInvalid() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    entryOf(mapping, PATIENT_GENDER).setValueMap(Map.of("M", "man", "F", "female"));
    assertOnly(validate(mapping), E4027, "man", "valueMap");
  }

  /** Sources the birth date from {@code textTea} in a mapping without family name entry. */
  @Test
  void birthDateFedTextAttributeIsInvalidValueType() {
    FhirResourceMapping mapping =
        replace(
            withEntries(PATIENT_FAMILY_NAME),
            PATIENT_BIRTH_DATE,
            entry(PATIENT_BIRTH_DATE, textTea));
    assertOnly(validate(mapping), E4027, "TEXT", "PATIENT_BIRTH_DATE");
  }

  @ParameterizedTest
  @MethodSource("acceptedValueTypes")
  void acceptedValueTypeHasNoValueTypeReport(FhirTargetField target, ValueType valueType) {
    assertNoReports(validate(withEntries(target, sourceOfValueType(target, valueType))));
  }

  @ParameterizedTest
  @MethodSource("rejectedValueTypes")
  void rejectedValueTypeIsInvalid(FhirTargetField target, ValueType valueType) {
    FhirResourceMapping mapping = withEntries(target, sourceOfValueType(target, valueType));
    assertOnly(validate(mapping), E4027, valueType.name(), target.name());
  }

  /** One row per target and value type it accepts. */
  static Stream<Arguments> acceptedValueTypes() {
    return ACCEPTED.entrySet().stream()
        .flatMap(e -> e.getValue().stream().map(type -> Arguments.of(e.getKey(), type)));
  }

  /** One row per target and value type it does not accept. */
  static Stream<Arguments> rejectedValueTypes() {
    return ACCEPTED.entrySet().stream()
        .flatMap(
            e ->
                EnumSet.complementOf(EnumSet.copyOf(e.getValue())).stream()
                    .map(type -> Arguments.of(e.getKey(), type)));
  }

  // E5002: invalid references

  @Test
  void programWithoutRegistrationIsInvalidReference() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    mapping.setProgram(withoutRegistrationProgram);
    String programUid = withoutRegistrationProgram.getUid();
    assertOnly(validate(mapping), E5002, programUid, mapping.getUid(), "program");
  }

  @Test
  void programOfAnotherTrackedEntityTypeIsInvalidReference() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    mapping.setProgram(otherTypeProgram);
    String programUid = otherTypeProgram.getUid();
    assertOnly(validate(mapping), E5002, programUid, mapping.getUid(), "trackedEntityType");
  }

  @Test
  void stageNotInProgramIsInvalidReference() {
    FhirResourceMapping mapping = validMapping(ENCOUNTER);
    mapping.setProgramStage(otherStage);
    assertOnly(validate(mapping), E5002, otherStage.getUid(), mapping.getUid(), "programStage");
  }

  @Test
  void programStageOnPatientMappingIsInvalidReference() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    mapping.setProgramStage(stage);
    assertOnly(validate(mapping), E5002, stage.getUid(), mapping.getUid(), "programStage");
  }

  /** An attribute of nothing, and a program attribute on a mapping without program. */
  @Test
  void attributeNotOnTypeOrProgramIsInvalidReference() {
    for (TrackedEntityAttribute attribute : List.of(strayTea, programTea)) {
      FhirResourceMapping mapping =
          withEntries(PATIENT_FAMILY_NAME, entry(PATIENT_FAMILY_NAME, attribute));
      String id = mapping.getUid();
      assertOnly(validate(mapping), E5002, attribute.getUid(), id, "PATIENT_FAMILY_NAME");
    }
  }

  @Test
  void dataElementNotInStageIsInvalidReference() {
    FhirResourceMapping mapping = withEntries(ENCOUNTER_TYPE, entry(ENCOUNTER_TYPE, strayDe));
    assertOnly(validate(mapping), E5002, strayDe.getUid(), mapping.getUid(), "ENCOUNTER_TYPE");
  }

  // E5003: duplicates

  /** The mapping itself and a mapping with its UID are skipped; the other holder is named. */
  @ParameterizedTest
  @EnumSource(FhirResourceType.class)
  void uniquenessKeyAlreadyHeldIsDuplicate(FhirResourceType type) {
    FhirResourceMapping mapping = validMapping(type);
    FhirResourceMapping sameUid = validMapping(type);
    sameUid.setUid(mapping.getUid());
    FhirResourceMapping other = validMapping(type);
    List<ErrorReport> reports = validate(mapping, mapping, sameUid, other);
    String key = uniquenessKey(mapping);
    assertOnly(reports, E5003, "resourceType", key, mapping.getUid(), other.getUid());
  }

  @Test
  void immunizationWithDifferentAdministeredDataElementIsAllowed() {
    FhirResourceMapping other =
        withEntries(IMMUNIZATION_ADMINISTERED, entry(IMMUNIZATION_ADMINISTERED, textDe));
    assertNoReports(validate(validMapping(IMMUNIZATION), other));
  }

  @Test
  void repeatedOneCardinalityTargetIsDuplicate() {
    FhirResourceMapping mapping =
        withEntries(PATIENT_GIVEN_NAME, entry(PATIENT_FAMILY_NAME, text2Tea));
    String id = mapping.getUid();
    assertOnly(validate(mapping), E5003, "target", "PATIENT_FAMILY_NAME", id, id);
  }

  @Test
  void duplicateIdentifierSystemIsDuplicate() {
    FhirResourceMapping mapping =
        withEntries(PATIENT_ADDRESS_TEXT, entry(PATIENT_IDENTIFIER, addressTea).system(SYSTEM));
    String id = mapping.getUid();
    assertOnly(validate(mapping), E5003, "system", SYSTEM, id, id);
  }

  @Test
  void attributeOnTwoPatientTargetsIsDuplicate() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    entryOf(mapping, PATIENT_GIVEN_NAME).setSource(textTea.getUid());
    String id = mapping.getUid();
    assertOnly(validate(mapping), E5003, "source", textTea.getUid(), id, id);
  }

  @Test
  void duplicateObservationDataElementIsDuplicate() {
    FhirResourceMapping mapping = validMapping(OBSERVATION);
    Entry weight = observation(numberDe, LOINC_BODY_WEIGHT_CODE, LOINC_BODY_WEIGHT_DISPLAY);
    mapping.getFieldMappings().add(weight.build());
    String id = mapping.getUid();
    assertOnly(validate(mapping), E5003, "source", numberDe.getUid(), id, id);
  }

  @Test
  void uniquenessKeyPerResourceType() {
    String stageUid = stage.getUid();
    assertEquals("PATIENT", uniquenessKey(validMapping(PATIENT)));
    assertEquals("ENCOUNTER:" + stageUid, uniquenessKey(validMapping(ENCOUNTER)));
    assertEquals("OBSERVATION:" + stageUid, uniquenessKey(validMapping(OBSERVATION)));
    assertEquals(
        "IMMUNIZATION:" + stageUid + ":" + booleanDe.getUid(),
        uniquenessKey(validMapping(IMMUNIZATION)));

    FhirResourceMapping withoutType = validMapping(PATIENT);
    withoutType.setResourceType(null);
    FhirResourceMapping withoutStage = validMapping(ENCOUNTER);
    withoutStage.setProgramStage(null);
    assertNull(uniquenessKey(null));
    assertNull(uniquenessKey(withoutType));
    assertNull(uniquenessKey(withoutStage));
    assertNull(uniquenessKey(withEntries(IMMUNIZATION_ADMINISTERED)));
  }

  // Fixture factories and assertions

  private <T extends IdentifiableObject> T register(T object) {
    metadata.add(object);
    return object;
  }

  /** Registers a new attribute of the value type and adds it to {@code person}. */
  private TrackedEntityAttribute personAttribute(ValueType valueType) {
    TrackedEntityAttribute attribute = register(trackedEntityAttribute(uid(), valueType));
    person.getTrackedEntityTypeAttributes().add(new TrackedEntityTypeAttribute(person, attribute));
    return attribute;
  }

  /** Registers a new data element of the value type and adds it to {@code stage}. */
  private DataElement stageDataElement(ValueType valueType) {
    DataElement dataElement = register(dataElement(uid(), valueType));
    stage.getProgramStageDataElements().add(new ProgramStageDataElement(stage, dataElement));
    return dataElement;
  }

  /**
   * Returns a new valid mapping of the type with a new UID; Patient mappings use every Patient
   * target, event mappings read {@code stage} of {@code program}.
   */
  private FhirResourceMapping validMapping(FhirResourceType type) {
    boolean patient = type == PATIENT;
    FhirResourceMapping mapping =
        mapping(uid(), type, person, patient ? null : program, patient ? null : stage);
    mapping.setFieldMappings(
        switch (type) {
          case PATIENT ->
              entries(
                  entry(PATIENT_IDENTIFIER, integerTea).system(SYSTEM),
                  entry(PATIENT_FAMILY_NAME, textTea),
                  entry(PATIENT_GIVEN_NAME, text2Tea),
                  entry(PATIENT_GENDER, genderTea).valueMap(GENDER_MAP),
                  entry(PATIENT_BIRTH_DATE, dateTea),
                  entry(PATIENT_PHONE, phoneTea),
                  entry(PATIENT_EMAIL, emailTea),
                  entry(PATIENT_ADDRESS_TEXT, addressTea));
          case ENCOUNTER ->
              entries(
                  Entry.constant(
                      ENCOUNTER_CLASS,
                      ENCOUNTER_CLASS_SYSTEM,
                      ENCOUNTER_CLASS_CODE,
                      ENCOUNTER_CLASS_DISPLAY),
                  entry(ENCOUNTER_TYPE, textDe).system(SYSTEM));
          case IMMUNIZATION ->
              entries(
                  entry(IMMUNIZATION_ADMINISTERED, booleanDe),
                  Entry.constant(IMMUNIZATION_VACCINE_CODE, CVX_SYSTEM, CVX_CODE, CVX_DISPLAY),
                  entry(IMMUNIZATION_LOT_NUMBER, textDe),
                  entry(IMMUNIZATION_DOSE_NUMBER, integerDe));
          case OBSERVATION ->
              entries(
                  observation(numberDe, LOINC_BODY_HEIGHT_CODE, LOINC_BODY_HEIGHT_DISPLAY)
                      .unit(BODY_HEIGHT_UNIT),
                  observation(number2De, LOINC_BODY_WEIGHT_CODE, LOINC_BODY_WEIGHT_DISPLAY)
                      .unit(BODY_WEIGHT_UNIT));
        });
    return mapping;
  }

  /**
   * Returns the valid mapping of the target's resource type with every entry of the target replaced
   * by the given entries, which may be none.
   */
  private FhirResourceMapping withEntries(FhirTargetField target, Entry... replacements) {
    return replace(validMapping(target.resourceType()), target, replacements);
  }

  /** Removes every entry of the target from the mapping, appends the replacements, returns it. */
  private static FhirResourceMapping replace(
      FhirResourceMapping mapping, FhirTargetField target, Entry... replacements) {
    mapping.getFieldMappings().removeIf(entry -> entry != null && entry.getTarget() == target);
    mapping.getFieldMappings().addAll(entries(replacements));
    return mapping;
  }

  /** Returns the first entry of the target in the mapping; changing it changes the mapping. */
  private static FhirFieldMapping entryOf(FhirResourceMapping mapping, FhirTargetField target) {
    return mapping.getFieldMappings().stream()
        .filter(entry -> entry.getTarget() == target)
        .findFirst()
        .orElseThrow();
  }

  /** Starts an entry of the target with an attribute source for Patient targets, else a DE. */
  private static Entry entry(FhirTargetField target, IdentifiableObject source) {
    FhirSourceType sourceType = target.resourceType() == PATIENT ? ATTRIBUTE : DATA_ELEMENT;
    return Entry.field(target, sourceType, source.getUid());
  }

  private static Entry observation(DataElement source, String code, String display) {
    return entry(OBSERVATION_VALUE, source).system(LOINC_SYSTEM).code(code).display(display);
  }

  /**
   * Starts an entry of the target whose source is a new attribute of {@code person} (Patient
   * targets) or a new data element of {@code stage} (other targets) of the value type, with a
   * system, a code and a valid gender value map.
   */
  private Entry sourceOfValueType(FhirTargetField target, ValueType valueType) {
    IdentifiableObject source =
        target.resourceType() == PATIENT ? personAttribute(valueType) : stageDataElement(valueType);
    return entry(target, source).system(SYSTEM).code(LOINC_BODY_HEIGHT_CODE).valueMap(GENDER_MAP);
  }

  /**
   * Validates the mapping against the others over the registered metadata, and asserts that each
   * report has one of the validator's error codes and {@link FhirResourceMapping} as its class.
   */
  private List<ErrorReport> validate(FhirResourceMapping mapping, FhirResourceMapping... others) {
    List<ErrorReport> reports =
        validator.validate(
            mapping, List.of(others), lookup(metadata.toArray(IdentifiableObject[]::new)));
    for (ErrorReport report : reports) {
      assertTrue(VALIDATOR_CODES.contains(report.getErrorCode()), report::toString);
      assertEquals(FhirResourceMapping.class, report.getMainKlass(), report::toString);
    }
    return reports;
  }

  /**
   * Asserts that there are reports and that all have the code; given arguments, also asserts that
   * there is exactly one report and that it carries exactly these arguments.
   */
  private static void assertOnly(List<ErrorReport> reports, ErrorCode code, String... args) {
    assertFalse(reports.isEmpty(), () -> "expected a report with " + code);
    reports.forEach(report -> assertEquals(code, report.getErrorCode(), report::toString));
    if (args.length > 0) {
      assertEquals(List.of(List.of(args)), reports.stream().map(ErrorReport::getArgs).toList());
    }
  }

  private static void assertNoReports(List<ErrorReport> reports) {
    assertTrue(reports.isEmpty(), reports::toString);
  }
}
