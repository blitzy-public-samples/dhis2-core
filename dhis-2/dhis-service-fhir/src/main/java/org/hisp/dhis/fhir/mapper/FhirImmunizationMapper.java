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

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirTargetField;
import org.hisp.dhis.webapi.controller.tracker.view.DataValue;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.Event;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.Immunization.ImmunizationStatus;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;
import org.springframework.stereotype.Component;

/**
 * Maps one Tracker event of an {@code IMMUNIZATION} mapping's program stage to at most one FHIR R4
 * {@link Immunization}. One mapping describes one vaccine.
 *
 * <p>An Immunization is produced only when both of these hold:
 *
 * <ul>
 *   <li>the mapping has an {@link FhirTargetField#IMMUNIZATION_ADMINISTERED} entry and the event
 *       carries a non-blank value for that entry's data element;
 *   <li>the event has an {@code occurredAt} date. {@code scheduledAt} is never read.
 * </ul>
 *
 * <p>Otherwise {@link #map} returns {@link Optional#empty()}.
 *
 * <p>Elements of the produced resource and their sources:
 *
 * <ul>
 *   <li>{@code id}: {@code {enrollmentUid}-{eventUid}-{administeredDataElementUid}}, see {@link
 *       FhirLogicalId#perDataElement(String, String, String)}.
 *   <li>{@code meta.lastUpdated}: the event's {@code updatedAt}, when present.
 *   <li>{@code status}: {@code not-done} when the administered value is exactly {@code false},
 *       otherwise {@code completed}.
 *   <li>{@code vaccineCode}: the coding ({@code system}, {@code code}, {@code display}) of the
 *       {@link FhirTargetField#IMMUNIZATION_VACCINE_CODE} constant entry, when present.
 *   <li>{@code patient}: {@code Patient/{trackedEntityUid}} of the enrollment.
 *   <li>{@code encounter}: {@code Encounter/{enrollmentUid}-{eventUid}}, only when the caller
 *       states that an {@code ENCOUNTER} mapping exists for the same program stage.
 *   <li>{@code occurrenceDateTime}: the event's {@code occurredAt}.
 *   <li>{@code lotNumber}: the event's value of the {@link FhirTargetField#IMMUNIZATION_LOT_NUMBER}
 *       data element, when present.
 *   <li>{@code protocolApplied[0].doseNumber[x]}: the event's value of the {@link
 *       FhirTargetField#IMMUNIZATION_DOSE_NUMBER} data element, when present, as {@code
 *       doseNumberPositiveInt} when the trimmed value is a positive decimal integer that fits in an
 *       {@code int}, and as {@code doseNumberString} holding the value verbatim otherwise.
 * </ul>
 *
 * <p>No other element is set. The mapper reads only the given DTOs and mapping and performs no
 * lookup. Instances are stateless and thread-safe.
 *
 * <p>Example:
 *
 * <pre>{@code
 * FhirImmunizationMapper mapper = new FhirImmunizationMapper(new FhirValueConverter());
 * Optional<Immunization> immunization =
 *     mapper.map(enrollment, event, resolvedImmunizationMapping, encounterMappedForStage);
 * }</pre>
 */
@Component
public class FhirImmunizationMapper {
  private static final String PATIENT_REFERENCE_PREFIX = "Patient/";

  private static final String ENCOUNTER_REFERENCE_PREFIX = "Encounter/";

  private static final String NOT_ADMINISTERED = "false";

  private static final Pattern POSITIVE_INTEGER = Pattern.compile("^[1-9][0-9]*$");

  private static final int MAX_INT_DIGITS = String.valueOf(Integer.MAX_VALUE).length();

  private final FhirValueConverter converter;

