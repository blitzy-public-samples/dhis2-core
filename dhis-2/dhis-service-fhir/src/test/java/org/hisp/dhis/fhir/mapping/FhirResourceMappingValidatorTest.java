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
import static org.hisp.dhis.feedback.ErrorCode.*;
import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceMappingValidator.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.hisp.dhis.program.ProgramType.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.stream.*;
import org.hisp.dhis.common.*;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.feedback.*;
import org.hisp.dhis.program.*;
import org.hisp.dhis.trackedentity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Positive cases expect no reports; negative ones break one rule and expect only its code. */
class FhirResourceMappingValidatorTest {
  private static final String SYSTEM = "urn:test:id", LDAP = "ldap://directory.example.org/ids";
  private static final Map<String, String> GENDER_MAP = Map.of("M", "male", "F", "female");
  private static final Entry AMBULATORY =
      Entry.constant(
          ENCOUNTER_CLASS, ENCOUNTER_CLASS_SYSTEM, ENCOUNTER_CLASS_CODE, ENCOUNTER_CLASS_DISPLAY);
  private static final Set<ErrorCode> VALIDATOR_CODES =
      EnumSet.of(E4000, E4001, E4010, E4014, E4027, E5002, E5003);
  private static final Map<FhirTargetField, Set<ValueType>> ACCEPTED =
      new EnumMap<>(FhirTargetField.class);

