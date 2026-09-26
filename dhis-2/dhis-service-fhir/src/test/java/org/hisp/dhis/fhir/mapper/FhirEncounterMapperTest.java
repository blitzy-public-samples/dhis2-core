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
import static org.hisp.dhis.event.EventStatus.ACTIVE;
import static org.hisp.dhis.event.EventStatus.COMPLETED;
import static org.hisp.dhis.event.EventStatus.OVERDUE;
import static org.hisp.dhis.event.EventStatus.SCHEDULE;
import static org.hisp.dhis.event.EventStatus.SKIPPED;
import static org.hisp.dhis.event.EventStatus.VISITED;
import static org.hisp.dhis.fhir.FhirR4Validation.assertValid;
import static org.hisp.dhis.fhir.FhirR4Validation.encode;
import static org.hisp.dhis.fhir.FhirR4Validation.parseStrict;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.OCCURRED;
import static org.hisp.dhis.fhir.FhirTestFixtures.SCHEDULED;
import static org.hisp.dhis.fhir.FhirTestFixtures.UPDATED;
import static org.hisp.dhis.fhir.FhirTestFixtures.dataValue;
import static org.hisp.dhis.fhir.FhirTestFixtures.enrollment;
import static org.hisp.dhis.fhir.FhirTestFixtures.entries;
import static org.hisp.dhis.fhir.FhirTestFixtures.resolved;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.ENCOUNTER;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.ENCOUNTER_CLASS;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.ENCOUNTER_REASON;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.ENCOUNTER_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.FhirTestFixtures;
import org.hisp.dhis.fhir.FhirTestFixtures.Entry;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.webapi.controller.tracker.view.DataValue;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.Event;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.junit.jupiter.api.Test;

/**
 * Tests mapping a Tracker event, inside its enrollment, to a FHIR R4 {@link Encounter}: the
 * structural elements every Encounter carries, and the {@code class}, {@code type} and {@code
 * reasonCode} elements that only the {@code ENCOUNTER} mapping supplies.
 */
class FhirEncounterMapperTest {
  private static final String TE = "QS6w44flWAf";

  private static final String TE_TYPE = "ja8NY4PW7Xm";

  private static final String PROGRAM = "BFcipDERJnf";

  private static final String STAGE = "NpsdDv6kKSO";

  private static final String ENR = "nxP7UnKhomJ";

  private static final String EVT = "pTzf9KYMk72";

  private static final String DE_TYPE = "DATAEL00005";

  private static final String DE_REASON = "DATAEL00002";

  private static final String VISIT_TYPE_SYSTEM = "urn:dhis2:fhir-test:visit-type";

  private static final String VISIT_TYPE = "ANC1";

  private static final String REASON = "Fever";

  private static final String TYPE_SYSTEM = "urn:dhis2:fhir-test:encounter-type";

  private static final String TYPE_CODE = "ROUTINE";

  private static final String TYPE_DISPLAY = "Routine visit";

  private final FhirEncounterMapper mapper = new FhirEncounterMapper(new FhirValueConverter());

  @Test
  void statusTranslationForEveryEventStatus() {
    Map<EventStatus, EncounterStatus> expected = new EnumMap<>(EventStatus.class);
    expected.put(ACTIVE, EncounterStatus.INPROGRESS);
    expected.put(VISITED, EncounterStatus.INPROGRESS);
    expected.put(COMPLETED, EncounterStatus.FINISHED);
    expected.put(SCHEDULE, EncounterStatus.PLANNED);
    expected.put(OVERDUE, EncounterStatus.PLANNED);
    expected.put(SKIPPED, EncounterStatus.CANCELLED);

    Map<EventStatus, EncounterStatus> actual = new EnumMap<>(EventStatus.class);
    for (EventStatus status : EventStatus.values()) {
      Encounter encounter = map(stageEvent(status, OCCURRED, SCHEDULED), fullMapping());
      actual.put(status, encounter.getStatus());
      assertEquals(
          expected.get(status),
          encounter.getStatus(),
          () -> "Encounter.status for event status " + status);
    }

    assertEquals(EnumSet.allOf(EventStatus.class), expected.keySet());
    assertEquals(expected, actual);
  }

