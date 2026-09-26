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

import static org.hisp.dhis.event.EventStatus.*;
import static org.hisp.dhis.fhir.FhirR4Validation.*;
import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.ENCOUNTER;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.parser.DataFormatException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.webapi.controller.tracker.view.*;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link FhirEncounterMapper}. */
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
      actual.put(status, map(stageEvent(status, OCCURRED, SCHEDULED), fullMapping()).getStatus());
    }
    assertEquals(expected, actual);
  }

  @Test
  void classTypeAndReasonFromMapping() {
    Encounter encounter = map(visitEvent(null), fullMapping());
    var encounterClass = encounter.getClass_();
    assertEquals(ENCOUNTER_CLASS_SYSTEM, encounterClass.getSystem());
    assertEquals(ENCOUNTER_CLASS_CODE, encounterClass.getCode());
    assertEquals(ENCOUNTER_CLASS_DISPLAY, encounterClass.getDisplay());
    encounter.getType().forEach(type -> assertEquals(1, type.getCoding().size()));
    assertEquals(
        List.of(
            Arrays.asList(VISIT_TYPE_SYSTEM, VISIT_TYPE, null),
            Arrays.asList(TYPE_SYSTEM, TYPE_CODE, TYPE_DISPLAY)),
        encounter.getType().stream()
            .map(CodeableConcept::getCodingFirstRep)
            .map(coding -> Arrays.asList(coding.getSystem(), coding.getCode(), coding.getDisplay()))
            .toList());
    assertEquals(1, encounter.getReasonCode().size());
    assertEquals(REASON, encounter.getReasonCode().get(0).getText());
    assertEquals(ENR + "-" + EVT, encounter.getIdElement().getIdPart());
    assertEquals("Patient/" + TE, encounter.getSubject().getReference());
    assertEquals(UPDATED, encounter.getMeta().getLastUpdated().toInstant());
    Encounter withoutValues =
        map(stageEvent(COMPLETED, OCCURRED, null, dataValue(DE_REASON, " ")), fullMapping());
    assertEquals(1, withoutValues.getType().size());
    assertEquals(TYPE_CODE, withoutValues.getTypeFirstRep().getCodingFirstRep().getCode());
    assertFalse(withoutValues.hasReasonCode());
    assertTrue(withoutValues.hasClass_());
    List.of(" ANC1", "ANC1 ", "ANC  1", "ANC\t1", "\u2003ANC1", "ANC1\u00A0", "ANC\u20031")
        .forEach(code -> assertEquals(List.of(TYPE_CODE), typeCodes(code), "'" + code + "'"));
    List.of("ANC 1", "\u00C4NC 1")
        .forEach(code -> assertEquals(List.of(code, TYPE_CODE), typeCodes(code), "'" + code + "'"));
  }

  @Test
  void periodUsesScheduledAtForScheduledEvents() {
    assertNotEquals(OCCURRED, SCHEDULED);
    for (EventStatus s : EventStatus.values()) {
      boolean planned = s == SCHEDULE || s == OVERDUE;
      var dated = planned ? stageEvent(s, null, SCHEDULED) : stageEvent(s, OCCURRED, SCHEDULED);
      assertEquals(planned ? SCHEDULED : OCCURRED, periodStart(dated), s::name);
      var undated = planned ? stageEvent(s, OCCURRED, null) : stageEvent(s, null, SCHEDULED);
      assertFalse(map(undated, fullMapping()).hasPeriod(), s::name);
    }
    assertEquals(SCHEDULED, periodStart(stageEvent(SCHEDULE, OCCURRED, SCHEDULED)), "both dates");
    Instant fractional = Instant.parse("2024-03-10T09:00:00.250Z");
    DateTimeType start =
        map(stageEvent(COMPLETED, fractional, null), fullMapping()).getPeriod().getStartElement();
    assertEquals(fractional, start.getValue().toInstant());
    assertEquals(TemporalPrecisionEnum.MILLI, start.getPrecision(), "period.start keeps millis");
  }

  @Test
  void emptyMappingYieldsOnlyStructuralElements() throws Exception {
    DataValue type = dataValue(DE_TYPE, "QxVisitMarker");
    DataValue reason = dataValue(DE_REASON, "QxReasonMarker");
    ResolvedMapping empty = resolved(ENCOUNTER, TE_TYPE, PROGRAM, STAGE, entries(), Map.of());
    Encounter encounter = map(stageEvent(COMPLETED, OCCURRED, SCHEDULED, type, reason), empty);
    String json = encode(encounter);
    Set<String> names = new HashSet<>();
    new ObjectMapper().readTree(json).fieldNames().forEachRemaining(names::add);
    assertEquals(Set.of("resourceType", "id", "meta", "status", "subject", "period"), names, json);
    assertFalse(json.contains("Marker"), json);
  }

  @Test
  void outputIsValidR4() {
    Encounter completed = map(visitEvent(SCHEDULED), fullMapping());
    assertValid(completed);
    assertEquals(ENR + "-" + EVT, parseStrict(encode(completed), Encounter.class).getIdPart());
    Encounter scheduled =
        map(stageEvent(SCHEDULE, null, SCHEDULED, dataValue(DE_TYPE, VISIT_TYPE)), fullMapping());
    assertEquals(EncounterStatus.PLANNED, scheduled.getStatus());
    assertValid(scheduled);
    Encounter parsed = parseStrict(encode(scheduled), Encounter.class);
    assertEquals(SCHEDULED, parsed.getPeriod().getStart().toInstant());
    var error = assertThrows(AssertionError.class, () -> assertValid(new Encounter())).getMessage();
    assertTrue(error.contains("ERROR Encounter: Encounter.status: minimum required"), error);
    assertTrue(error.contains("ERROR Encounter: Encounter.class: minimum required"), error);
    Encounter paddedCode = completed.copy();
    paddedCode.getClass_().setCode(ENCOUNTER_CLASS_CODE + " ");
    String padded = assertThrows(AssertionError.class, () -> assertValid(paddedCode)).getMessage();
    var whitespace = "ERROR Encounter.class.code: The code 'AMB ' is not valid (whitespace rules)";
    assertTrue(padded.contains(whitespace), padded);
    String json = encode(completed).replaceFirst("\\{", "{\"unknownElement\":\"x\",");
    var unknown = assertThrows(DataFormatException.class, () -> parseStrict(json, Encounter.class));
    assertTrue(unknown.getMessage().contains("Unknown element 'unknownElement'"), json);
  }

  private Encounter map(Event event, ResolvedMapping mapping) {
    return mapper.map(enrollment(ENR, TE, PROGRAM, event), event, mapping);
  }

  private List<String> typeCodes(String typeValue) {
    DataValue type = dataValue(DE_TYPE, typeValue);
    Encounter typed = map(stageEvent(COMPLETED, OCCURRED, null, type), fullMapping());
    assertValid(typed);
    return typed.getType().stream().map(t -> t.getCodingFirstRep().getCode()).toList();
  }

  private Instant periodStart(Event event) {
    Encounter encounter = map(event, fullMapping());
    assertTrue(encounter.hasPeriod() && encounter.getPeriod().hasStart(), "period.start is set");
    return encounter.getPeriod().getStart().toInstant();
  }

  private static Event stageEvent(
      EventStatus status, Instant occurredAt, Instant scheduledAt, DataValue... values) {
    return event(EVT, STAGE, status, occurredAt, scheduledAt, UPDATED, values);
  }

  private static Event visitEvent(Instant scheduledAt) {
    DataValue type = dataValue(DE_TYPE, VISIT_TYPE);
    return stageEvent(COMPLETED, OCCURRED, scheduledAt, type, dataValue(DE_REASON, REASON));
  }

  private static ResolvedMapping fullMapping() {
    Entry encounterClass =
        Entry.constant(
            ENCOUNTER_CLASS, ENCOUNTER_CLASS_SYSTEM, ENCOUNTER_CLASS_CODE, ENCOUNTER_CLASS_DISPLAY);
    var fields =
        entries(
            encounterClass,
            Entry.field(ENCOUNTER_TYPE, DATA_ELEMENT, DE_TYPE).system(VISIT_TYPE_SYSTEM),
            Entry.constant(ENCOUNTER_TYPE, TYPE_SYSTEM, TYPE_CODE, TYPE_DISPLAY),
            Entry.field(ENCOUNTER_REASON, DATA_ELEMENT, DE_REASON));
    Map<String, ValueType> valueTypes = Map.of(DE_TYPE, ValueType.TEXT, DE_REASON, ValueType.TEXT);
    return resolved(ENCOUNTER, TE_TYPE, PROGRAM, STAGE, fields, valueTypes);
  }
}
