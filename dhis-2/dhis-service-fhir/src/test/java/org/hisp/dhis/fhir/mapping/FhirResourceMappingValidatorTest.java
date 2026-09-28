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

class FhirResourceMappingValidatorTest {
  private static final String SYSTEM = "urn:test:id";
  private static final String LDAP = "ldap://directory.example.org/ids";
  private static final Map<String, String> GENDER_MAP = Map.of("M", "male", "F", "female");
  private static final Map<FhirTargetField, Set<ValueType>> ACCEPTED =
      new EnumMap<>(FhirTargetField.class);

  static {
    catalog()
        .filter(c -> !c[5].equals("none"))
        .forEach(c -> ACCEPTED.put(FhirTargetField.valueOf(c[0]), parse(ValueType.class, c[5])));
  }

  private final IdentifiableObjectManager manager = mock(IdentifiableObjectManager.class);
  private final List<IdentifiableObject> metadata = new ArrayList<>();
  private final FhirResourceMappingValidator validator = new FhirResourceMappingValidator(manager);
  private TrackedEntityType person;
  private TrackedEntityAttribute textTea;
  private TrackedEntityAttribute text2Tea;
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
  private DataElement numberDe;
  private DataElement number2De;
  private DataElement strayDe;

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
  void validMappingOfEachTypeHasNoReportsUnlessItsKeyIsHeld(FhirResourceType type) {
    assertEquals(List.of(), validate(validMapping(type)));
    if (type == PATIENT) {
      var withProgram = withEntries(PATIENT_ADDRESS_TEXT, entry(PATIENT_ADDRESS_TEXT, programTea));
      withProgram.setProgram(program);
      assertEquals(List.of(), validate(withProgram));
    }
    FhirResourceMapping mapping = validMapping(type);
    FhirResourceMapping sameUid = validMapping(type);
    sameUid.setUid(mapping.getUid());
    FhirResourceMapping other = validMapping(type);
    List<ErrorReport> reports = validate(mapping, mapping, sameUid, other);
    String administered = type == IMMUNIZATION ? ", " + IMMUNIZATION_ADMINISTERED : "";
    String property = type == PATIENT ? "resourceType" : "programStage" + administered;
    String value = uniquenessKey(mapping).replaceFirst("^[A-Z]+:", "").replace(":", ", ");
    assertOnly(reports, E5003, property, value, describe(mapping), OTHER_MAPPING);
    String message = reports.get(0).getMessage();
    assertFalse(message.contains(other.getUid()) || message.contains(other.getName()), message);
  }