  /**
   * Creates the mapper.
   *
   * @param converter converts Tracker timestamps to FHIR {@code instant} and {@code dateTime}
   */
  public FhirImmunizationMapper(@Nonnull FhirValueConverter converter) {
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  /**
   * Maps one event to an Immunization.
   *
   * @param enrollment the enrollment the event belongs to, carrying its enrollment and tracked
   *     entity UIDs
   * @param event an event of the mapping's program stage, carrying its UID, dates and the data
   *     values the current user may read
   * @param mapping the resolved {@code IMMUNIZATION} mapping of the event's program stage
   * @param encounterMapped whether an {@code ENCOUNTER} mapping exists for the same program stage;
   *     when {@code true} the Immunization references the event's Encounter
   * @return the Immunization, or empty when the mapping has no administered entry, the event has no
   *     non-blank value for its data element, or the event has no {@code occurredAt}
   */
  @Nonnull
  public Optional<Immunization> map(
      @Nonnull Enrollment enrollment,
      @Nonnull Event event,
      @Nonnull ResolvedMapping mapping,
      boolean encounterMapped) {
    Optional<FhirFieldMapping> administered =
        mapping.entry(FhirTargetField.IMMUNIZATION_ADMINISTERED);
    if (administered.isEmpty()) {
      return Optional.empty();
    }

    Map<String, String> values = dataValues(event.getDataValues());
    String administeredDataElement = administered.get().getSource();
    String administeredValue = valueOf(values, administeredDataElement);
    Instant occurredAt = event.getOccurredAt();
    if (administeredValue == null || occurredAt == null) {
      return Optional.empty();
    }

    String enrollmentUid = enrollment.getEnrollment().getValue();
    String eventUid = event.getEvent().getValue();

    Immunization immunization = new Immunization();
    immunization.setId(
        FhirLogicalId.perDataElement(enrollmentUid, eventUid, administeredDataElement).compose());

    Instant updatedAt = event.getUpdatedAt();
    if (updatedAt != null) {
      immunization.getMeta().setLastUpdatedElement(converter.instant(updatedAt));
    }

    immunization.setStatus(
        NOT_ADMINISTERED.equals(administeredValue)
            ? ImmunizationStatus.NOTDONE
            : ImmunizationStatus.COMPLETED);

    mapping
        .entry(FhirTargetField.IMMUNIZATION_VACCINE_CODE)
        .ifPresent(
            entry ->
                immunization.setVaccineCode(
                    new CodeableConcept()
                        .addCoding(
                            new Coding(entry.getSystem(), entry.getCode(), entry.getDisplay()))));

    immunization.setPatient(
        new Reference(PATIENT_REFERENCE_PREFIX + enrollment.getTrackedEntity().getValue()));

    if (encounterMapped) {
      immunization.setEncounter(
          new Reference(
              ENCOUNTER_REFERENCE_PREFIX
                  + FhirLogicalId.encounter(enrollmentUid, eventUid).compose()));
    }

    immunization.setOccurrence(converter.dateTime(occurredAt));

    String lotNumber = mappedValue(mapping, FhirTargetField.IMMUNIZATION_LOT_NUMBER, values);
    if (lotNumber != null) {
      immunization.setLotNumber(lotNumber);
    }

    String doseNumber = mappedValue(mapping, FhirTargetField.IMMUNIZATION_DOSE_NUMBER, values);
    if (doseNumber != null) {
      immunization.addProtocolApplied().setDoseNumber(doseNumberType(doseNumber));
    }

    return Optional.of(immunization);
  }

  /**
   * Returns the event's value of the data element of the first entry with the given target.
   *
   * @return the value, or {@code null} when the mapping has no such entry, the entry has no source,
   *     or the event has no non-blank value for it
   */
  @CheckForNull
  private static String mappedValue(
      ResolvedMapping mapping, FhirTargetField target, Map<String, String> values) {
    return mapping.entry(target).map(entry -> valueOf(values, entry.getSource())).orElse(null);
  }

  /**
   * Returns the value of the given data element.
   *
   * @return the value, or {@code null} when the data element UID is {@code null} or has no value
   */
  @CheckForNull
  private static String valueOf(Map<String, String> values, @CheckForNull String dataElement) {
    return dataElement == null ? null : values.get(dataElement);
  }

  /**
   * Collects the event's data values by data element UID. Data values that are {@code null}, have
   * no data element, or have a {@code null} or blank value are skipped. When a data element occurs
   * more than once, the first value encountered is kept.
   *
   * @param dataValues the event's data values; {@code null} is treated as empty
   * @return the values by data element UID
   */
  private static Map<String, String> dataValues(@CheckForNull Collection<DataValue> dataValues) {
    Map<String, String> values = new LinkedHashMap<>();
    if (dataValues == null) {
      return values;
    }
    for (DataValue dataValue : dataValues) {
      if (dataValue == null
          || dataValue.getDataElement() == null
          || dataValue.getValue() == null
          || dataValue.getValue().isBlank()) {
        continue;
      }
      values.putIfAbsent(dataValue.getDataElement(), dataValue.getValue());
    }
    return values;
  }

  /**
   * Converts a dose number value to {@link PositiveIntType} when the trimmed value is a positive
   * decimal integer without leading zeros that fits in an {@code int}, and to {@link StringType}
   * holding the value verbatim otherwise.
   *
   * @param value the non-blank dose number value
   * @return the {@code doseNumber[x]} datatype
   */
  private static Type doseNumberType(String value) {
    String trimmed = value.trim();
    if (trimmed.length() <= MAX_INT_DIGITS && POSITIVE_INTEGER.matcher(trimmed).matches()) {
      long number = Long.parseLong(trimmed);
      if (number <= Integer.MAX_VALUE) {
        return new PositiveIntType((int) number);
      }
    }
    return new StringType(value);
  }
}
