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
package org.hisp.dhis.fhir.mapper;

import java.util.*;
import javax.annotation.*;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.webapi.controller.tracker.view.Attribute;
import org.hisp.dhis.webapi.controller.tracker.view.TrackedEntity;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.springframework.stereotype.Component;

/**
 * Maps a Tracker {@link TrackedEntity} to a FHIR R4 {@link Patient} with {@code id} and {@code
 * meta.lastUpdated} from its UID and {@code updatedAt}, and elements from the first non-blank value
 * of each attribute a {@link FhirSourceType#ATTRIBUTE} entry names: one {@code identifier} with the
 * entry's {@code system} per {@code PATIENT_IDENTIFIER} entry, {@code name[0]} family and given,
 * {@code gender} as the {@code valueMap} translation of the key equal to the value, else of the
 * first key that {@link FhirResourceMappingValidator#genderKeyMatches} it, when that is {@code
 * male}, {@code female}, {@code other} or {@code unknown}, {@code birthDate} converted by {@link
 * FhirValueConverter#toDate}, one {@code phone} then one {@code email} {@code telecom} per entry,
 * and {@code address[0].text}, omitting an element whose value is missing or unconvertible.
 */
@Component
public class FhirPatientMapper {
  private static final Set<String> ADMINISTRATIVE_GENDER_CODES =
      Set.of(
          AdministrativeGender.MALE.toCode(),
          AdministrativeGender.FEMALE.toCode(),
          AdministrativeGender.OTHER.toCode(),
          AdministrativeGender.UNKNOWN.toCode());
  private final FhirValueConverter converter;

  public FhirPatientMapper(@Nonnull FhirValueConverter converter) {
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  @Nonnull
  public Patient map(@Nonnull TrackedEntity trackedEntity, @Nonnull ResolvedMapping mapping) {
    Objects.requireNonNull(trackedEntity, "trackedEntity");
    Objects.requireNonNull(mapping, "mapping");
    Patient patient = new Patient();
    UID uid = trackedEntity.getTrackedEntity();
    if (uid != null) {
      patient.setId(uid.getValue());
    }
    if (trackedEntity.getUpdatedAt() != null) {
      patient.getMeta().setLastUpdatedElement(converter.instant(trackedEntity.getUpdatedAt()));
    }
    Map<String, String> values = attributeValues(trackedEntity.getAttributes());
    mapIdentifiers(patient, mapping, values);
    mapName(patient, mapping, values);
    mapGender(patient, mapping, values);
    mapBirthDate(patient, mapping, values);
    mapTelecom(patient, mapping, values, FhirTargetField.PATIENT_PHONE, ContactPointSystem.PHONE);
    mapTelecom(patient, mapping, values, FhirTargetField.PATIENT_EMAIL, ContactPointSystem.EMAIL);
    mapAddress(patient, mapping, values);
    return patient;
  }

  private static void mapIdentifiers(
      Patient patient, ResolvedMapping mapping, Map<String, String> values) {
    for (FhirFieldMapping entry : mapping.entries(FhirTargetField.PATIENT_IDENTIFIER)) {
      value(entry, values)
          .ifPresent(value -> patient.addIdentifier().setSystem(entry.getSystem()).setValue(value));
    }
  }

  private static void mapName(
      Patient patient, ResolvedMapping mapping, Map<String, String> values) {
    Optional<String> family = singleValue(mapping, FhirTargetField.PATIENT_FAMILY_NAME, values);
    Optional<String> given = singleValue(mapping, FhirTargetField.PATIENT_GIVEN_NAME, values);
    if (family.isEmpty() && given.isEmpty()) {
      return;
    }
    HumanName name = patient.addName();
    family.ifPresent(name::setFamily);
    given.ifPresent(name::addGiven);
  }

  private static void mapGender(
      Patient patient, ResolvedMapping mapping, Map<String, String> values) {
    mapping
        .entry(FhirTargetField.PATIENT_GENDER)
        .flatMap(entry -> value(entry, values).map(value -> translate(entry, value)))
        .filter(ADMINISTRATIVE_GENDER_CODES::contains)
        .map(AdministrativeGender::fromCode)
        .ifPresent(patient::setGender);
  }

  private void mapBirthDate(Patient patient, ResolvedMapping mapping, Map<String, String> values) {
    mapping
        .entry(FhirTargetField.PATIENT_BIRTH_DATE)
        .flatMap(
            entry ->
                value(entry, values)
                    .flatMap(
                        value ->
                            converter.toDate(mapping.valueTypes().get(entry.getSource()), value)))
        .ifPresent(patient::setBirthDateElement);
  }

  private static void mapTelecom(
      Patient patient,
      ResolvedMapping mapping,
      Map<String, String> values,
      FhirTargetField target,
      ContactPointSystem system) {
    for (FhirFieldMapping entry : mapping.entries(target)) {
      value(entry, values)
          .ifPresent(value -> patient.addTelecom().setSystem(system).setValue(value));
    }
  }

  private static void mapAddress(
      Patient patient, ResolvedMapping mapping, Map<String, String> values) {
    singleValue(mapping, FhirTargetField.PATIENT_ADDRESS_TEXT, values)
        .ifPresent(value -> patient.addAddress().setText(value));
  }

  private static Optional<String> singleValue(
      ResolvedMapping mapping, FhirTargetField target, Map<String, String> values) {
    return mapping.entry(target).flatMap(entry -> value(entry, values));
  }

  private static Optional<String> value(FhirFieldMapping entry, Map<String, String> values) {
    if (entry.getSourceType() != FhirSourceType.ATTRIBUTE || entry.getSource() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(values.get(entry.getSource()));
  }

  @CheckForNull
  private static String translate(FhirFieldMapping entry, String value) {
    Map<String, String> valueMap = entry.getValueMap();
    if (valueMap == null) {
      return null;
    }
    if (valueMap.containsKey(value)) {
      return valueMap.get(value);
    }
    for (Map.Entry<String, String> mapped : valueMap.entrySet()) {
      if (mapped.getKey() != null
          && FhirResourceMappingValidator.genderKeyMatches(mapped.getKey(), value)) {
        return mapped.getValue();
      }
    }
    return null;
  }

  private static Map<String, String> attributeValues(@CheckForNull List<Attribute> attributes) {
    Map<String, String> values = new HashMap<>();
    if (attributes == null) {
      return values;
    }
    for (Attribute attribute : attributes) {
      if (attribute == null) {
        continue;
      }
      String uid = attribute.getAttribute();
      String value = attribute.getValue();
      if (uid != null && value != null && !value.isBlank()) {
        values.putIfAbsent(uid, value);
      }
    }
    return values;
  }
}