  @Test
  void catalogSourceReferencesDuplicatesAndStructureOnlyRulesFollowExpectedTables() {
    FhirResourceMapping mapping = validMapping(PATIENT);
    mapping.getFieldMappings().add(observation(numberDe, LOINC_BODY_HEIGHT_CODE, null).build());
    assertOnly(validate(mapping), E4010, "OBSERVATION_VALUE", "PATIENT");
    List<String[]> rows = catalog().toList();
    assertEquals(rows.size(), FhirTargetField.values().length);
    for (FhirTargetField target : FhirTargetField.values()) {
      String[] c = rows.get(target.ordinal());
      assertEquals(c[0] + " " + c[1], target + " " + target.resourceType(), c[0]);
      assertEquals(parse(FhirSourceType.class, c[2]), target.allowedSources(), c[0]);
      assertEquals(c[3] + " " + c[4], target.cardinality() + " " + target.isRequired(), c[0]);
      assertEquals(parse(ValueType.class, c[5]), target.acceptedValueTypes(), c[0]);
      if (c[4].equals("true")) {
        assertOnly(validate(withEntries(target)), E4000, target.name());
      }
    }
    assertEquals("[ATTRIBUTE, DATA_ELEMENT, CONSTANT]", Arrays.toString(FhirSourceType.values()));
    FhirResourceType[] types = FhirResourceType.values();
    assertEquals("[PATIENT, ENCOUNTER, IMMUNIZATION, OBSERVATION]", Arrays.toString(types));
    List<String> fhirTypes = Stream.of(types).map(FhirResourceType::fhirType).toList();
    assertEquals(List.of("Patient", "Encounter", "Immunization", "Observation"), fhirTypes);
    for (IdentifiableObject source : List.of(strayTea, programTea, strayDe)) {
      FhirTargetField target = source == strayDe ? ENCOUNTER_TYPE : PATIENT_FAMILY_NAME;
      FhirResourceMapping m = withEntries(target, entry(target, source));
      assertOnly(
          validate(m), E5002, source.getUID().getValue(), describe(m), at(m, target, "source"));
    }
    mapping = withValue(PATIENT_FAMILY_NAME, "source", "not-a-uid");
    assertOnly(validate(mapping), E4014, "not-a-uid", at(mapping, PATIENT_FAMILY_NAME, "source"));
    mapping =
        withEntries(PATIENT_ADDRESS_TEXT, entry(PATIENT_IDENTIFIER, addressTea).system(SYSTEM));
    int last = mapping.getFieldMappings().size() - 1;
    assertOnly(validate(mapping), E5003, "system", SYSTEM, row(last), row(0));
    mapping = validMapping(PATIENT);
    entryOf(mapping, PATIENT_GIVEN_NAME).setSource(textTea.getUid());
    assertOnly(validate(mapping), E5003, "source", textTea.getUid(), row(2), row(1));
    mapping = validMapping(OBSERVATION);
    mapping.getFieldMappings().add(observation(numberDe, LOINC_BODY_WEIGHT_CODE, null).build());
    assertOnly(validate(mapping), E5003, "source", numberDe.getUid(), row(2), row(0));
    var other = withEntries(IMMUNIZATION_ADMINISTERED, entry(IMMUNIZATION_ADMINISTERED, textDe));
    assertEquals(List.of(), validate(validMapping(IMMUNIZATION), other));
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
    mapping = validMapping(PATIENT);
    var gender = entryOf(mapping, PATIENT_GENDER);
    gender.setValueMap(Map.of("ΟΔΟΣ", "other", "οδος", "other", "I", "unknown", "ı", "female"));
    assertEquals(List.of(), validate(mapping));
    gender.setValueMap(Map.of("A;B", "male"));
    assertEquals(List.of(), validate(mapping));
    assertTrue(genderKeyMatches("I", "i") && genderKeyMatches("İ", "i"));
    assertTrue(genderKeyMatches("ΟΔΟΣ", "οδοσ") && genderKeyMatches("A;B", "a;b"));
    assertFalse(genderKeyMatches("I", "ı") || genderKeyMatches("ΟΔΟΣ", "οδος"));
    mapping = withEntries(ENCOUNTER_TYPE, entry(ENCOUNTER_TYPE, dataElement(uid(), BOOLEAN)));
    mapping.setTrackedEntityType(trackedEntityType(uid()));
    mapping.setProgram(withoutRegistrationProgram);
    mapping.setProgramStage(otherStage);
    assertEquals(List.of(), validator.validateStructure(mapping));
    entryOf(mapping, ENCOUNTER_TYPE).setTarget(null);
    assertOnly(validator.validateStructure(mapping), E4000, at(mapping, null, "target"));
    verifyNoInteractions(manager);
    var lookup = lookup(metadata.toArray(IdentifiableObject[]::new));
    when(manager.getNoAcl(any(), anyString()))
        .thenAnswer(call -> lookup.apply(call.getArgument(0), call.getArgument(1)));
    assertEquals(List.of(), validator.validate(validMapping(ENCOUNTER), null));
    verify(manager).getNoAcl(Program.class, program.getUid());
    verify(manager).getNoAcl(ProgramStage.class, stage.getUid());
  }