  static {
    Set<ValueType> text = EnumSet.of(TEXT, LONG_TEXT, LETTER);
    Set<ValueType> integer =
        EnumSet.of(INTEGER, INTEGER_POSITIVE, INTEGER_NEGATIVE, INTEGER_ZERO_OR_POSITIVE);
    Set<ValueType> identifier =
        EnumSet.of(USERNAME, EMAIL, PHONE_NUMBER, URL, TEXT, LONG_TEXT, LETTER);
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

  private final IdentifiableObjectManager manager = mock(IdentifiableObjectManager.class);
  private final List<IdentifiableObject> metadata = new ArrayList<>();
  private final FhirResourceMappingValidator validator = new FhirResourceMappingValidator(manager);
  private TrackedEntityType person;
  private TrackedEntityAttribute textTea, text2Tea, addressTea, programTea, strayTea;
  private Program program, withoutRegistrationProgram, otherTypeProgram;
  private ProgramStage stage, otherStage;
  private DataElement booleanDe, textDe, numberDe, number2De, strayDe;

  @BeforeEach
  void setUp() {
    person = register(trackedEntityType(uid()));
    textTea = personAttribute(TEXT);
    text2Tea = personAttribute(TEXT);
    addressTea = personAttribute(LONG_TEXT);
    programTea = register(trackedEntityAttribute(uid(), TEXT));
    strayTea = register(trackedEntityAttribute(uid(), TEXT));
    program = register(program(uid(), WITH_REGISTRATION, person, programTea));
    stage = register(programStage(uid(), program));
    booleanDe = stageDataElement(BOOLEAN);
    textDe = stageDataElement(TEXT);
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
    assertEquals(List.of(), validate(validMapping(type)));
    if (type == PATIENT) {
      var withProgram = withEntries(PATIENT_ADDRESS_TEXT, entry(PATIENT_ADDRESS_TEXT, programTea));
      withProgram.setProgram(program);
      assertEquals(List.of(), validate(withProgram));
    }
  }

  @Test
  void validateWithoutLookupResolvesMetadataThroughManagerWithoutAcl() {
    var lookup = lookup(metadata.toArray(IdentifiableObject[]::new));
    when(manager.getNoAcl(any(), anyString()))
        .thenAnswer(call -> lookup.apply(call.getArgument(0), call.getArgument(1)));
    assertEquals(List.of(), validator.validate(validMapping(ENCOUNTER), null));
    verify(manager).getNoAcl(Program.class, program.getUid());
    verify(manager).getNoAcl(ProgramStage.class, stage.getUid());
  }

  @ParameterizedTest
  @CsvSource({
    "PATIENT_FAMILY_NAME, fieldMappings",
    "PATIENT_FAMILY_NAME, target",
    "PATIENT_FAMILY_NAME, sourceType",
    "PATIENT_FAMILY_NAME, source",
    "PATIENT_IDENTIFIER, system",
    "ENCOUNTER_CLASS, code",
    "OBSERVATION_VALUE, code",
    "PATIENT_FAMILY_NAME, resourceType",
    "ENCOUNTER_CLASS, resourceType",
    "PATIENT_FAMILY_NAME, trackedEntityType",
    "ENCOUNTER_CLASS, trackedEntityType"
  })
  void entryWithoutPropertyIsMissingRequiredProperty(FhirTargetField target, String property) {
    String blank = property.equals("system") ? " " : null;
    assertOnly(validate(withValue(target, property, blank)), E4000, property);
  }

  @ParameterizedTest
  @EnumSource(value = FhirResourceType.class, names = "PATIENT", mode = EnumSource.Mode.EXCLUDE)
  void eventMappingWithoutProgramOrStageIsMissingRequiredProperty(FhirResourceType type) {
    FhirResourceMapping withoutProgram = validMapping(type);
    withoutProgram.setProgram(null);
    assertOnly(validate(withoutProgram), E4000, "program");
    FhirResourceMapping withoutStage = validMapping(type);
    withoutStage.setProgramStage(null);
    assertOnly(validate(withoutStage), E4000, "programStage");
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
  void targetOfAnotherResourceTypeIsNotSupported() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    mapping.getFieldMappings().add(observation(numberDe, LOINC_BODY_HEIGHT_CODE, null).build());
    assertOnly(validate(mapping), E4010, "OBSERVATION_VALUE", "PATIENT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"ENCOUNTER_CLASS", "PATIENT_FAMILY_NAME"})
  void sourceTypeNotAllowedForTargetIsNotSupported(FhirTargetField target) {
    var mapping = withEntries(target, Entry.field(target, DATA_ELEMENT, textDe.getUid()));
    assertOnly(validate(mapping), E4010, "DATA_ELEMENT", target.name());
  }

  @Test
  void sourceThatIsNotAUidIsInvalid() {
    var mapping = withValue(PATIENT_FAMILY_NAME, "source", "not-a-uid");
    assertOnly(validate(mapping), E4014, "not-a-uid", "source");
  }

  @ParameterizedTest
  @CsvSource({
    "M, man, F, female, E4027, man",
    "'  ', male, F, female, E4027, '  '",
    "A;B, male, C, male, E4027, A;B",
    "Ä, male, ä, female, E5003, ä",
    "ΟΔΟΣ, other, F, female, E4027, ΟΔΟΣ"
  })
  void genderValueMapOutsideAdministrativeGenderIsInvalid(
      String k1, String v1, String k2, String v2, ErrorCode code, String arg) {
    FhirResourceMapping mapping = validMapping(PATIENT);
    entryOf(mapping, PATIENT_GENDER).setValueMap(new TreeMap<>(Map.of(k1, v1, k2, v2)));
    String id = mapping.getUid();
    String[] args =
        code == E5003 ? new String[] {"valueMap", arg, id, id} : new String[] {arg, "valueMap"};
    assertOnly(validate(mapping), code, args);
  }

  @ParameterizedTest
  @MethodSource("acceptedValueTypes")
  void acceptedValueTypeHasNoValueTypeReport(FhirTargetField target, ValueType valueType) {
    assertEquals(List.of(), validate(withEntries(target, sourceOfValueType(target, valueType))));
  }

  @ParameterizedTest
  @MethodSource("rejectedValueTypes")
  void rejectedValueTypeIsInvalid(FhirTargetField target, ValueType valueType) {
    FhirResourceMapping mapping = withEntries(target, sourceOfValueType(target, valueType));
    assertOnly(validate(mapping), E4027, valueType.name(), target.name());
  }

  static Stream<Arguments> acceptedValueTypes() {
    return ACCEPTED.entrySet().stream()
        .flatMap(e -> e.getValue().stream().map(type -> Arguments.of(e.getKey(), type)));
  }

  static Stream<Arguments> rejectedValueTypes() {
    return ACCEPTED.entrySet().stream()
        .flatMap(
            e ->
                EnumSet.complementOf(EnumSet.copyOf(e.getValue())).stream()
                    .map(type -> Arguments.of(e.getKey(), type)));
  }

  @ParameterizedTest
  @CsvSource({
    "PATIENT, program",
    "PATIENT, trackedEntityType",
    "PATIENT, programStage",
    "ENCOUNTER, programStage"
  })
  void programOrStageOutsideItsScopeIsInvalidReference(FhirResourceType type, String property) {
    FhirResourceMapping mapping = validMapping(type);
    switch (property) {
      case "program" -> mapping.setProgram(withoutRegistrationProgram);
      case "trackedEntityType" -> mapping.setProgram(otherTypeProgram);
      default -> mapping.setProgramStage(type == PATIENT ? stage : otherStage);
    }
    IdentifiableObject reference =
        property.equals("programStage") ? mapping.getProgramStage() : mapping.getProgram();
    assertOnly(validate(mapping), E5002, reference.getUID().getValue(), mapping.getUid(), property);
  }

  @Test
  void sourceOutsideTypeProgramOrStageIsInvalidReference() {
    for (IdentifiableObject source : List.of(strayTea, programTea, strayDe)) {
      FhirTargetField target = source == strayDe ? ENCOUNTER_TYPE : PATIENT_FAMILY_NAME;
      FhirResourceMapping mapping = withEntries(target, entry(target, source));
      assertOnly(
          validate(mapping), E5002, source.getUID().getValue(), mapping.getUid(), target.name());
    }
  }

  @ParameterizedTest
  @EnumSource(FhirResourceType.class)
  void uniquenessKeyAlreadyHeldIsDuplicate(FhirResourceType type) {
    FhirResourceMapping mapping = validMapping(type);
    FhirResourceMapping sameUid = validMapping(type);
    sameUid.setUid(mapping.getUid());
    FhirResourceMapping other = validMapping(type);
    List<ErrorReport> reports = validate(mapping, mapping, sameUid, other);
    String key = uniquenessKey(mapping);
    assertOnly(reports, E5003, "resourceType", key, mapping.getUid(), OTHER_MAPPING);
    String message = reports.get(0).getMessage();
    assertFalse(message.contains(other.getUid()) || message.contains(other.getName()), message);
  }

  @Test
  void immunizationWithDifferentAdministeredDataElementIsAllowed() {
    var other = withEntries(IMMUNIZATION_ADMINISTERED, entry(IMMUNIZATION_ADMINISTERED, textDe));
    assertEquals(List.of(), validate(validMapping(IMMUNIZATION), other));
  }

  @Test
  void repeatedOneCardinalityTargetIsDuplicate() {
    var mapping = withEntries(PATIENT_GIVEN_NAME, entry(PATIENT_FAMILY_NAME, text2Tea));
    String id = mapping.getUid();
    assertOnly(validate(mapping), E5003, "target", "PATIENT_FAMILY_NAME", id, id);
  }

  @Test
  void duplicateIdentifierSystemIsDuplicate() {
    FhirResourceMapping mapping =
        withEntries(PATIENT_ADDRESS_TEXT, entry(PATIENT_IDENTIFIER, addressTea).system(SYSTEM));
    String id = mapping.getUid();
    assertOnly(validate(mapping), E5003, "system", SYSTEM, id, id);
    String tooLong = "urn:x:" + "a".repeat(MAX_TEXT_LENGTH);
    mapping.getFieldMappings().stream()
        .filter(e -> e.getSystem() != null)
        .forEach(e -> e.setSystem(tooLong));
    List<String> args = List.of("system", "1024", String.valueOf(tooLong.length()));
    assertEquals(
        List.of(args, args), validate(mapping).stream().map(ErrorReport::getArgs).toList());
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
    mapping.getFieldMappings().add(observation(numberDe, LOINC_BODY_WEIGHT_CODE, null).build());
    String id = mapping.getUid();
    assertOnly(validate(mapping), E5003, "source", numberDe.getUid(), id, id);
  }

  @Test
  void uniquenessKeyPerResourceType() {
    assertEquals("PATIENT", uniquenessKey(validMapping(PATIENT)));
    assertEquals("ENCOUNTER:" + stage.getUid(), uniquenessKey(validMapping(ENCOUNTER)));
    assertEquals("OBSERVATION:" + stage.getUid(), uniquenessKey(validMapping(OBSERVATION)));
    assertEquals(
        "IMMUNIZATION:" + stage.getUid() + ":" + booleanDe.getUid(),
        uniquenessKey(validMapping(IMMUNIZATION)));
    assertNull(uniquenessKey(null));
    assertNull(uniquenessKey(withValue(PATIENT_FAMILY_NAME, "resourceType", null)));
    assertNull(uniquenessKey(withValue(ENCOUNTER_CLASS, "programStage", null)));
    assertNull(uniquenessKey(withEntries(IMMUNIZATION_ADMINISTERED)));
  }

  @ParameterizedTest
  @CsvSource({
    "OBSERVATION_VALUE, code, '8302-2 ', false",
    "ENCOUNTER_CLASS, code, 'a  b', false",
    "IMMUNIZATION_VACCINE_CODE, code, 'a\tb', false",
    "OBSERVATION_VALUE, code, 'a\u00a0b', false",
    "ENCOUNTER_TYPE, system, 'urn:bad uri', false",
    "IMMUNIZATION_VACCINE_CODE, system, mailto:a@b.c, false",
    "ENCOUNTER_CLASS, system, http:foo, false",
    "PATIENT_IDENTIFIER, system, urn:oid:1.2.3, false",
    "OBSERVATION_VALUE, system, urn:uuid:53FEFA32-FCBB-4FF8-8A92-55EE120877B7, false",
    "OBSERVATION_VALUE, system, " + LDAP + ", false",
    "OBSERVATION_VALUE, code, a b, true",
    "ENCOUNTER_CLASS, code, \u00e4, true",
    "PATIENT_IDENTIFIER, system, " + LDAP + ", true",
    "ENCOUNTER_TYPE, system, https://fhir.example.org:8443/x, true",
    "PATIENT_IDENTIFIER, system, urn:oid:2.16.840.1.113883.6.1, true",
    "OBSERVATION_VALUE, system, urn:uuid:53fefa32-fcbb-4ff8-8a92-55ee120877b7, true"
  })
  void codeOrSystemFollowsR4CodeAndUriRules(
      FhirTargetField target, String property, String value, boolean valid) {
    List<ErrorReport> reports = validate(withValue(target, property, value));
    if (valid) {
      assertEquals(List.of(), reports);
    } else {
      assertOnly(reports, E4027, value, property);
    }
  }

  @Test
  void mappingOverCountOrTotalTextBoundIsTooLong() {
    FhirResourceMapping mapping = validMapping(ENCOUNTER);
    FhirFieldMapping type = entryOf(mapping, ENCOUNTER_TYPE);
    type.setValueMap(pairs(MAX_VALUE_MAP_SIZE));
    List<FhirFieldMapping> entries = mapping.getFieldMappings();
    IntStream.range(entries.size(), MAX_FIELD_MAPPINGS)
        .forEach(i -> entries.add(Entry.constant(ENCOUNTER_TYPE, SYSTEM, "t" + i, null).build()));
    assertEquals(List.of(), validate(mapping));
    type.setValueMap(pairs(101));
    assertOnly(validate(mapping), E4001, "valueMap", "100", "101");
    entries.add(entry(ENCOUNTER_TYPE, strayDe).build());
    assertOnly(validate(mapping), E4001, "fieldMappings", "500", "501");
    FhirResourceMapping text = withEntries(ENCOUNTER_TYPE);
    String coding = ENCOUNTER_CLASS_SYSTEM + ENCOUNTER_CLASS_CODE + ENCOUNTER_CLASS_DISPLAY;
    int free = MAX_TOTAL_TEXT_LENGTH - coding.length();
    Entry reason =
        entry(ENCOUNTER_REASON, textDe).display("d".repeat(1000 - textDe.getUid().length()));
    IntStream.range(0, free / 1000).forEach(i -> text.getFieldMappings().add(reason.build()));
    FhirFieldMapping encounterClass = entryOf(text, ENCOUNTER_CLASS);
    encounterClass.setDisplay(encounterClass.getDisplay() + "d".repeat(free % 1000));
    assertEquals(List.of(), validate(text));
    entryOf(text, ENCOUNTER_REASON).setValueMap(Map.of("d", ""));
    assertOnly(validate(text), E4001, "fieldMappings.text", "100000", "100001");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"source", "system", "code", "display", "unit", "valueMap.key", "valueMap.value"})
  void textLongerThanMaxTextLengthIsTooLong(String property) {
    String urn = "urn:x:" + "a".repeat(MAX_TEXT_LENGTH - "urn:x:".length());
    List<ErrorReport> atBound = validate(withValue(OBSERVATION_VALUE, property, urn));
    assertTrue(atBound.stream().noneMatch(r -> r.getErrorCode() == E4001), atBound::toString);
    var tooLong = withValue(OBSERVATION_VALUE, property, urn + "a");
    assertOnly(validate(tooLong), E4001, property, "1024", "1025");
  }

  private <T extends IdentifiableObject> T register(T object) {
    metadata.add(object);
    return object;
  }

  private TrackedEntityAttribute personAttribute(ValueType valueType) {
    TrackedEntityAttribute attribute = register(trackedEntityAttribute(uid(), valueType));
    person.getTrackedEntityTypeAttributes().add(new TrackedEntityTypeAttribute(person, attribute));
    return attribute;
  }

  private DataElement stageDataElement(ValueType valueType) {
    DataElement dataElement = register(dataElement(uid(), valueType));
    stage.getProgramStageDataElements().add(new ProgramStageDataElement(stage, dataElement));
    return dataElement;
  }

  private FhirResourceMapping validMapping(FhirResourceType type) {
    boolean patient = type == PATIENT;
    FhirResourceMapping mapping =
        mapping(uid(), type, person, patient ? null : program, patient ? null : stage);
    mapping.setFieldMappings(
        switch (type) {
          case PATIENT ->
              entries(
                  entry(PATIENT_IDENTIFIER, personAttribute(INTEGER)).system(SYSTEM),
                  entry(PATIENT_FAMILY_NAME, textTea),
                  entry(PATIENT_GIVEN_NAME, text2Tea),
                  entry(PATIENT_GENDER, personAttribute(TEXT)).valueMap(GENDER_MAP),
                  entry(PATIENT_BIRTH_DATE, personAttribute(DATE)),
                  entry(PATIENT_PHONE, personAttribute(PHONE_NUMBER)),
                  entry(PATIENT_EMAIL, personAttribute(EMAIL)),
                  entry(PATIENT_ADDRESS_TEXT, addressTea));
          case ENCOUNTER -> entries(AMBULATORY, entry(ENCOUNTER_TYPE, textDe).system(SYSTEM));
          case IMMUNIZATION ->
              entries(
                  entry(IMMUNIZATION_ADMINISTERED, booleanDe),
                  Entry.constant(IMMUNIZATION_VACCINE_CODE, CVX_SYSTEM, CVX_CODE, CVX_DISPLAY),
                  entry(IMMUNIZATION_LOT_NUMBER, textDe),
                  entry(IMMUNIZATION_DOSE_NUMBER, stageDataElement(INTEGER)));
          case OBSERVATION ->
              entries(
                  observation(numberDe, LOINC_BODY_HEIGHT_CODE, LOINC_BODY_HEIGHT_DISPLAY)
                      .unit(BODY_HEIGHT_UNIT),
                  observation(number2De, LOINC_BODY_WEIGHT_CODE, LOINC_BODY_WEIGHT_DISPLAY)
                      .unit(BODY_WEIGHT_UNIT));
        });
    return mapping;
  }

  private FhirResourceMapping withEntries(FhirTargetField target, Entry... replacements) {
    FhirResourceMapping mapping = validMapping(target.resourceType());
    mapping.getFieldMappings().removeIf(entry -> entry.getTarget() == target);
    mapping.getFieldMappings().addAll(entries(replacements));
    return mapping;
  }

  private FhirResourceMapping withValue(FhirTargetField target, String property, String value) {
    FhirResourceMapping mapping = validMapping(target.resourceType());
    FhirFieldMapping entry = entryOf(mapping, target);
    switch (property) {
      case "fieldMappings" -> mapping.getFieldMappings().add(null);
      case "target" -> entry.setTarget(null);
      case "sourceType" -> entry.setSourceType(null);
      case "resourceType" -> mapping.setResourceType(null);
      case "trackedEntityType" -> mapping.setTrackedEntityType(null);
      case "programStage" -> mapping.setProgramStage(null);
      case "source" -> entry.setSource(value);
      case "system" -> entry.setSystem(value);
      case "code" -> entry.setCode(value);
      case "display" -> entry.setDisplay(value);
      case "unit" -> entry.setUnit(value);
      case "valueMap.key" -> entry.setValueMap(Map.of(value, "v"));
      case "valueMap.value" -> entry.setValueMap(Map.of("k", value));
      default -> throw new IllegalArgumentException(property);
    }
    return mapping;
  }

  private static FhirFieldMapping entryOf(FhirResourceMapping mapping, FhirTargetField target) {
    return mapping.getFieldMappings().stream()
        .filter(entry -> entry.getTarget() == target)
        .findFirst()
        .orElseThrow();
  }

  private static Entry entry(FhirTargetField target, IdentifiableObject source) {
    FhirSourceType sourceType = target.resourceType() == PATIENT ? ATTRIBUTE : DATA_ELEMENT;
    return Entry.field(target, sourceType, source.getUID().getValue());
  }

  private static Entry observation(DataElement source, String code, String display) {
    return entry(OBSERVATION_VALUE, source).system(LOINC_SYSTEM).code(code).display(display);
  }

  private Entry sourceOfValueType(FhirTargetField target, ValueType valueType) {
    IdentifiableObject source =
        target.resourceType() == PATIENT ? personAttribute(valueType) : stageDataElement(valueType);
    return entry(target, source).system(SYSTEM).code(LOINC_BODY_HEIGHT_CODE).valueMap(GENDER_MAP);
  }

  private static Map<String, String> pairs(int size) {
    return IntStream.range(0, size).boxed().collect(Collectors.toMap(i -> "k" + i, i -> "v" + i));
  }

  private List<ErrorReport> validate(FhirResourceMapping mapping, FhirResourceMapping... others) {
    var lookup = lookup(metadata.toArray(IdentifiableObject[]::new));
    List<ErrorReport> reports = validator.validate(mapping, List.of(others), lookup);
    for (ErrorReport report : reports) {
      assertTrue(VALIDATOR_CODES.contains(report.getErrorCode()), report::toString);
      assertEquals(FhirResourceMapping.class, report.getMainKlass(), report::toString);
    }
    return reports;
  }

  private static void assertOnly(List<ErrorReport> reports, ErrorCode code, String... args) {
    List<String> actual = reports.stream().map(r -> r.getErrorCode() + " " + r.getArgs()).toList();
    assertEquals(List.of(code + " " + List.of(args)), actual);
  }
}
