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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirTargetField;
import org.hisp.dhis.webapi.controller.tracker.view.DataValue;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.Event;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

/**
 * Maps one Tracker event to HAPI FHIR R4 {@link Observation}s through a resolved {@code
 * OBSERVATION} mapping. Each {@link FhirTargetField#OBSERVATION_VALUE} entry of the mapping, in
 * stored order, yields one Observation when its data element has a non-blank value on the event. An
 * entry whose data element has no value on the event, including a value the export path withheld,
 * yields nothing.
 *
 * <p>Every Observation carries exactly these elements:
 *
 * <ul>
 *   <li>{@code id}: {@code {enrollmentUid}-{eventUid}-{dataElementUid}}, composed by {@link
 *       FhirLogicalId#perDataElement(String, String, String)}.
 *   <li>{@code meta.lastUpdated}: the event's {@code updatedAt}; omitted when absent.
 *   <li>{@code status}: from the event status, where a missing status reads as {@code ACTIVE}:
 *       <ul>
 *         <li>{@code COMPLETED}: {@code final}
 *         <li>{@code ACTIVE}, {@code VISITED}: {@code preliminary}
 *         <li>{@code SCHEDULE}, {@code OVERDUE}: {@code registered}
 *         <li>{@code SKIPPED}: {@code cancelled}
 *       </ul>
 *   <li>{@code code}: one coding holding the entry's {@code system}, {@code code} and {@code
 *       display} verbatim.
 *   <li>{@code subject}: {@code Patient/{trackedEntityUid}} of the enrollment.
 *   <li>{@code encounter}: {@code Encounter/{enrollmentUid}-{eventUid}}, only when the caller
 *       states that an {@code ENCOUNTER} mapping exists for the event's program stage.
 *   <li>{@code effectiveDateTime}: the event's {@code occurredAt}; omitted when absent.
 *   <li>{@code value[x]}: the data value converted by {@link FhirValueConverter#toFhir(ValueType,
 *       String, String)} with the data element's value type from {@link
 *       ResolvedMapping#valueTypes()} and the entry's {@code unit}; omitted when the value cannot
 *       be converted, while the Observation itself is still emitted.
 * </ul>
 *
 * <p>The caller supplies only events of the mapping's program stage and applies any {@code code}
 * search selection to the result. The mapper performs no metadata or data lookups. Instances are
 * stateless and thread-safe.
 *
 * <p>Example:
 *
 * <pre>{@code
 * FhirObservationMapper mapper = new FhirObservationMapper(new FhirValueConverter());
 * List<Observation> observations = mapper.map(enrollment, event, mapping, encounterMapped);
 * }</pre>
 */
@Component
public class FhirObservationMapper {
  private static final String PATIENT_REFERENCE_PREFIX = "Patient/";

  private static final String ENCOUNTER_REFERENCE_PREFIX = "Encounter/";

  private final FhirValueConverter converter;

  /**
   * Creates a mapper.
   *
   * @param converter the converter of Tracker values and timestamps to FHIR datatypes
   * @throws NullPointerException if {@code converter} is {@code null}
   */
  public FhirObservationMapper(@Nonnull FhirValueConverter converter) {
    this.converter = Objects.requireNonNull(converter, "converter must not be null");
  }

  /**
   * Maps the data values of one event to Observations, one per {@link
   * FhirTargetField#OBSERVATION_VALUE} entry whose data element has a value on the event, in the
   * entries' stored order.
   *
   * <p>Data values with a {@code null} or blank data element or value are ignored. When the event
   * holds more than one value of the same data element, the first one read is used.
   *
   * @param enrollment the enrollment the event belongs to; supplies the enrollment UID and the
   *     tracked entity UID
   * @param event the event of the mapping's program stage
   * @param mapping the resolved {@code OBSERVATION} mapping of the event's program stage
   * @param encounterMapped whether an {@code ENCOUNTER} mapping exists for the event's program
   *     stage; when {@code true} every Observation references its Encounter
   * @return a new list owned by the caller, empty when no Observation is emitted
   * @throws NullPointerException if {@code enrollment}, {@code event} or {@code mapping} is {@code
   *     null}, or if at least one Observation is emitted and the enrollment UID, the event UID or
   *     the enrollment's tracked entity UID is {@code null}
   */
  @Nonnull
  public List<Observation> map(
      @Nonnull Enrollment enrollment,
      @Nonnull Event event,
      @Nonnull ResolvedMapping mapping,
      boolean encounterMapped) {
    Objects.requireNonNull(enrollment, "enrollment must not be null");
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(mapping, "mapping must not be null");

    Map<String, String> values = dataValues(event.getDataValues());
    List<FhirFieldMapping> emitted =
        mapping.entries(FhirTargetField.OBSERVATION_VALUE).stream()
            .filter(entry -> entry.getSource() != null && values.containsKey(entry.getSource()))
            .toList();
    if (emitted.isEmpty()) {
      return new ArrayList<>();
    }

    EventSource source = EventSource.of(enrollment, event, encounterMapped);
    Map<String, ValueType> valueTypes = mapping.valueTypes();
    List<Observation> observations = new ArrayList<>(emitted.size());
    for (FhirFieldMapping entry : emitted) {
      observations.add(
          observation(
              source, entry, values.get(entry.getSource()), valueTypes.get(entry.getSource())));
    }
    return observations;
  }

