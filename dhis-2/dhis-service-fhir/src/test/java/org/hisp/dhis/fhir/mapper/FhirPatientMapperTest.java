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
package org.hisp.dhis.fhir.mapper;

import static java.util.stream.Collectors.toSet;
import static org.hisp.dhis.common.ValueType.*;
import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.PATIENT;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.ATTRIBUTE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.webapi.controller.tracker.view.Attribute;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link FhirPatientMapper}. */
class FhirPatientMapperTest {
  private static final String TE_UID = "TePatient01";
  private static final String TRACKED_ENTITY_TYPE = "TetPerson01";
  private static final String TEA_NATIONAL_ID = "TeaNationId";
  private static final String TEA_INTEGER = "TeaIntegerA";
  private static final String TEA_FAMILY = "TeaFamilyNm";
  private static final String TEA_GIVEN = "TeaGivenNam";
  private static final String TEA_GENDER = "TeaGenderCd";
  private static final String TEA_BIRTH = "TeaBirthDat";
  private static final String TEA_AGE = "TeaAgeValue";
  private static final String TEA_PHONE = "TeaPhoneNum";
  private static final String TEA_EMAIL = "TeaEmailAdr";
  private static final String TEA_ADDRESS = "TeaAddressT";
  private static final Map<String, ValueType> VALUE_TYPES =
      Map.of(
          TEA_INTEGER, INTEGER,
          TEA_BIRTH, DATE,
          TEA_AGE, AGE,
          TEA_PHONE, PHONE_NUMBER,
          TEA_EMAIL, EMAIL);
  private static final String INTEGER_IDENTIFIER_SYSTEM = "urn:dhis2:fhir-test:integer-attr";
  private static final String NATIONAL_ID = "NID-1985-0412";
  private static final String INTEGER_ID = "70";
  private static final String FAMILY = "Nordmann";
  private static final String GIVEN = "Kari";
  private static final String BIRTH_DATE = "1985-04-12";
  private static final String PHONE = "+4722334455";
  private static final String EMAIL_ADDR = "kari.nordmann@example.org";
  private static final String ADDRESS = "Storgata 1, 0155 Oslo";
  private static final List<Attribute> EMPTY_MAPPING_VALUES =
      List.of(
          teaValue(TEA_NATIONAL_ID, "EMPTY-ID"), teaValue(TEA_FAMILY, "EMPTY-FAMILY"),
          teaValue(TEA_GIVEN, "EMPTY-GIVEN"), teaValue(TEA_GENDER, "EMPTY-GENDER"),
          teaValue(TEA_BIRTH, "1971-01-01"), teaValue(TEA_PHONE, "+4799887766"),
          teaValue(TEA_EMAIL, "empty@example.org"), teaValue(TEA_ADDRESS, "EMPTY-ADDRESS"));
  private final FhirPatientMapper mapper = new FhirPatientMapper(new FhirValueConverter());

  @Test
  void mapsEveryPatientTarget() {
    Patient patient = mapFullPatient();
    assertEquals(TE_UID, patient.getIdElement().getIdPart());
    assertEquals(UPDATED, patient.getMeta().getLastUpdated().toInstant());
    assertEquals(
        List.of(
            IDENTIFIER_SYSTEM + "|" + NATIONAL_ID, INTEGER_IDENTIFIER_SYSTEM + "|" + INTEGER_ID),
        patient.getIdentifier().stream().map(id -> id.getSystem() + "|" + id.getValue()).toList());
    assertEquals(List.of(FAMILY + "|" + GIVEN), names(patient));
    assertEquals(FEMALE, patient.getGender());
    assertEquals(BIRTH_DATE, patient.getBirthDateElement().getValueAsString());
    assertEquals(
        List.of("PHONE|" + PHONE, "EMAIL|" + EMAIL_ADDR),
        patient.getTelecom().stream().map(c -> c.getSystem() + "|" + c.getValue()).toList());
    assertEquals(List.of(ADDRESS), patient.getAddress().stream().map(Address::getText).toList());
    Patient byAge =
        map(patientMapping(attr(PATIENT_BIRTH_DATE, TEA_AGE)), teaValue(TEA_AGE, "2019-05-17"));
    assertEquals("2019-05-17", byAge.getBirthDateElement().getValueAsString());
  }

  @Test
  void singleGivenName() {
    ResolvedMapping mapping =
        patientMapping(attr(PATIENT_FAMILY_NAME, TEA_FAMILY), attr(PATIENT_GIVEN_NAME, TEA_GIVEN));
    Attribute[] values = {
      teaValue(TEA_FAMILY, FAMILY), teaValue(TEA_GIVEN, GIVEN), teaValue(TEA_GIVEN, "Ola")
    };
    assertEquals(List.of(FAMILY + "|" + GIVEN), names(map(mapping, values)));
  }

  @Test
  void genderValueMapAndUnknownValues() {
    ResolvedMapping mapping =
        genderMapping(
            Map.of("M", "male", "F", "female", "O", "other", "U", "unknown", "Q", "nonbinary"));
    Map.of("M", MALE, "F", FEMALE, "O", OTHER, "U", UNKNOWN, "f", FEMALE, "m", MALE)
        .forEach((source, expected) -> assertEquals(expected, gender(mapping, source), source));
    List.of("X", "Q").forEach(source -> assertNull(gender(mapping, source), source));
    Map<String, String> caseDistinct = new LinkedHashMap<>(Map.of("F", "female"));
    caseDistinct.put("f", "other");
    assertEquals(OTHER, gender(genderMapping(caseDistinct), "f"));
    ResolvedMapping dottedAndGreek = genderMapping(Map.of("i", "other", "οδοσ", "unknown"));
    assertEquals(OTHER, gender(dottedAndGreek, "İ"));
    assertEquals(UNKNOWN, gender(dottedAndGreek, "ΟΔΟΣ"));
    ResolvedMapping capitalI = genderMapping(Map.of("I", "male"));
    Locale locale = Locale.getDefault();
    try {
      Locale.setDefault(Locale.ENGLISH);
      assertEquals(MALE, gender(capitalI, "i"));
      assertNull(gender(capitalI, "ı"));
      Locale.setDefault(Locale.forLanguageTag("tr"));
      assertEquals(MALE, gender(capitalI, "ı"));
      assertNull(gender(capitalI, "i"));
    } finally {
      Locale.setDefault(locale);
    }
  }

