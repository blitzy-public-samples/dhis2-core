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

import static org.hisp.dhis.common.ValueType.AGE;
import static org.hisp.dhis.common.ValueType.DATE;
import static org.hisp.dhis.common.ValueType.EMAIL;
import static org.hisp.dhis.common.ValueType.INTEGER;
import static org.hisp.dhis.common.ValueType.PHONE_NUMBER;
import static org.hisp.dhis.common.ValueType.TEXT;
import static org.hisp.dhis.fhir.FhirTestFixtures.IDENTIFIER_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.UPDATED;
import static org.hisp.dhis.fhir.FhirTestFixtures.attribute;
import static org.hisp.dhis.fhir.FhirTestFixtures.entries;
import static org.hisp.dhis.fhir.FhirTestFixtures.resolved;
import static org.hisp.dhis.fhir.FhirTestFixtures.trackedEntity;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.PATIENT;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.ATTRIBUTE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_ADDRESS_TEXT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_BIRTH_DATE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_EMAIL;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_FAMILY_NAME;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_GENDER;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_GIVEN_NAME;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_IDENTIFIER;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_PHONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.FhirTestFixtures.Entry;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.webapi.controller.tracker.view.Attribute;
import org.hisp.dhis.webapi.controller.tracker.view.TrackedEntity;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Property;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link FhirPatientMapper}: every Patient target of the mapping, the single name,
 * the gender value map, the omission of unmapped and missing values, the structural-only output of
 * a mapping without entries, and FHIR R4 validity of the mapped Patients.
 */
class FhirPatientMapperTest {
  private static final String TE_UID = "TePatient01";

  private static final String OTHER_TE_UID = "TePatient02";

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

  private static final String TEA_UNMAPPED = "TeaUnmapped";

  private static final Map<String, ValueType> VALUE_TYPES =
      Map.ofEntries(
          Map.entry(TEA_NATIONAL_ID, TEXT),
          Map.entry(TEA_INTEGER, INTEGER),
          Map.entry(TEA_FAMILY, TEXT),
          Map.entry(TEA_GIVEN, TEXT),
          Map.entry(TEA_GENDER, TEXT),
          Map.entry(TEA_BIRTH, DATE),
          Map.entry(TEA_AGE, AGE),
          Map.entry(TEA_PHONE, PHONE_NUMBER),
          Map.entry(TEA_EMAIL, EMAIL),
          Map.entry(TEA_ADDRESS, TEXT),
          Map.entry(TEA_UNMAPPED, TEXT));

  private static final String INTEGER_IDENTIFIER_SYSTEM = "urn:dhis2:fhir-test:integer-attr";

  private static final String NATIONAL_ID = "NID-1985-0412";

  private static final String INTEGER_ID = "70";

  private static final String FAMILY = "Nordmann";

  private static final String GIVEN = "Kari";

  private static final String BIRTH_DATE = "1985-04-12";

  private static final String PHONE = "+4722334455";

  private static final String EMAIL_ADDRESS = "kari.nordmann@example.org";

  private static final String ADDRESS = "Storgata 1, 0155 Oslo";

  private final FhirPatientMapper mapper = new FhirPatientMapper(new FhirValueConverter());

  @Test
  void mapsEveryPatientTarget() {
    Patient patient = mapFullPatient();

    assertEquals(TE_UID, patient.getIdElement().getIdPart());
    assertEquals(UPDATED, patient.getMeta().getLastUpdated().toInstant());

    List<Identifier> identifiers = patient.getIdentifier();
    assertEquals(2, identifiers.size());
    assertEquals(IDENTIFIER_SYSTEM, identifiers.get(0).getSystem());
    assertEquals(NATIONAL_ID, identifiers.get(0).getValue());
    assertEquals(INTEGER_IDENTIFIER_SYSTEM, identifiers.get(1).getSystem());
    assertEquals(INTEGER_ID, identifiers.get(1).getValue());

    assertEquals(1, patient.getName().size());
    HumanName name = patient.getName().get(0);
    assertEquals(FAMILY, name.getFamily());
    assertEquals(1, name.getGiven().size());
    assertEquals(GIVEN, name.getGiven().get(0).getValue());

    assertEquals(AdministrativeGender.FEMALE, patient.getGender());
    assertEquals(BIRTH_DATE, patient.getBirthDateElement().getValueAsString());

    assertEquals(2, patient.getTelecom().size());
    assertEquals(
        Set.of(
            ContactPointSystem.PHONE + "|" + PHONE, ContactPointSystem.EMAIL + "|" + EMAIL_ADDRESS),
        patient.getTelecom().stream()
            .map(FhirPatientMapperTest::systemAndValue)
            .collect(Collectors.toSet()));

    assertEquals(1, patient.getAddress().size());
    assertEquals(ADDRESS, patient.getAddress().get(0).getText());

    Patient byAge =
        mapper.map(
            trackedEntity(
                OTHER_TE_UID, TRACKED_ENTITY_TYPE, UPDATED, teaValue(TEA_AGE, "2019-05-17")),
            patientMapping(Entry.field(PATIENT_BIRTH_DATE, ATTRIBUTE, TEA_AGE)));
    assertEquals(OTHER_TE_UID, byAge.getIdElement().getIdPart());
    assertEquals("2019-05-17", byAge.getBirthDateElement().getValueAsString());
  }