  @ParameterizedTest
  @CsvSource({
    "M, man, F, female, E4027, man", "'  ', male, F, female, E4027, '  '",
    "A;B, male, F, female, E4027, A;B", "A;B, male, C, male, E4027, A;B",
    "Ä, male, ä, female, E5003, ä", "ΟΔΟΣ, other, οδοσ, female, E5003, οδοσ"
  })
  void genderValueMapOutsideAdministrativeGenderIsInvalid(
      String k1, String v1, String k2, String v2, ErrorCode code, String arg) {
    FhirResourceMapping mapping = validMapping(PATIENT);
    entryOf(mapping, PATIENT_GENDER).setValueMap(new TreeMap<>(Map.of(k1, v1, k2, v2)));
    String row = at(mapping, PATIENT_GENDER, "");
    String[] args =
        code == E5003
            ? new String[] {"valueMap", arg, row, row + " key `" + k1 + "`"}
            : new String[] {arg, row + ".valueMap"};
    assertOnly(validate(mapping), code, args);
  }

  @ParameterizedTest
  @MethodSource("valueTypes")
  void valueTypeOutsideTargetsAcceptedTypesIsInvalid(FhirTargetField target, ValueType valueType) {
    FhirSourceType sourceType = target.resourceType() == PATIENT ? ATTRIBUTE : DATA_ELEMENT;
    var reports = validate(withEntries(target, sourceOf(target, sourceType, valueType)));
    if (ACCEPTED.get(target).contains(valueType)) {
      assertEquals(List.of(), reports);
    } else {
      assertOnly(reports, E4027, valueType.name(), target.name());
    }
  }

  static Stream<Arguments> valueTypes() {
    return ACCEPTED.keySet().stream()
        .flatMap(target -> Stream.of(ValueType.values()).map(type -> Arguments.of(target, type)));
  }

  static Stream<Arguments> sourceTypes() {
    return catalog()
        .flatMap(
            c -> Stream.of(FhirSourceType.values()).map(s -> Arguments.of(c[0], s, c[2], c[3])));
  }

  @ParameterizedTest
  @CsvSource({
    "PATIENT, program", "PATIENT, trackedEntityType",
    "PATIENT, programStage", "ENCOUNTER, programStage"
  })
  void programOrStageOutsideItsScopeIsInvalidReference(FhirResourceType type, String property) {
    FhirResourceMapping mapping = validMapping(type);
    switch (property) {
      case "program" -> mapping.setProgram(withoutRegistrationProgram);
      case "trackedEntityType" -> mapping.setProgram(otherTypeProgram);
      default -> mapping.setProgramStage(type == PATIENT ? stage : otherStage);
    }
    var ref = property.equals("programStage") ? mapping.getProgramStage() : mapping.getProgram();
    assertOnly(validate(mapping), E5002, ref.getUID().getValue(), describe(mapping), property);
  }