  /**
   * Builds the Observation of one entry and its data value.
   *
   * @param source the event-level elements shared by every Observation of the event
   * @param entry the {@link FhirTargetField#OBSERVATION_VALUE} entry
   * @param value the non-blank data value of the entry's data element
   * @param valueType the value type of the entry's data element, or {@code null} when unknown
   * @return the Observation
   */
  @Nonnull
  private Observation observation(
      @Nonnull EventSource source,
      @Nonnull FhirFieldMapping entry,
      @Nonnull String value,
      @CheckForNull ValueType valueType) {
    Observation observation = new Observation();
    observation.setId(
        FhirLogicalId.perDataElement(source.enrollment(), source.event(), entry.getSource())
            .compose());
    if (source.updatedAt() != null) {
      observation.getMeta().setLastUpdatedElement(converter.instant(source.updatedAt()));
    }
    observation.setStatus(source.status());
    observation.setCode(
        new CodeableConcept()
            .addCoding(new Coding(entry.getSystem(), entry.getCode(), entry.getDisplay())));
    observation.setSubject(new Reference(PATIENT_REFERENCE_PREFIX + source.trackedEntity()));
    if (source.encounter() != null) {
      observation.setEncounter(new Reference(ENCOUNTER_REFERENCE_PREFIX + source.encounter()));
    }
    if (source.occurredAt() != null) {
      observation.setEffective(converter.dateTime(source.occurredAt()));
    }
    converter.toFhir(valueType, value, entry.getUnit()).ifPresent(observation::setValue);
    return observation;
  }

  /**
   * Indexes the usable data values of an event by data element UID. Values with a {@code null} or
   * blank data element or value are skipped, and the first value read for a data element is kept.
   *
   * @param dataValues the event's data values, or {@code null}
   * @return a new map from data element UID to value
   */
  @Nonnull
  private static Map<String, String> dataValues(@CheckForNull Collection<DataValue> dataValues) {
    Map<String, String> values = new HashMap<>();
    if (dataValues == null) {
      return values;
    }
    for (DataValue dataValue : dataValues) {
      if (dataValue == null
          || isBlank(dataValue.getDataElement())
          || isBlank(dataValue.getValue())) {
        continue;
      }
      values.putIfAbsent(dataValue.getDataElement(), dataValue.getValue());
    }
    return values;
  }

  /**
   * Translates an event status to the Observation status.
   *
   * @param status the event status; {@code null} reads as {@link EventStatus#ACTIVE}
   * @return the Observation status
   */
  @Nonnull
  private static ObservationStatus observationStatus(@CheckForNull EventStatus status) {
    return switch (status == null ? EventStatus.ACTIVE : status) {
      case COMPLETED -> ObservationStatus.FINAL;
      case ACTIVE, VISITED -> ObservationStatus.PRELIMINARY;
      case SCHEDULE, OVERDUE -> ObservationStatus.REGISTERED;
      case SKIPPED -> ObservationStatus.CANCELLED;
    };
  }

  private static boolean isBlank(@CheckForNull String value) {
    return value == null || value.isBlank();
  }

  /**
   * The elements every Observation of one event shares.
   *
   * @param enrollment the enrollment UID
   * @param event the event UID
   * @param trackedEntity the tracked entity UID of the enrollment
   * @param status the Observation status derived from the event status
   * @param updatedAt the event's last update, or {@code null}
   * @param occurredAt the event's occurrence date, or {@code null}
   * @param encounter the Encounter logical id, or {@code null} when no Encounter is referenced
   */
  private record EventSource(
      @Nonnull String enrollment,
      @Nonnull String event,
      @Nonnull String trackedEntity,
      @Nonnull ObservationStatus status,
      @CheckForNull Instant updatedAt,
      @CheckForNull Instant occurredAt,
      @CheckForNull String encounter) {

    /**
     * Reads the shared elements of an event.
     *
     * @throws NullPointerException if the enrollment UID, the event UID or the tracked entity UID
     *     is {@code null}
     */
    @Nonnull
    static EventSource of(
        @Nonnull Enrollment enrollment, @Nonnull Event event, boolean encounterMapped) {
      String enrollmentUid = uid(enrollment.getEnrollment(), "enrollment");
      String eventUid = uid(event.getEvent(), "event");
      String trackedEntityUid = uid(enrollment.getTrackedEntity(), "trackedEntity");
      return new EventSource(
          enrollmentUid,
          eventUid,
          trackedEntityUid,
          observationStatus(event.getStatus()),
          event.getUpdatedAt(),
          event.getOccurredAt(),
          encounterMapped ? FhirLogicalId.encounter(enrollmentUid, eventUid).compose() : null);
    }

    @Nonnull
    private static String uid(@CheckForNull UID uid, @Nonnull String name) {
      return Objects.requireNonNull(uid, () -> name + " UID must not be null").getValue();
    }
  }
}
