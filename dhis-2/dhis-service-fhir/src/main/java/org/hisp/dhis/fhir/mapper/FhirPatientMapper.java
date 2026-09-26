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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirSourceType;
import org.hisp.dhis.fhir.mapping.FhirTargetField;
import org.hisp.dhis.webapi.controller.tracker.view.Attribute;
import org.hisp.dhis.webapi.controller.tracker.view.TrackedEntity;
import org.hl7.fhir.r4.model.ContactPoint.ContactPointSystem;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

/**
 * Maps a Tracker {@link TrackedEntity}, as returned by the tracked entity export, to a FHIR R4
 * {@link Patient} through a resolved {@code PATIENT} mapping.
 *
 * <p>Structural elements come from the tracked entity itself:
 *
 * <ul>
 *   <li>{@code id}: the tracked entity UID, when present;
 *   <li>{@code meta.lastUpdated}: {@code updatedAt}, when present.
 * </ul>
 *
 * <p>Mapped elements come only from the mapping's {@link FhirSourceType#ATTRIBUTE} entries. Each
 * entry reads the value of the tracked entity attribute whose UID is its {@code source}:
 *
 * <ul>
 *   <li>{@code identifier}: one per {@link FhirTargetField#PATIENT_IDENTIFIER} entry, in entry
 *       order, with the entry's {@code system} and the attribute value;
 *   <li>{@code name[0].family}: the {@link FhirTargetField#PATIENT_FAMILY_NAME} value;
 *   <li>{@code name[0].given[0]}: the {@link FhirTargetField#PATIENT_GIVEN_NAME} value. The name is
 *       present only when its family or given part has a value;
 *   <li>{@code gender}: the {@link FhirTargetField#PATIENT_GENDER} value translated by the entry's
 *       {@code valueMap} (exact key match), when the translation is {@code male}, {@code female},
 *       {@code other} or {@code unknown};
 *   <li>{@code birthDate}: the {@link FhirTargetField#PATIENT_BIRTH_DATE} value converted by {@link
 *       FhirValueConverter#toDate} for the attribute's value type;
 *   <li>{@code telecom}: one {@code phone} contact point per {@link FhirTargetField#PATIENT_PHONE}
 *       entry, then one {@code email} contact point per {@link FhirTargetField#PATIENT_EMAIL}
 *       entry, each in entry order;
 *   <li>{@code address[0].text}: the {@link FhirTargetField#PATIENT_ADDRESS_TEXT} value.
 * </ul>
 *
 * <p>An element is omitted when the mapping has no entry for its target, when the tracked entity
 * carries no non-blank value for the entry's attribute, or when the value cannot be translated or
 * converted. Identifier, name, telecom and address values are the attribute values verbatim. When
 * the tracked entity lists an attribute more than once, its first non-blank value is used. No other
 * Patient element is set.
 *
 * <p>Instances are stateless and thread-safe.
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

  /**
   * Creates a mapper that converts dates and timestamps with the given converter.
   *
   * @param converter the value converter; must not be {@code null}
   */
  public FhirPatientMapper(@Nonnull FhirValueConverter converter) {
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  /**
   * Maps the tracked entity to a Patient through the mapping's entries.
   *
   * @param trackedEntity the tracked entity as returned by the export, with the attribute values
   *     the requesting user may read
   * @param mapping the resolved {@code PATIENT} mapping
   * @return a new Patient holding the structural elements and every mapped element that has a
   *     value; with a mapping without entries, only {@code id} and {@code meta.lastUpdated}
   */
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

  /** Adds one identifier per {@code PATIENT_IDENTIFIER} entry whose attribute has a value. */
  private static void mapIdentifiers(
      Patient patient, ResolvedMapping mapping, Map<String, String> values) {
    for (FhirFieldMapping entry : mapping.entries(FhirTargetField.PATIENT_IDENTIFIER)) {
      value(entry, values)
          .ifPresent(value -> patient.addIdentifier().setSystem(entry.getSystem()).setValue(value));
    }
  }

  /** Adds one name with the family and given values, when at least one of them is present. */
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

  /** Sets the gender when the entry's value map translates the value to a gender code. */
  private static void mapGender(
      Patient patient, ResolvedMapping mapping, Map<String, String> values) {
    mapping
        .entry(FhirTargetField.PATIENT_GENDER)
        .flatMap(entry -> value(entry, values).map(value -> translate(entry, value)))
        .filter(ADMINISTRATIVE_GENDER_CODES::contains)
        .map(AdministrativeGender::fromCode)
        .ifPresent(patient::setGender);
  }

  /** Sets the birth date when the value converts to a date for the attribute's value type. */
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

  /** Adds one contact point of the given system per entry of the target whose value is present. */
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

  /** Adds one address whose text is the {@code PATIENT_ADDRESS_TEXT} value, when present. */
  private static void mapAddress(
      Patient patient, ResolvedMapping mapping, Map<String, String> values) {
    singleValue(mapping, FhirTargetField.PATIENT_ADDRESS_TEXT, values)
        .ifPresent(value -> patient.addAddress().setText(value));
  }

  /** Returns the value of the first entry of the target, or empty when it has none. */
  private static Optional<String> singleValue(
      ResolvedMapping mapping, FhirTargetField target, Map<String, String> values) {
    return mapping.entry(target).flatMap(entry -> value(entry, values));
  }

  /**
   * Returns the attribute value an entry reads: present only for an {@link
   * FhirSourceType#ATTRIBUTE} entry with a {@code source} whose attribute has a non-blank value.
   */
  private static Optional<String> value(FhirFieldMapping entry, Map<String, String> values) {
    if (entry.getSourceType() != FhirSourceType.ATTRIBUTE || entry.getSource() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(values.get(entry.getSource()));
  }

  /** Returns the code the entry's value map holds for the value, or {@code null} when none. */
  @CheckForNull
  private static String translate(FhirFieldMapping entry, String value) {
    Map<String, String> valueMap = entry.getValueMap();
    return valueMap == null ? null : valueMap.get(value);
  }

  /**
   * Collects the attribute values by attribute UID, skipping entries without a UID or with a null
   * or blank value; for a repeated UID the first remaining value is kept.
   */
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
