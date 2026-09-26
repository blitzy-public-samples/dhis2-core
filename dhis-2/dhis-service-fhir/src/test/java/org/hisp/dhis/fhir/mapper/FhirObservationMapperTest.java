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

import static java.util.stream.Collectors.*;
import static org.hisp.dhis.common.ValueType.*;
import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.OBSERVATION_VALUE;
import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.webapi.controller.tracker.view.*;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link FhirObservationMapper}. */
class FhirObservationMapperTest {
  private static final String TE_TYPE = "TeTypeUid01";
  private static final String PROGRAM = "ProgramUid1";
  private static final String STAGE = "StageUid001";
  private static final String TE = "TrackedEnt1";
  private static final String TE_2 = "TrackedEnt2";
  private static final String ENR = "Enrollment1";
  private static final String ENR_2 = "Enrollment2";
  private static final String EVT = "EventUid001";
  private static final String EVT_2 = "EventUid002";
  private static final String EVT_3 = "EventUid003";
  private static final String DE_HEIGHT = "DeHeight001";
  private static final String DE_WEIGHT = "DeWeight001";
  private static final String DE_MISSING = "DeMissing01";
  private static final String DE_NUMBER = "DeNumber001";
  private static final String DE_BOOLEAN = "DeBoolean01";
  private static final String DE_DATETIME = "DeDateTime1";
  private static final String DE_TIME = "DeTime00001";
  private static final String DE_TEXT = "DeText00001";
  private static final String DE_BAD_NUMBER = "DeBadNumber";
  private static final String TEST_SYSTEM = "urn:dhis2:fhir-test:observation";
  private final FhirObservationMapper mapper = new FhirObservationMapper(new FhirValueConverter());

  @Test
  void oneObservationPerMappedDataValue() {
    ResolvedMapping mapping =
        observationMapping(
            entries(
                height(),
                loinc(DE_WEIGHT, LOINC_BODY_WEIGHT_CODE, LOINC_BODY_WEIGHT_DISPLAY)
                    .unit(BODY_WEIGHT_UNIT),
                coded(DE_MISSING, "missing")),
            Map.of(DE_HEIGHT, NUMBER, DE_WEIGHT, NUMBER, DE_MISSING, NUMBER));
    DataValue heightValue = dataValue(DE_HEIGHT, "172.5");
    DataValue weightValue = dataValue(DE_WEIGHT, "68");
    DataValue unmapped = dataValue("DeUnmapped1", "unmapped value");
    Event event = stageEvent(EVT, EventStatus.COMPLETED, heightValue, weightValue, unmapped);
    var observations = mapper.map(enrollment(ENR, TE, PROGRAM, event), event, mapping, false);
    List<String> ids = observations.stream().map(Observation::getIdPart).toList();
    assertEquals(List.of(id(ENR, EVT, DE_HEIGHT), id(ENR, EVT, DE_WEIGHT)), ids);
    Observation height = observations.get(0);
    assertCoding(height, LOINC_SYSTEM, LOINC_BODY_HEIGHT_CODE, LOINC_BODY_HEIGHT_DISPLAY);
    assertQuantity(height.getValue(), "172.5", BODY_HEIGHT_UNIT);
    Observation weight = observations.get(1);
    assertCoding(weight, LOINC_SYSTEM, LOINC_BODY_WEIGHT_CODE, LOINC_BODY_WEIGHT_DISPLAY);
    assertQuantity(weight.getValue(), "68", BODY_WEIGHT_UNIT);
    for (Observation observation : observations) {
      String id = observation.getIdPart();
      assertEquals(ObservationStatus.FINAL, observation.getStatus(), id);
      assertEquals("Patient/" + TE, observation.getSubject().getReference(), id);
      assertFalse(observation.hasEncounter(), id);
      assertEquals(OCCURRED, observation.getEffectiveDateTimeType().getValue().toInstant(), id);
      assertEquals(UPDATED, observation.getMeta().getLastUpdated().toInstant(), id);
    }
  }

