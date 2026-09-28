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
import java.util.*;
import java.util.regex.*;
import javax.annotation.*;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.webapi.controller.tracker.view.*;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Immunization.ImmunizationStatus;
import org.springframework.stereotype.Component;

/** Maps an administered Tracker event with an occurrence to a FHIR R4 {@link Immunization}. */
@Component
public class FhirImmunizationMapper {
  private static final String PATIENT_REFERENCE_PREFIX = "Patient/";
  private static final String ENCOUNTER_REFERENCE_PREFIX = "Encounter/";
  private static final Pattern POSITIVE_INTEGER = Pattern.compile("0*([1-9][0-9]{0,9})");
  private final FhirValueConverter converter;

  public FhirImmunizationMapper(@Nonnull FhirValueConverter converter) {
    this.converter = Objects.requireNonNull(converter, "converter");
  }

  /** Maps the event; empty without an administered value or an {@code occurredAt}. */
  @Nonnull
  public Optional<Immunization> map(
      @Nonnull Enrollment enrollment,
      @Nonnull Event event,
      @Nonnull ResolvedMapping mapping,
      boolean encounterMapped) {
    Objects.requireNonNull(enrollment, "enrollment must not be null");
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(mapping, "mapping must not be null");
    Optional<FhirFieldMapping> administered =
        mapping.entry(FhirTargetField.IMMUNIZATION_ADMINISTERED);
    if (administered.isEmpty()) {
      return Optional.empty();
    }
    Map<String, String> values = FhirValueConverter.dataValues(event.getDataValues());
    String administeredDataElement = administered.get().getSource();
    String administeredValue = valueOf(values, administeredDataElement);
    Instant occurredAt = event.getOccurredAt();
    if (administeredValue == null || occurredAt == null) {
      return Optional.empty();
    }
    String enrollmentUid =
        FhirValueConverter.uid(enrollment.getEnrollment(), "enrollment UID must not be null");
    String eventUid = FhirValueConverter.uid(event.getEvent(), "event UID must not be null");
    String trackedEntityUid =
        FhirValueConverter.uid(
            enrollment.getTrackedEntity(), "enrollment tracked entity UID must not be null");
    Immunization immunization = new Immunization();
    immunization.setId(
        FhirLogicalId.perDataElement(enrollmentUid, eventUid, administeredDataElement).compose());
    Instant updatedAt = event.getUpdatedAt();
    if (updatedAt != null) {
      immunization.getMeta().setLastUpdatedElement(converter.instant(updatedAt));
    }
    immunization.setStatus(
        Optional.of(false).equals(converter.toBoolean(administeredValue))
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
    immunization.setPatient(new Reference(PATIENT_REFERENCE_PREFIX + trackedEntityUid));
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

  @CheckForNull
  private static String mappedValue(
      ResolvedMapping mapping, FhirTargetField target, Map<String, String> values) {
    return mapping.entry(target).map(entry -> valueOf(values, entry.getSource())).orElse(null);
  }

  @CheckForNull
  private static String valueOf(Map<String, String> values, @CheckForNull String dataElement) {
    return dataElement == null ? null : values.get(dataElement);
  }

  private static Type doseNumberType(String value) {
    Matcher matcher = POSITIVE_INTEGER.matcher(value.trim());
    if (matcher.matches() && Long.parseLong(matcher.group(1)) <= Integer.MAX_VALUE) {
      return new PositiveIntType(Integer.parseInt(matcher.group(1)));
    }
    return new StringType(value);
  }
}