  @Test
  void omitsUnmappedAndMissingValues() {
    ResolvedMapping mapping =
        patientMapping(
            attr(PATIENT_FAMILY_NAME, TEA_FAMILY), attr(PATIENT_GIVEN_NAME, TEA_GIVEN),
            attr(PATIENT_PHONE, TEA_PHONE), attr(PATIENT_BIRTH_DATE, TEA_BIRTH));
    Attribute family = teaValue(TEA_FAMILY, FAMILY);
    Attribute unmapped = teaValue("TeaUnmapped", "UNMAPPED-VALUE");
    Patient patient = map(mapping, family, teaValue(TEA_BIRTH, "not-a-date"), unmapped);
    assertEquals(List.of(FAMILY + "|"), names(patient));
    assertFalse(patient.hasTelecom());
    assertFalse(patient.hasBirthDate());
    assertFalse(FhirR4Validation.encode(patient).contains("UNMAPPED-VALUE"));
  }

  @Test
  void emptyMappingYieldsOnlyStructuralElements() {
    Patient patient = mapEmptyMappingPatient();
    assertEquals(Set.of("id", "meta"), populatedElements(patient));
    assertEquals(Set.of("lastUpdated"), populatedElements(patient.getMeta()));
    String json = FhirR4Validation.encode(patient);
    EMPTY_MAPPING_VALUES.forEach(a -> assertFalse(json.contains(a.getValue()), a.getValue()));
  }

  @Test
  void outputIsValidR4() {
    FhirR4Validation.assertValid(mapFullPatient());
    FhirR4Validation.assertValid(mapEmptyMappingPatient());
  }

  private Patient map(ResolvedMapping mapping, Attribute... attributes) {
    return mapper.map(trackedEntity(TE_UID, TRACKED_ENTITY_TYPE, UPDATED, attributes), mapping);
  }

  private Patient mapFullPatient() {
    ResolvedMapping mapping =
        patientMapping(
            attr(PATIENT_IDENTIFIER, TEA_NATIONAL_ID).system(IDENTIFIER_SYSTEM),
            attr(PATIENT_IDENTIFIER, TEA_INTEGER).system(INTEGER_IDENTIFIER_SYSTEM),
            attr(PATIENT_FAMILY_NAME, TEA_FAMILY),
            attr(PATIENT_GIVEN_NAME, TEA_GIVEN),
            attr(PATIENT_GENDER, TEA_GENDER).valueMap(Map.of("F", "female", "M", "male")),
            attr(PATIENT_BIRTH_DATE, TEA_BIRTH),
            attr(PATIENT_PHONE, TEA_PHONE),
            attr(PATIENT_EMAIL, TEA_EMAIL),
            attr(PATIENT_ADDRESS_TEXT, TEA_ADDRESS));
    Attribute[] values = {
      teaValue(TEA_NATIONAL_ID, NATIONAL_ID), teaValue(TEA_INTEGER, INTEGER_ID),
      teaValue(TEA_FAMILY, FAMILY), teaValue(TEA_GIVEN, GIVEN),
      teaValue(TEA_GENDER, "F"), teaValue(TEA_BIRTH, BIRTH_DATE),
      teaValue(TEA_PHONE, PHONE), teaValue(TEA_EMAIL, EMAIL_ADDR),
      teaValue(TEA_ADDRESS, ADDRESS)
    };
    return map(mapping, values);
  }

  private Patient mapEmptyMappingPatient() {
    return map(
        resolved(PATIENT, TRACKED_ENTITY_TYPE, null, null, List.of(), Map.of()),
        EMPTY_MAPPING_VALUES.toArray(Attribute[]::new));
  }

  private static ResolvedMapping genderMapping(Map<String, String> valueMap) {
    return patientMapping(attr(PATIENT_GENDER, TEA_GENDER).valueMap(valueMap));
  }

  private Enumerations.AdministrativeGender gender(ResolvedMapping mapping, String source) {
    return map(mapping, teaValue(TEA_GENDER, source)).getGender();
  }

  private static ResolvedMapping patientMapping(Entry... fields) {
    List<FhirFieldMapping> built = entries(fields);
    var types = new LinkedHashMap<String, ValueType>();
    built.forEach(e -> types.put(e.getSource(), VALUE_TYPES.getOrDefault(e.getSource(), TEXT)));
    return resolved(PATIENT, TRACKED_ENTITY_TYPE, null, null, built, types);
  }

  private static Attribute teaValue(String tea, String value) {
    return attribute(tea, VALUE_TYPES.getOrDefault(tea, TEXT), value);
  }

  private static Entry attr(FhirTargetField target, String tea) {
    return Entry.field(target, ATTRIBUTE, tea);
  }

  private static Set<String> populatedElements(Base element) {
    return element.children().stream()
        .filter(Property::hasValues)
        .map(Property::getName)
        .collect(toSet());
  }

  private static List<String> names(Patient patient) {
    return patient.getName().stream()
        .map(name -> name.getFamily() + "|" + name.getGivenAsSingleString())
        .toList();
  }
}