  @Test
  void valueTypingPerValueType() {
    ResolvedMapping mapping =
        observationMapping(
            entries(
                coded(DE_NUMBER, "number").unit("mmHg"), coded(DE_BOOLEAN, "boolean"),
                coded(DE_DATETIME, "datetime"), coded(DE_TIME, "time"),
                coded(DE_TEXT, "text"), coded(DE_BAD_NUMBER, "bad-number")),
            Map.ofEntries(
                Map.entry(DE_NUMBER, NUMBER), Map.entry(DE_BOOLEAN, BOOLEAN),
                Map.entry(DE_DATETIME, DATETIME), Map.entry(DE_TIME, TIME),
                Map.entry(DE_TEXT, TEXT), Map.entry(DE_BAD_NUMBER, NUMBER)));
    String note = "Blood pressure within normal range";
    DataValue[] values = {
      dataValue(DE_NUMBER, "120.5"), dataValue(DE_TEXT, note),
      dataValue(DE_BOOLEAN, "true"), dataValue(DE_DATETIME, "2024-03-01T10:15:30.123Z"),
      dataValue(DE_TIME, "08:30:15"), dataValue(DE_BAD_NUMBER, "abc")
    };
    Event event = stageEvent(EVT, EventStatus.COMPLETED, values);
    Map<String, Observation> byDataElement =
        mapper.map(enrollment(ENR, TE, PROGRAM, event), event, mapping, false).stream()
            .collect(toMap(o -> o.getIdPart().substring(24), o -> o));
    Function<String, Type> value = dataElement -> byDataElement.get(dataElement).getValue();
    assertQuantity(value.apply(DE_NUMBER), "120.5", "mmHg");
    assertTrue(assertInstanceOf(BooleanType.class, value.apply(DE_BOOLEAN)).booleanValue());
    var dateTime = assertInstanceOf(DateTimeType.class, value.apply(DE_DATETIME));
    assertEquals(Instant.parse("2024-03-01T10:15:30.123Z"), dateTime.getValue().toInstant());
    assertEquals(TemporalPrecisionEnum.MILLI, dateTime.getPrecision());
    assertEquals("08:30:15", assertInstanceOf(TimeType.class, value.apply(DE_TIME)).getValue());
    assertEquals(note, assertInstanceOf(StringType.class, value.apply(DE_TEXT)).getValue());
    Observation badNumber = byDataElement.get(DE_BAD_NUMBER);
    assertFalse(badNumber.hasValue());
    assertCoding(badNumber, TEST_SYSTEM, "bad-number", "Test bad-number");
    assertEquals(ObservationStatus.FINAL, badNumber.getStatus());
  }

  @Test
  void statusTranslation() {
    Map<EventStatus, ObservationStatus> expected =
        Map.of(
            EventStatus.COMPLETED, ObservationStatus.FINAL,
            EventStatus.ACTIVE, ObservationStatus.PRELIMINARY,
            EventStatus.VISITED, ObservationStatus.PRELIMINARY,
            EventStatus.SCHEDULE, ObservationStatus.REGISTERED,
            EventStatus.OVERDUE, ObservationStatus.REGISTERED,
            EventStatus.SKIPPED, ObservationStatus.CANCELLED);
    Map<EventStatus, ObservationStatus> actual = new EnumMap<>(EventStatus.class);
    for (EventStatus status : EventStatus.values()) {
      actual.put(status, single(status, OCCURRED, UPDATED).getStatus());
    }
    assertEquals(expected, actual);
    assertEquals(ObservationStatus.PRELIMINARY, single(null, OCCURRED, UPDATED).getStatus());
  }

  @Test
  void emptyMappingYieldsOnlyStructuralElements() {
    Event event = stageEvent(EVT, EventStatus.COMPLETED, dataValue(DE_HEIGHT, "172.5"));
    ResolvedMapping empty = observationMapping(entries(), Map.of());
    assertTrue(mapper.map(enrollment(ENR, TE, PROGRAM, event), event, empty, true).isEmpty());
    Observation undated = single(EventStatus.COMPLETED, null, null);
    assertEquals(id(ENR, EVT, DE_HEIGHT), undated.getIdPart());
    assertFalse(undated.hasEffective());
    assertFalse(undated.hasMeta());
    assertQuantity(undated.getValue(), "172.5", BODY_HEIGHT_UNIT);
    Instant fractional = Instant.parse("2024-03-10T09:00:00.250Z");
    var effective = single(EventStatus.COMPLETED, fractional, UPDATED).getEffectiveDateTimeType();
    assertEquals(fractional, effective.getValue().toInstant());
    assertEquals(TemporalPrecisionEnum.MILLI, effective.getPrecision());
  }

