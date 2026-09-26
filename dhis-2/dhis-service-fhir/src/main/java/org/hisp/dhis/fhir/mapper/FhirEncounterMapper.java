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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirSourceType;
import org.hisp.dhis.fhir.mapping.FhirTargetField;
import org.hisp.dhis.webapi.controller.tracker.view.DataValue;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.Event;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

/**
 * Maps one Tracker event, inside its enrollment, to a FHIR R4 {@link Encounter} through an {@code
 * ENCOUNTER} mapping. The caller passes only events of the mapping's program stage; the mapper
 * neither filters events nor looks up metadata or data.
 *
 * <p>Structural elements, set on every Encounter:
 *
 * <ul>
 *   <li>{@code id}: {@code {enrollmentUid}-{eventUid}} (see {@link FhirLogicalId#encounter(String,
 *       String)}).
 *   <li>{@code meta.lastUpdated}: the event's {@code updatedAt}; omitted when it is {@code null}.
 *   <li>{@code status}, from the event status ({@code null} is read as {@code ACTIVE}): {@code
 *       ACTIVE} and {@code VISITED} give {@code in-progress}, {@code COMPLETED} gives {@code
 *       finished}, {@code SCHEDULE} and {@code OVERDUE} give {@code planned}, and {@code SKIPPED}
 *       gives {@code cancelled}.
 *   <li>{@code subject}: {@code Patient/{trackedEntityUid}} of the enrollment.
 *   <li>{@code period.start}: the event's {@code scheduledAt} when its status is {@code SCHEDULE}
 *       or {@code OVERDUE}, otherwise its {@code occurredAt}. No {@code period} is set when that
 *       timestamp is {@code null}.
 * </ul>
 *
 * <p>Mapped elements, set only from the mapping's entries:
 *
 * <ul>
 *   <li>{@code class}: the {@code system}, {@code code} and {@code display} of the {@link
 *       FhirTargetField#ENCOUNTER_CLASS} {@code CONSTANT} entry; omitted when there is none.
 *   <li>{@code type}: one element per {@link FhirTargetField#ENCOUNTER_TYPE} entry, in entry order.
 *       A {@code CONSTANT} entry gives a coding of its {@code system}, {@code code} and {@code
 *       display}. A {@code DATA_ELEMENT} entry gives a coding whose {@code system} is the entry's
 *       and whose {@code code} is the event's value of the entry's data element; it is skipped when
 *       the event has no such value.
 *   <li>{@code reasonCode}: one element per {@link FhirTargetField#ENCOUNTER_REASON} {@code
 *       DATA_ELEMENT} entry whose data element has a value on the event, in entry order, with that
 *       value as its {@code text}.
 * </ul>
 *
 * <p>A data value that is {@code null} or blank counts as absent, as does a data value the event
 * does not carry. When the event carries several values of one data element, the first one read is
 * used. No other Encounter element is set.
 *
 * <p>Example:
 *
 * <pre>{@code
 * FhirEncounterMapper mapper = new FhirEncounterMapper(new FhirValueConverter());
 * Encounter encounter = mapper.map(enrollment, event, resolvedEncounterMapping);
 * }</pre>
 *
 * <p>Instances are stateless and thread-safe.
 */
@Component
public class FhirEncounterMapper {
  private static final String PATIENT_REFERENCE_PREFIX = "Patient/";

  private static final Set<EventStatus> SCHEDULED_STATUSES =
      Set.of(EventStatus.SCHEDULE, EventStatus.OVERDUE);

  private final FhirValueConverter converter;

  /**
   * Creates a mapper.
   *
   * @param converter converts Tracker timestamps to FHIR datatypes
   * @throws NullPointerException if {@code converter} is {@code null}
   */
  public FhirEncounterMapper(@Nonnull FhirValueConverter converter) {
    this.converter = Objects.requireNonNull(converter, "converter must not be null");
  }

  /**
   * Maps the event to an Encounter.
   *
   * @param enrollment the enrollment the event belongs to; supplies the id prefix and the subject
   * @param event the event of the mapping's program stage
   * @param mapping the resolved {@code ENCOUNTER} mapping of the event's program stage
   * @return a new Encounter holding the structural elements and the elements the mapping fills
   * @throws NullPointerException if an argument, the enrollment UID, the event UID or the
   *     enrollment's tracked entity UID is {@code null}
   */
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

  /**
   * Translates an event status to an Encounter status: {@code ACTIVE} and {@code VISITED} to {@code
   * in-progress}, {@code COMPLETED} to {@code finished}, {@code SCHEDULE} and {@code OVERDUE} to
   * {@code planned}, and {@code SKIPPED} to {@code cancelled}.
   */
  private static EncounterStatus encounterStatus(EventStatus status) {
    return switch (status) {
      case ACTIVE, VISITED -> EncounterStatus.INPROGRESS;
      case COMPLETED -> EncounterStatus.FINISHED;
      case SCHEDULE, OVERDUE -> EncounterStatus.PLANNED;
      case SKIPPED -> EncounterStatus.CANCELLED;
    };
  }

  /**
   * Returns the start of the Encounter period: {@code scheduledAt} for {@code SCHEDULE} and {@code
   * OVERDUE} events, {@code occurredAt} for every other status.
   *
   * @return the timestamp, or {@code null} when the event does not carry it
   */
  @CheckForNull
  private static Instant periodStart(Event event, EventStatus status) {
    return SCHEDULED_STATUSES.contains(status) ? event.getScheduledAt() : event.getOccurredAt();
  }

  /**
   * Returns the coding of a {@code CONSTANT} entry for {@code ENCOUNTER_TYPE}, or the coding of the
   * event's value of a {@code DATA_ELEMENT} entry's data element under the entry's {@code system}.
   *
   * @return the coding, or empty for a {@code DATA_ELEMENT} entry whose data element has no value
   *     on the event and for any other source type
   */
  private static Optional<Coding> typeCoding(FhirFieldMapping entry, Map<String, String> values) {
    if (entry.getSourceType() == FhirSourceType.CONSTANT) {
      return Optional.of(constantCoding(entry));
    }
    return dataElementValue(entry, values)
        .map(value -> new Coding().setSystem(entry.getSystem()).setCode(value));
  }

  /** Returns a coding of the entry's {@code system}, {@code code} and {@code display}. */
  private static Coding constantCoding(FhirFieldMapping entry) {
    return new Coding(entry.getSystem(), entry.getCode(), entry.getDisplay());
  }

  /**
   * Returns the event's value of the data element of a {@code DATA_ELEMENT} entry.
   *
   * @return the value, or empty when the entry is not a {@code DATA_ELEMENT} entry or the event has
   *     no value of its data element
   */
  private static Optional<String> dataElementValue(
      FhirFieldMapping entry, Map<String, String> values) {
    if (entry.getSourceType() != FhirSourceType.DATA_ELEMENT || entry.getSource() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(values.get(entry.getSource()));
  }

  /**
   * Collects the event's data values by data element UID. Values that are {@code null} or blank,
   * and data values without a data element, are left out; of several values of one data element,
   * the first one read is kept.
   *
   * @return a map from data element UID to value; empty when the event has no data values
   */
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