  @ParameterizedTest
  @CsvSource({"name, FHIR shared name", "code, FHIR-shared", "code, ' '"})
  void nameOrCodeAlreadyHeldIsDuplicateWithoutNamingTheHolder(String property, String value) {
    FhirResourceMapping mapping = validMapping(ENCOUNTER);
    FhirResourceMapping sameUid = validMapping(OBSERVATION);
    sameUid.setUid(mapping.getUid());
    FhirResourceMapping other = validMapping(IMMUNIZATION);
    List.of(mapping, sameUid, other)
        .forEach(property.equals("name") ? m -> m.setName(value) : m -> m.setCode(value));
    assertEquals(List.of(), validate(mapping, mapping, sameUid));
    List<ErrorReport> reports = validate(mapping, sameUid, other);
    assertOnly(reports, E5003, property, value, describe(mapping), OTHER_MAPPING);
    String message = reports.get(0).getMessage();
    boolean holderNamed = !property.equals("name") && message.contains(other.getName());
    assertFalse(message.contains(other.getUid()) || holderNamed, message);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "\u00a0\u3000", "\uFEFF"})
  void blankNameIsMissingRequiredNameAndNotComparedWithOthers(String name) {
    FhirResourceMapping mapping = validMapping(PATIENT);
    FhirResourceMapping other = validMapping(ENCOUNTER);
    mapping.setName(name);
    other.setName(name);
    assertOnly(validate(mapping, other), E4000, "name");
    assertOnly(validator.validateStructure(mapping), E4000, "name");
    mapping.getFieldMappings().get(0).setDisplay("d".repeat(1025));
    var reports = validator.validateStructure(mapping).stream();
    var expected = List.of("E4000 [name]", "E4027 [length 1025 > 1024, " + row(0) + ".display]");
    assertEquals(expected, reports.map(r -> r.getErrorCode() + " " + r.getArgs()).toList());
  }

  @ParameterizedTest
  @MethodSource("sourceTypes")
  void sourceTypesAndRepeatedEntriesFollowExpectedCatalog(
      FhirTargetField target, FhirSourceType type, String sources, Cardinality cardinality) {
    ValueType valueType = ACCEPTED.getOrDefault(target, Set.of(TEXT)).iterator().next();
    FhirResourceMapping mapping = withEntries(target, sourceOf(target, type, valueType));
    if (!parse(FhirSourceType.class, sources).contains(type)) {
      assertOnly(validate(mapping), E4010, type.name(), target.name());
      return;
    }
    assertEquals(List.of(), validate(mapping));
    Entry repeat = sourceOf(target, type, valueType).system(SYSTEM + 2).code("2");
    mapping.getFieldMappings().add(repeat.build());
    int last = mapping.getFieldMappings().size() - 1;
    if (cardinality == Cardinality.ONE) {
      assertOnly(validate(mapping), E5003, "target", target.name(), row(last), row(last - 1));
    } else {
      assertEquals(List.of(), validate(mapping));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "OBSERVATION_VALUE, code, '8302-2 ', E4027", "PATIENT_IDENTIFIER, system, " + LDAP + ",",
    "PATIENT_IDENTIFIER, system, urn:oid:1.02.3, E4027", "ENCOUNTER_CLASS, code, 'a  b', E4027",
    "ENCOUNTER_CLASS, system, http:foo,", "OBSERVATION_VALUE, system, " + LDAP + ",",
    "IMMUNIZATION_VACCINE_CODE, system, mailto:a@b.c,", "ENCOUNTER_CLASS, code, \u00e4,",
    "PATIENT_IDENTIFIER, system, urn:oid:1.2.3,", "OBSERVATION_VALUE, code, a b,",
    "IMMUNIZATION_VACCINE_CODE, code, 'a\tb', E4027", "OBSERVATION_VALUE, code, 'a\u00a0b', E4027",
    "ENCOUNTER_TYPE, system, 'urn:bad uri', E4027", "ENCOUNTER_TYPE, system, codes/local, E4027",
    "PATIENT_FAMILY_NAME, fieldMappings,, E4000", "PATIENT_FAMILY_NAME, target,, E4000",
    "PATIENT_FAMILY_NAME, sourceType,, E4000", "PATIENT_FAMILY_NAME, source,, E4000",
    "PATIENT_IDENTIFIER, system, ' ', E4000", "ENCOUNTER_CLASS, code,, E4000",
    "OBSERVATION_VALUE, code,, E4000", "PATIENT_FAMILY_NAME, resourceType,, E4000",
    "ENCOUNTER_CLASS, resourceType,, E4000", "PATIENT_FAMILY_NAME, trackedEntityType,, E4000",
    "ENCOUNTER_CLASS, trackedEntityType,, E4000", "ENCOUNTER_CLASS, program,, E4000",
    "IMMUNIZATION_VACCINE_CODE, program,, E4000", "OBSERVATION_VALUE, program,, E4000",
    "ENCOUNTER_CLASS, programStage,, E4000", "IMMUNIZATION_VACCINE_CODE, programStage,, E4000",
    "OBSERVATION_VALUE, programStage,, E4000"
  })
  @CsvSource("OBSERVATION_VALUE, system, urn:uuid:53FEFA32-FCBB-4FF8-8A92-55EE120877B7, E4027")
  @CsvSource("OBSERVATION_VALUE, system, urn:uuid:53fefa32-fcbb-4ff8-8a92-55ee120877b7,")
  @CsvSource("PATIENT_IDENTIFIER, system, ftp://fhir.example.org/codes,")
  @CsvSource("ENCOUNTER_TYPE, system, https://fhir.example.org:8443/x,")
  @CsvSource("PATIENT_IDENTIFIER, system, urn:oid:2.16.840.1.113883.6.1,")
  void requiredPropertyCodeAndSystemFollowR4Rules(
      FhirTargetField target, String property, String value, ErrorCode code) {
    FhirResourceMapping mapping = withValue(target, property, value);
    if (code == null) {
      assertEquals(List.of(), validate(mapping));
    } else if (code == E4000) {
      assertOnly(validate(mapping), code, at(mapping, target, property));
    } else {
      assertOnly(validate(mapping), code, value, at(mapping, target, property));
    }
  }

  @ParameterizedTest
  @CsvSource({"entries, fieldMappings, size 501 > 500", "pairs, valueMap, size 101 > 100"})
  @CsvSource("total, fieldMappings, length 100001 > 100000")
  @CsvSource(
      "text, source system code display unit valueMap.key valueMap.value, length 1025 > 1024")
  void mappingOverASizeBoundGetsOnlyThatReport(String bound, String properties, String value) {
    for (String property : properties.split(" ")) {
      assertEquals(List.of(), validate(sized(bound, property, 0)), property);
      FhirResourceMapping mapping = sized(bound, property, 1);
      String path = property.equals("fieldMappings") ? property : row(0) + "." + property;
      assertOnly(validate(mapping), E4027, value, path);
      assertOnly(validator.validateStructure(mapping), E4027, value, path);
      assertOnly(validator.validate(mapping, List.of()), E4027, value, path);
    }
    verifyNoInteractions(manager);
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
    var mapping = mapping(uid(), type, person, patient ? null : program, patient ? null : stage);
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
      case "program" -> mapping.setProgram(null);
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

  private FhirResourceMapping sized(String bound, String property, int excess) {
    if (bound.equals("text")) {
      return withValue(ENCOUNTER_CLASS, property, "urn:" + "a".repeat(1020) + " ".repeat(excess));
    }
    FhirResourceMapping mapping = validMapping(ENCOUNTER);
    List<FhirFieldMapping> fields = mapping.getFieldMappings();
    switch (bound) {
      case "entries" -> fields.addAll(Collections.nCopies(498 + excess, fields.get(1)));
      case "pairs" -> {
        Map<String, String> pairs = new HashMap<>();
        IntStream.range(0, 100 + excess).forEach(i -> pairs.put("k" + i, "v"));
        fields.get(0).setValueMap(pairs);
      }
      default -> {
        fields.set(0, Entry.constant(ENCOUNTER_CLASS, null, "c", "d".repeat(999 + excess)).build());
        fields.set(1, Entry.constant(ENCOUNTER_TYPE, null, "c", "d".repeat(999)).build());
        fields.addAll(Collections.nCopies(98, fields.get(1)));
      }
    }
    return mapping;
  }

  private static String at(FhirResourceMapping mapping, FhirTargetField target, String property) {
    if (property.matches("resourceType|trackedEntityType|program|programStage")) return property;
    List<FhirFieldMapping> entries = mapping.getFieldMappings();
    if (property.equals("fieldMappings")) return row(entries.lastIndexOf(null));
    FhirTargetField wanted = property.equals("target") ? null : target;
    var entry = entries.stream().filter(e -> e != null && e.getTarget() == wanted).findFirst();
    return row(entries.indexOf(entry.orElseThrow())) + (property.isEmpty() ? "" : "." + property);
  }

  private static FhirFieldMapping entryOf(FhirResourceMapping mapping, FhirTargetField target) {
    var matching = mapping.getFieldMappings().stream().filter(e -> e.getTarget() == target);
    return matching.findFirst().orElseThrow();
  }

  private static Entry entry(FhirTargetField target, IdentifiableObject source) {
    FhirSourceType sourceType = target.resourceType() == PATIENT ? ATTRIBUTE : DATA_ELEMENT;
    return Entry.field(target, sourceType, source.getUID().getValue());
  }

  private static Entry observation(DataElement source, String code, String display) {
    return entry(OBSERVATION_VALUE, source).system(LOINC_SYSTEM).code(code).display(display);
  }

  private Entry sourceOf(FhirTargetField target, FhirSourceType type, ValueType valueType) {
    IdentifiableObject source = type == ATTRIBUTE ? personAttribute(valueType) : null;
    source = type == DATA_ELEMENT ? stageDataElement(valueType) : source;
    Entry entry = Entry.field(target, type, source == null ? null : source.getUID().getValue());
    return entry.system(SYSTEM).code(LOINC_BODY_HEIGHT_CODE).valueMap(GENDER_MAP);
  }

  static Stream<String[]> catalog() {
    return """
        PATIENT_IDENTIFIER PATIENT ATTRIBUTE MANY false TEXT,LONG_TEXT,LETTER,USERNAME,EMAIL,\
        PHONE_NUMBER,URL,INTEGER,INTEGER_POSITIVE,INTEGER_NEGATIVE,INTEGER_ZERO_OR_POSITIVE
        PATIENT_FAMILY_NAME PATIENT ATTRIBUTE ONE false TEXT,LONG_TEXT,LETTER
        PATIENT_GIVEN_NAME PATIENT ATTRIBUTE ONE false TEXT,LONG_TEXT,LETTER
        PATIENT_GENDER PATIENT ATTRIBUTE ONE false TEXT,LONG_TEXT,LETTER
        PATIENT_BIRTH_DATE PATIENT ATTRIBUTE ONE false DATE,AGE
        PATIENT_PHONE PATIENT ATTRIBUTE MANY false PHONE_NUMBER,TEXT
        PATIENT_EMAIL PATIENT ATTRIBUTE MANY false EMAIL,TEXT
        PATIENT_ADDRESS_TEXT PATIENT ATTRIBUTE ONE false TEXT,LONG_TEXT,LETTER
        ENCOUNTER_CLASS ENCOUNTER CONSTANT ONE true none
        ENCOUNTER_TYPE ENCOUNTER DATA_ELEMENT,CONSTANT MANY false TEXT,LONG_TEXT,LETTER
        ENCOUNTER_REASON ENCOUNTER DATA_ELEMENT MANY false TEXT,LONG_TEXT,LETTER
        IMMUNIZATION_ADMINISTERED IMMUNIZATION DATA_ELEMENT ONE true BOOLEAN,TRUE_ONLY,TEXT
        IMMUNIZATION_VACCINE_CODE IMMUNIZATION CONSTANT ONE true none
        IMMUNIZATION_LOT_NUMBER IMMUNIZATION DATA_ELEMENT ONE false TEXT,LONG_TEXT,LETTER
        IMMUNIZATION_DOSE_NUMBER IMMUNIZATION DATA_ELEMENT ONE false TEXT,INTEGER,\
        INTEGER_POSITIVE,INTEGER_NEGATIVE,INTEGER_ZERO_OR_POSITIVE
        OBSERVATION_VALUE OBSERVATION DATA_ELEMENT MANY true !FILE_RESOURCE,IMAGE,COORDINATE,\
        GEOJSON,ORGANISATION_UNIT,REFERENCE"""
        .lines()
        .map(row -> row.split(" "));
  }

  private static <E extends Enum<E>> EnumSet<E> parse(Class<E> type, String names) {
    EnumSet<E> set = EnumSet.noneOf(type);
    Stream.of(names.replace("!", "").split(","))
        .filter(name -> !name.equals("none"))
        .forEach(name -> set.add(Enum.valueOf(type, name)));
    return names.startsWith("!") ? EnumSet.complementOf(set) : set;
  }

  private List<ErrorReport> validate(FhirResourceMapping mapping, FhirResourceMapping... others) {
    var lookup = lookup(metadata.toArray(IdentifiableObject[]::new));
    List<ErrorReport> reports = validator.validate(mapping, List.of(others), lookup);
    var validatorCodes = EnumSet.of(E4000, E4010, E4014, E4027, E5002, E5003);
    for (ErrorReport report : reports) {
      assertTrue(validatorCodes.contains(report.getErrorCode()), report::toString);
      assertEquals(FhirResourceMapping.class, report.getMainKlass(), report::toString);
    }
    return reports;
  }

  private static void assertOnly(List<ErrorReport> reports, ErrorCode code, String... args) {
    List<String> actual = reports.stream().map(r -> r.getErrorCode() + " " + r.getArgs()).toList();
    assertEquals(List.of(code + " " + List.of(args)), actual);
  }
}