  @Test
  void idsAndFullUrlsAreUniqueWithinBundle() {
    List<Observation> observations = fullObservations();
    List<String> expected = new ArrayList<>();
    for (String source : List.of(ENR + "-" + EVT, ENR + "-" + EVT_2, ENR_2 + "-" + EVT_3)) {
      List.of(DE_HEIGHT, DE_BOOLEAN, DE_TEXT).forEach(de -> expected.add(source + "-" + de));
    }
    assertEquals(expected, observations.stream().map(Observation::getIdPart).toList());
    for (Observation observation : observations) {
      String id = observation.getIdPart();
      var parsed = FhirLogicalId.parse(FhirResourceType.OBSERVATION, id);
      assertEquals(Optional.of(id), parsed.map(FhirLogicalId::compose));
    }
  }

  @Test
  void outputIsValidR4() {
    for (Observation observation : fullObservations()) {
      String id = observation.getIdPart();
      assertTrue(observation.hasValue(), id);
      assertEquals("Encounter/" + id.substring(0, 23), observation.getEncounter().getReference());
      FhirR4Validation.assertValid(observation);
    }
  }

  private List<Observation> fullObservations() {
    Event first = fullEvent(EVT, EventStatus.COMPLETED);
    Event second = fullEvent(EVT_2, EventStatus.ACTIVE);
    Event other = fullEvent(EVT_3, EventStatus.COMPLETED);
    Enrollment enrollment = enrollment(ENR, TE, PROGRAM, first, second);
    ResolvedMapping mapping = fullMapping();
    List<Observation> observations = new ArrayList<>(mapper.map(enrollment, first, mapping, true));
    observations.addAll(mapper.map(enrollment, second, mapping, true));
    observations.addAll(mapper.map(enrollment(ENR_2, TE_2, PROGRAM, other), other, mapping, true));
    return observations;
  }

  private static ResolvedMapping fullMapping() {
    return observationMapping(
        entries(height(), coded(DE_BOOLEAN, "smoker"), coded(DE_TEXT, "note")),
        Map.of(DE_HEIGHT, NUMBER, DE_BOOLEAN, BOOLEAN, DE_TEXT, TEXT));
  }

  private static Event fullEvent(String uid, EventStatus status) {
    DataValue smoker = dataValue(DE_BOOLEAN, "true");
    DataValue note = dataValue(DE_TEXT, "No abnormal findings");
    return stageEvent(uid, status, dataValue(DE_HEIGHT, "172.5"), smoker, note);
  }

  private static Event stageEvent(String uid, EventStatus status, DataValue... values) {
    return event(uid, STAGE, status, OCCURRED, null, UPDATED, values);
  }

  private static ResolvedMapping observationMapping(
      List<FhirFieldMapping> entries, Map<String, ValueType> valueTypes) {
    return resolved(FhirResourceType.OBSERVATION, TE_TYPE, PROGRAM, STAGE, entries, valueTypes);
  }

  private static Entry loinc(String dataElement, String code, String display) {
    return coded(dataElement, code).system(LOINC_SYSTEM).display(display);
  }

  private static Entry height() {
    return loinc(DE_HEIGHT, LOINC_BODY_HEIGHT_CODE, LOINC_BODY_HEIGHT_DISPLAY)
        .unit(BODY_HEIGHT_UNIT);
  }

  private static Entry coded(String dataElement, String code) {
    Entry entry = Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, dataElement).system(TEST_SYSTEM);
    return entry.code(code).display("Test " + code);
  }

  private static String id(String enrollment, String event, String dataElement) {
    return enrollment + "-" + event + "-" + dataElement;
  }

  private Observation single(EventStatus status, Instant occurredAt, Instant updatedAt) {
    DataValue value = dataValue(DE_HEIGHT, "172.5");
    Event event = event(EVT, STAGE, status, occurredAt, null, updatedAt, value);
    ResolvedMapping height = observationMapping(entries(height()), Map.of(DE_HEIGHT, NUMBER));
    List<Observation> observations = mapper.map(enrollment(ENR, TE, PROGRAM), event, height, false);
    assertEquals(1, observations.size());
    return observations.get(0);
  }

  private static void assertCoding(Observation actual, String system, String code, String display) {
    var codings = actual.getCode().getCoding().stream();
    var codes = codings.map(c -> c.getSystem() + "|" + c.getCode() + "|" + c.getDisplay()).toList();
    assertEquals(List.of(system + "|" + code + "|" + display), codes);
  }

  private static void assertQuantity(Type value, String expected, String unit) {
    Quantity quantity = assertInstanceOf(Quantity.class, value);
    assertEquals(0, new BigDecimal(expected).compareTo(quantity.getValue()), expected);
    assertEquals(unit, quantity.getUnit());
  }
}