  @Test
  void classTypeAndReasonFromMapping() {
    Encounter encounter =
        map(
            stageEvent(
                COMPLETED,
                OCCURRED,
                null,
                dataValue(DE_TYPE, VISIT_TYPE),
                dataValue(DE_REASON, REASON)),
            fullMapping());

    Coding encounterClass = encounter.getClass_();
    assertEquals(ENCOUNTER_CLASS_SYSTEM, encounterClass.getSystem());
    assertEquals(ENCOUNTER_CLASS_CODE, encounterClass.getCode());
    assertEquals(ENCOUNTER_CLASS_DISPLAY, encounterClass.getDisplay());

    assertEquals(2, encounter.getType().size());
    encounter.getType().forEach(type -> assertEquals(1, type.getCoding().size()));
    Set<List<String>> typeCodings =
        encounter.getType().stream()
            .flatMap(type -> type.getCoding().stream())
            .map(coding -> Arrays.asList(coding.getSystem(), coding.getCode(), coding.getDisplay()))
            .collect(toSet());
    assertEquals(
        Set.of(
            Arrays.asList(VISIT_TYPE_SYSTEM, VISIT_TYPE, null),
            Arrays.asList(TYPE_SYSTEM, TYPE_CODE, TYPE_DISPLAY)),
        typeCodings);

    assertEquals(1, encounter.getReasonCode().size());
    assertEquals(REASON, encounter.getReasonCode().get(0).getText());

    assertEquals(ENR + "-" + EVT, encounter.getIdElement().getIdPart());
    assertEquals("Patient/" + TE, encounter.getSubject().getReference());
    assertEquals(UPDATED, encounter.getMeta().getLastUpdated().toInstant());

    // An absent type value and a blank reason value leave only the constant type.
    Encounter withoutValues =
        map(stageEvent(COMPLETED, OCCURRED, null, dataValue(DE_REASON, " ")), fullMapping());
    assertEquals(1, withoutValues.getType().size());
    assertEquals(TYPE_CODE, withoutValues.getTypeFirstRep().getCodingFirstRep().getCode());
    assertFalse(withoutValues.hasReasonCode());
    assertTrue(withoutValues.hasClass_());
  }

  @Test
  void periodUsesScheduledAtForScheduledEvents() {
    assertNotEquals(OCCURRED, SCHEDULED);

    for (EventStatus status : List.of(SCHEDULE, OVERDUE)) {
      assertEquals(
          SCHEDULED,
          periodStart(stageEvent(status, null, SCHEDULED)),
          () -> "period.start of a " + status + " event with only scheduledAt");
    }
    assertEquals(
        SCHEDULED,
        periodStart(stageEvent(SCHEDULE, OCCURRED, SCHEDULED)),
        "period.start of a SCHEDULE event with occurredAt and scheduledAt");
    for (EventStatus status : List.of(ACTIVE, COMPLETED)) {
      assertEquals(
          OCCURRED,
          periodStart(stageEvent(status, OCCURRED, SCHEDULED)),
          () -> "period.start of a " + status + " event with occurredAt and scheduledAt");
    }
  }

