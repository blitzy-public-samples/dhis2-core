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
import java.util.regex.Pattern;
import javax.annotation.*;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.webapi.controller.tracker.view.DataValue;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.Event;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.springframework.stereotype.Component;

/**
 * Maps one Tracker event of an {@code ENCOUNTER} mapping's program stage to a FHIR R4 {@link
 * Encounter} with {@code id} {@code {enrollmentUid}-{eventUid}}, {@code meta.lastUpdated} from
 * {@code updatedAt}, {@code status} from the event status ({@code null} as {@code ACTIVE}), {@code
 * subject} {@code Patient/{trackedEntityUid}}, {@code period.start} from {@code scheduledAt} for
 * {@code SCHEDULE} and {@code OVERDUE} events and from {@code occurredAt} otherwise, {@code class}
 * from the {@code CONSTANT} {@link FhirTargetField#ENCOUNTER_CLASS} entry, one {@code type} per
 * {@link FhirTargetField#ENCOUNTER_TYPE} entry as its constant coding or as its {@code system} with
 * the first non-blank data element value as {@code code} when that is a valid R4 {@code code}, and
 * one {@code reasonCode} text per {@link FhirTargetField#ENCOUNTER_REASON} data element value.
 */
@Component
public class FhirEncounterMapper {
  private static final String PATIENT_REFERENCE_PREFIX = "Patient/";
  private static final Set<EventStatus> SCHEDULED_STATUSES =
      Set.of(EventStatus.SCHEDULE, EventStatus.OVERDUE);
  private static final Pattern R4_CODE =
      Pattern.compile("\\S+( \\S+)*", Pattern.UNICODE_CHARACTER_CLASS);
  private final FhirValueConverter converter;

  public FhirEncounterMapper(@Nonnull FhirValueConverter converter) {
    this.converter = Objects.requireNonNull(converter, "converter must not be null");
  }

  @Nonnull
  public Encounter map(
      @Nonnull Enrollment enrollment, @Nonnull Event event, @Nonnull ResolvedMapping mapping) {
    Objects.requireNonNull(enrollment, "enrollment must not be null");
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(mapping, "mapping must not be null");
    String enrollmentUid =
        Objects.requireNonNull(enrollment.getEnrollment(), "enrollment UID must not be null")
            .getValue();
    String eventUid =
        Objects.requireNonNull(event.getEvent(), "event UID must not be null").getValue();
    String trackedEntityUid =
        Objects.requireNonNull(
                enrollment.getTrackedEntity(), "enrollment tracked entity UID must not be null")
            .getValue();
    EventStatus status = event.getStatus() == null ? EventStatus.ACTIVE : event.getStatus();
    Map<String, String> values = dataValues(event);
    Encounter encounter = new Encounter();
    encounter.setId(FhirLogicalId.encounter(enrollmentUid, eventUid).compose());
    if (event.getUpdatedAt() != null) {
      encounter.getMeta().setLastUpdatedElement(converter.instant(event.getUpdatedAt()));
    }
    encounter.setStatus(encounterStatus(status));
    mapping
        .entry(FhirTargetField.ENCOUNTER_CLASS)
        .filter(entry -> entry.getSourceType() == FhirSourceType.CONSTANT)
        .ifPresent(entry -> encounter.setClass_(constantCoding(entry)));
    for (FhirFieldMapping entry : mapping.entries(FhirTargetField.ENCOUNTER_TYPE)) {
      typeCoding(entry, values).ifPresent(coding -> encounter.addType().addCoding(coding));
    }
    for (FhirFieldMapping entry : mapping.entries(FhirTargetField.ENCOUNTER_REASON)) {
      dataElementValue(entry, values).ifPresent(value -> encounter.addReasonCode().setText(value));
    }
    encounter.setSubject(new Reference(PATIENT_REFERENCE_PREFIX + trackedEntityUid));
    Instant start = periodStart(event, status);
    if (start != null) {
      encounter.setPeriod(new Period().setStartElement(converter.dateTime(start)));
    }
    return encounter;
  }

  private static EncounterStatus encounterStatus(EventStatus status) {
    return switch (status) {
      case ACTIVE, VISITED -> EncounterStatus.INPROGRESS;
      case COMPLETED -> EncounterStatus.FINISHED;
      case SCHEDULE, OVERDUE -> EncounterStatus.PLANNED;
      case SKIPPED -> EncounterStatus.CANCELLED;
    };
  }

  @CheckForNull
  private static Instant periodStart(Event event, EventStatus status) {
    return SCHEDULED_STATUSES.contains(status) ? event.getScheduledAt() : event.getOccurredAt();
  }

  private static Optional<Coding> typeCoding(FhirFieldMapping entry, Map<String, String> values) {
    if (entry.getSourceType() == FhirSourceType.CONSTANT) {
      return Optional.of(constantCoding(entry));
    }
    return dataElementValue(entry, values)
        .filter(value -> R4_CODE.matcher(value).matches())
        .map(value -> new Coding().setSystem(entry.getSystem()).setCode(value));
  }

  private static Coding constantCoding(FhirFieldMapping entry) {
    return new Coding(entry.getSystem(), entry.getCode(), entry.getDisplay());
  }

  private static Optional<String> dataElementValue(
      FhirFieldMapping entry, Map<String, String> values) {
    if (entry.getSourceType() != FhirSourceType.DATA_ELEMENT || entry.getSource() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(values.get(entry.getSource()));
  }

  private static Map<String, String> dataValues(Event event) {
    Map<String, String> values = new LinkedHashMap<>();
    Set<DataValue> dataValues = event.getDataValues();
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
}