  @Test
  void singleGivenName() {
    Patient patient =
        mapper.map(
            trackedEntity(
                TE_UID,
                TRACKED_ENTITY_TYPE,
                UPDATED,
                teaValue(TEA_FAMILY, FAMILY),
                teaValue(TEA_GIVEN, GIVEN),
                teaValue(TEA_GIVEN, "Ola")),
            patientMapping(
                Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, TEA_FAMILY),
                Entry.field(PATIENT_GIVEN_NAME, ATTRIBUTE, TEA_GIVEN)));

    assertEquals(1, patient.getName().size());
    HumanName name = patient.getName().get(0);
    assertEquals(FAMILY, name.getFamily());
    assertEquals(1, name.getGiven().size());
    assertEquals(GIVEN, name.getGiven().get(0).getValue());
  }

  @Test
  void genderValueMapAndUnknownValues() {
    ResolvedMapping mapping =
        patientMapping(
            Entry.field(PATIENT_GENDER, ATTRIBUTE, TEA_GENDER)
                .valueMap(
                    Map.of(
                        "M",
                        "male",
                        "F",
                        "female",
                        "O",
                        "other",
                        "U",
                        "unknown",
                        "Q",
                        "nonbinary")));
    Map<String, AdministrativeGender> expected = new LinkedHashMap<>();
    expected.put("M", AdministrativeGender.MALE);
    expected.put("F", AdministrativeGender.FEMALE);
    expected.put("O", AdministrativeGender.OTHER);
    expected.put("U", AdministrativeGender.UNKNOWN);

    for (String source : expected.keySet()) {
      Patient patient = mapper.map(genderTrackedEntity(source), mapping);
      assertEquals(expected.get(source), patient.getGender(), "gender of source value " + source);
    }

    for (String source : List.of("X", "f", "Q")) {
      Patient patient = mapper.map(genderTrackedEntity(source), mapping);
      assertFalse(patient.hasGender(), "gender of source value " + source);
    }
  }

  @Test
  void omitsUnmappedAndMissingValues() {
    Patient patient =
        mapper.map(
            trackedEntity(
                TE_UID,
                TRACKED_ENTITY_TYPE,
                UPDATED,
                teaValue(TEA_FAMILY, FAMILY),
                teaValue(TEA_BIRTH, "not-a-date"),
                teaValue(TEA_UNMAPPED, "UNMAPPED-VALUE")),
            patientMapping(
                Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, TEA_FAMILY),
                Entry.field(PATIENT_GIVEN_NAME, ATTRIBUTE, TEA_GIVEN),
                Entry.field(PATIENT_PHONE, ATTRIBUTE, TEA_PHONE),
                Entry.field(PATIENT_BIRTH_DATE, ATTRIBUTE, TEA_BIRTH)));

    assertEquals(1, patient.getName().size());
    assertEquals(FAMILY, patient.getName().get(0).getFamily());
    assertFalse(patient.getName().get(0).hasGiven());
    assertFalse(patient.hasTelecom());
    assertFalse(patient.hasBirthDate());
    assertFalse(FhirR4Validation.encode(patient).contains("UNMAPPED-VALUE"));
  }

  @Test
  void emptyMappingYieldsOnlyStructuralElements() {
    Patient patient = mapEmptyMappingPatient();

    assertEquals(TE_UID, patient.getIdElement().getIdPart());
    assertEquals(UPDATED, patient.getMeta().getLastUpdated().toInstant());
    assertEquals(Set.of("id", "meta"), populatedElements(patient));
    assertEquals(Set.of("lastUpdated"), populatedElements(patient.getMeta()));
    assertFalse(patient.hasIdentifier());
    assertFalse(patient.hasName());
    assertFalse(patient.hasGender());
    assertFalse(patient.hasBirthDate());
    assertFalse(patient.hasTelecom());
    assertFalse(patient.hasAddress());

    String json = FhirR4Validation.encode(patient);
    for (Attribute attribute : emptyMappingAttributes()) {
      assertFalse(json.contains(attribute.getValue()), "encoded value " + attribute.getValue());
    }
  }

  @Test
  void outputIsValidR4() {
    Patient full = mapFullPatient();
    assertTrue(full.hasIdentifier() && full.hasName() && full.hasTelecom() && full.hasAddress());

    FhirR4Validation.assertValid(full);
    FhirR4Validation.assertValid(mapEmptyMappingPatient());
  }

  /** Maps a tracked entity with a value for every Patient target through a full mapping. */
  private Patient mapFullPatient() {
    return mapper.map(
        trackedEntity(
            TE_UID,
            TRACKED_ENTITY_TYPE,
            UPDATED,
            teaValue(TEA_NATIONAL_ID, NATIONAL_ID),
            teaValue(TEA_INTEGER, INTEGER_ID),
            teaValue(TEA_FAMILY, FAMILY),
            teaValue(TEA_GIVEN, GIVEN),
            teaValue(TEA_GENDER, "F"),
            teaValue(TEA_BIRTH, BIRTH_DATE),
            teaValue(TEA_PHONE, PHONE),
            teaValue(TEA_EMAIL, EMAIL_ADDRESS),
            teaValue(TEA_ADDRESS, ADDRESS)),
        patientMapping(
            Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, TEA_NATIONAL_ID).system(IDENTIFIER_SYSTEM),
            Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, TEA_INTEGER)
                .system(INTEGER_IDENTIFIER_SYSTEM),
            Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, TEA_FAMILY),
            Entry.field(PATIENT_GIVEN_NAME, ATTRIBUTE, TEA_GIVEN),
            Entry.field(PATIENT_GENDER, ATTRIBUTE, TEA_GENDER)
                .valueMap(Map.of("F", "female", "M", "male")),
            Entry.field(PATIENT_BIRTH_DATE, ATTRIBUTE, TEA_BIRTH),
            Entry.field(PATIENT_PHONE, ATTRIBUTE, TEA_PHONE),
            Entry.field(PATIENT_EMAIL, ATTRIBUTE, TEA_EMAIL),
            Entry.field(PATIENT_ADDRESS_TEXT, ATTRIBUTE, TEA_ADDRESS)));
  }

  /** Maps a tracked entity with distinctive attribute values through a mapping without entries. */
  private Patient mapEmptyMappingPatient() {
    return mapper.map(
        trackedEntity(
            TE_UID,
            TRACKED_ENTITY_TYPE,
            UPDATED,
            emptyMappingAttributes().toArray(Attribute[]::new)),
        resolved(PATIENT, TRACKED_ENTITY_TYPE, null, null, List.of(), Map.of()));
  }

  private static List<Attribute> emptyMappingAttributes() {
    return List.of(
        teaValue(TEA_NATIONAL_ID, "EMPTY-MAPPING-NATIONAL-ID"),
        teaValue(TEA_FAMILY, "EMPTY-MAPPING-FAMILY"),
        teaValue(TEA_GIVEN, "EMPTY-MAPPING-GIVEN"),
        teaValue(TEA_GENDER, "EMPTY-MAPPING-GENDER"),
        teaValue(TEA_BIRTH, "1971-01-01"),
        teaValue(TEA_PHONE, "+4799887766"),
        teaValue(TEA_EMAIL, "empty.mapping@example.org"),
        teaValue(TEA_ADDRESS, "EMPTY-MAPPING-ADDRESS"));
  }

  private static TrackedEntity genderTrackedEntity(String source) {
    return trackedEntity(TE_UID, TRACKED_ENTITY_TYPE, UPDATED, teaValue(TEA_GENDER, source));
  }

  /** Builds a PATIENT mapping whose value types are those of the entries' attributes. */
  private static ResolvedMapping patientMapping(Entry... fields) {
    List<FhirFieldMapping> built = entries(fields);
    Map<String, ValueType> valueTypes = new LinkedHashMap<>();
    for (FhirFieldMapping entry : built) {
      valueTypes.put(entry.getSource(), VALUE_TYPES.get(entry.getSource()));
    }
    return resolved(PATIENT, TRACKED_ENTITY_TYPE, null, null, built, valueTypes);
  }

  /** Builds an attribute DTO carrying the value type the attribute has in {@link #VALUE_TYPES}. */
  private static Attribute teaValue(String tea, String value) {
    return attribute(tea, VALUE_TYPES.get(tea), value);
  }

  private static String systemAndValue(ContactPoint contactPoint) {
    return contactPoint.getSystem() + "|" + contactPoint.getValue();
  }

  /** Returns the names of the element's child properties that hold a non-empty value. */
  private static Set<String> populatedElements(Base element) {
    return element.children().stream()
        .filter(Property::hasValues)
        .map(Property::getName)
        .collect(Collectors.toSet());
  }
}