  @Test
  void emptyMappingYieldsOnlyStructuralElements() throws Exception {
    Encounter encounter =
        map(
            stageEvent(
                COMPLETED,
                OCCURRED,
                SCHEDULED,
                dataValue(DE_TYPE, "QxVisitMarker"),
                dataValue(DE_REASON, "QxReasonMarker")),
            resolved(ENCOUNTER, TE_TYPE, PROGRAM, STAGE, entries(), Map.of()));

    String json = encode(encounter);
    Set<String> elements = new HashSet<>();
    new ObjectMapper().readTree(json).fieldNames().forEachRemaining(elements::add);
    assertEquals(
        Set.of("resourceType", "id", "meta", "status", "subject", "period"), elements, json);

    assertEquals(ENR + "-" + EVT, encounter.getIdElement().getIdPart());
    assertEquals(UPDATED, encounter.getMeta().getLastUpdated().toInstant());
    assertEquals(EncounterStatus.FINISHED, encounter.getStatus());
    assertEquals("Patient/" + TE, encounter.getSubject().getReference());
    assertEquals(OCCURRED, encounter.getPeriod().getStart().toInstant());
    assertFalse(encounter.hasClass_());
    assertFalse(encounter.hasType());
    assertFalse(encounter.hasReasonCode());
    assertFalse(json.contains("QxVisitMarker"), json);
    assertFalse(json.contains("QxReasonMarker"), json);
  }

  @Test
  void outputIsValidR4() {
    Encounter completed =
        map(
            stageEvent(
                COMPLETED,
                OCCURRED,
                SCHEDULED,
                dataValue(DE_TYPE, VISIT_TYPE),
                dataValue(DE_REASON, REASON)),
            fullMapping());
    assertValid(completed);
    assertEquals(
        ENR + "-" + EVT,
        parseStrict(encode(completed), Encounter.class).getIdElement().getIdPart());

    Encounter scheduled =
        map(stageEvent(SCHEDULE, null, SCHEDULED, dataValue(DE_TYPE, VISIT_TYPE)), fullMapping());
    assertEquals(EncounterStatus.PLANNED, scheduled.getStatus());
    assertValid(scheduled);
    assertEquals(
        SCHEDULED,
        parseStrict(encode(scheduled), Encounter.class).getPeriod().getStart().toInstant());
  }

  /** Maps the event inside an enrollment of {@link #TE}, linking the event to both. */
  private Encounter map(Event event, ResolvedMapping mapping) {
    event.setEnrollment(UID.of(ENR));
    event.setTrackedEntity(UID.of(TE));
    event.setProgram(PROGRAM);
    Enrollment enrollment = enrollment(ENR, TE, PROGRAM, event);
    return mapper.map(enrollment, event, mapping);
  }

  /** Maps the event with the full mapping and returns its {@code period.start}. */
  private Instant periodStart(Event event) {
    Encounter encounter = map(event, fullMapping());
    assertTrue(encounter.hasPeriod() && encounter.getPeriod().hasStart(), "period.start is set");
    return encounter.getPeriod().getStart().toInstant();
  }

  /** Builds event {@link #EVT} of stage {@link #STAGE}, updated at {@link #UPDATED}. */
  private static Event stageEvent(
      EventStatus status, Instant occurredAt, Instant scheduledAt, DataValue... values) {
    return FhirTestFixtures.event(EVT, STAGE, status, occurredAt, scheduledAt, UPDATED, values);
  }

  /**
   * Builds an {@code ENCOUNTER} mapping with a constant class, a data element type, a constant type
   * and a data element reason.
   */
  private static ResolvedMapping fullMapping() {
    return resolved(
        ENCOUNTER,
        TE_TYPE,
        PROGRAM,
        STAGE,
        entries(
            Entry.constant(
                ENCOUNTER_CLASS,
                ENCOUNTER_CLASS_SYSTEM,
                ENCOUNTER_CLASS_CODE,
                ENCOUNTER_CLASS_DISPLAY),
            Entry.field(ENCOUNTER_TYPE, DATA_ELEMENT, DE_TYPE).system(VISIT_TYPE_SYSTEM),
            Entry.constant(ENCOUNTER_TYPE, TYPE_SYSTEM, TYPE_CODE, TYPE_DISPLAY),
            Entry.field(ENCOUNTER_REASON, DATA_ELEMENT, DE_REASON)),
        Map.of(DE_TYPE, ValueType.TEXT, DE_REASON, ValueType.TEXT));
  }
}
