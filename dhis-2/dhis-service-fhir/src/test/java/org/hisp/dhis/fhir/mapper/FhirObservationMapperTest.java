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
import static org.hisp.dhis.common.ValueType.BOOLEAN;
import static org.hisp.dhis.common.ValueType.DATE;
import static org.hisp.dhis.common.ValueType.DATETIME;
import static org.hisp.dhis.common.ValueType.INTEGER;
import static org.hisp.dhis.common.ValueType.NUMBER;
import static org.hisp.dhis.common.ValueType.TEXT;
import static org.hisp.dhis.common.ValueType.TIME;
import static org.hisp.dhis.fhir.FhirTestFixtures.BODY_HEIGHT_UNIT;
import static org.hisp.dhis.fhir.FhirTestFixtures.BODY_WEIGHT_UNIT;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_HEIGHT_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_HEIGHT_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_WEIGHT_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_WEIGHT_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.OCCURRED;
import static org.hisp.dhis.fhir.FhirTestFixtures.UPDATED;
import static org.hisp.dhis.fhir.FhirTestFixtures.dataValue;
import static org.hisp.dhis.fhir.FhirTestFixtures.enrollment;
import static org.hisp.dhis.fhir.FhirTestFixtures.entries;
import static org.hisp.dhis.fhir.FhirTestFixtures.event;
import static org.hisp.dhis.fhir.FhirTestFixtures.resolved;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.OBSERVATION_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.FhirTestFixtures.Entry;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.webapi.controller.tracker.view.DataValue;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.Event;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.SearchEntryMode;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.TimeType;
import org.hl7.fhir.r4.model.Type;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link FhirObservationMapper}. */
class FhirObservationMapperTest {
  private static final String TRACKED_ENTITY_TYPE = "TeTypeUid01";
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
  private static final String DE_UNMAPPED = "DeUnmapped1";
  private static final String DE_NUMBER = "DeNumber001";
  private static final String DE_INTEGER = "DeInteger01";
  private static final String DE_BOOLEAN = "DeBoolean01";
  private static final String DE_DATE = "DeDate00001";
  private static final String DE_DATETIME = "DeDateTime1";
  private static final String DE_TIME = "DeTime00001";
  private static final String DE_TEXT = "DeText00001";
  private static final String DE_BAD_NUMBER = "DeBadNumber";
  private static final String TEST_SYSTEM = "urn:dhis2:fhir-test:observation";
  private static final String FULL_URL_BASE = "http://localhost/api/fhir/Observation/";

  private final FhirObservationMapper mapper = new FhirObservationMapper(new FhirValueConverter());

  @Test
  void oneObservationPerMappedDataValue() {
    ResolvedMapping mapping =
        observationMapping(
            entries(
                loinc(DE_HEIGHT, LOINC_BODY_HEIGHT_CODE, LOINC_BODY_HEIGHT_DISPLAY)
                    .unit(BODY_HEIGHT_UNIT),
                loinc(DE_WEIGHT, LOINC_BODY_WEIGHT_CODE, LOINC_BODY_WEIGHT_DISPLAY)
                    .unit(BODY_WEIGHT_UNIT),
                coded(DE_MISSING, "missing")),
            Map.of(DE_HEIGHT, NUMBER, DE_WEIGHT, NUMBER, DE_MISSING, NUMBER));
    Event event =
        stageEvent(
            EVT,
            EventStatus.COMPLETED,
            dataValue(DE_HEIGHT, "172.5"),
            dataValue(DE_WEIGHT, "68"),
            dataValue(DE_UNMAPPED, "unmapped value"));

    List<Observation> observations =
        mapper.map(enrollment(ENR, TE, PROGRAM, event), event, mapping, false);

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
  void idsAndFullUrlsAreUniqueWithinBundle() {
    ResolvedMapping mapping = fullMapping();
    Event first = fullEvent(EVT, EventStatus.COMPLETED);
    Event second = fullEvent(EVT_2, EventStatus.ACTIVE);
    Event other = fullEvent(EVT_3, EventStatus.COMPLETED);
    Enrollment enrollment = enrollment(ENR, TE, PROGRAM, first, second);
    Enrollment otherEnrollment = enrollment(ENR_2, TE_2, PROGRAM, other);

    List<Observation> observations = new ArrayList<>();
    observations.addAll(mapper.map(enrollment, first, mapping, true));
    observations.addAll(mapper.map(enrollment, second, mapping, true));
    observations.addAll(mapper.map(otherEnrollment, other, mapping, true));

    Bundle bundle = new Bundle().setType(BundleType.SEARCHSET);
    for (Observation observation : observations) {
      bundle
          .addEntry()
          .setFullUrl(FULL_URL_BASE + observation.getIdPart())
          .setResource(observation)
          .getSearch()
          .setMode(SearchEntryMode.MATCH);
    }

    Set<String> expectedIds = new HashSet<>();
    for (String[] source : new String[][] {{ENR, EVT}, {ENR, EVT_2}, {ENR_2, EVT_3}}) {
      for (String dataElement : List.of(DE_HEIGHT, DE_BOOLEAN, DE_TEXT)) {
        expectedIds.add(id(source[0], source[1], dataElement));
      }
    }
    Set<String> ids = observations.stream().map(Observation::getIdPart).collect(toSet());
    Set<String> fullUrls =
        bundle.getEntry().stream().map(BundleEntryComponent::getFullUrl).collect(toSet());
    assertEquals(9, observations.size());
    assertEquals(observations.size(), ids.size(), "ids are unique");
    assertEquals(observations.size(), fullUrls.size(), "fullUrls are unique");
    assertEquals(expectedIds, ids);

    for (String id : ids) {
      Optional<FhirLogicalId> parsed = FhirLogicalId.parse(FhirResourceType.OBSERVATION, id);
      assertTrue(parsed.isPresent(), "parses as an Observation id: " + id);
      assertEquals(id, parsed.get().compose(), "round trip of " + id);
    }
    FhirR4Validation.assertValid(bundle);
  }

  @Test
  void valueTypingPerValueType() {
    ResolvedMapping mapping =
        observationMapping(
            entries(
                coded(DE_NUMBER, "number").unit("mmHg"),
                coded(DE_INTEGER, "integer"),
                coded(DE_BOOLEAN, "boolean"),
                coded(DE_DATE, "date"),
                coded(DE_DATETIME, "datetime"),
                coded(DE_TIME, "time"),
                coded(DE_TEXT, "text"),
                coded(DE_BAD_NUMBER, "bad-number")),
            Map.of(
                DE_NUMBER, NUMBER,
                DE_INTEGER, INTEGER,
                DE_BOOLEAN, BOOLEAN,
                DE_DATE, DATE,
                DE_DATETIME, DATETIME,
                DE_TIME, TIME,
                DE_TEXT, TEXT,
                DE_BAD_NUMBER, NUMBER));
    Event event =
        stageEvent(
            EVT,
            EventStatus.COMPLETED,
            dataValue(DE_NUMBER, "120.5"),
            dataValue(DE_INTEGER, "42"),
            dataValue(DE_BOOLEAN, "true"),
            dataValue(DE_DATE, "2024-03-01"),
            dataValue(DE_DATETIME, "2024-03-01T10:15:30Z"),
            dataValue(DE_TIME, "08:30:15"),
            dataValue(DE_TEXT, "Blood pressure within normal range"),
            dataValue(DE_BAD_NUMBER, "abc"));

    Map<String, Observation> byId =
        byId(mapper.map(enrollment(ENR, TE, PROGRAM, event), event, mapping, false));

    assertEquals(8, byId.size());
    assertQuantity(value(byId, DE_NUMBER), "120.5", "mmHg");
    Quantity integer = assertInstanceOf(Quantity.class, value(byId, DE_INTEGER));
    assertEquals(0, new BigDecimal("42").compareTo(integer.getValue()), "INTEGER quantity value");
    assertFalse(integer.hasUnit(), "INTEGER quantity without unit");
    assertEquals(
        Boolean.TRUE, assertInstanceOf(BooleanType.class, value(byId, DE_BOOLEAN)).getValue());
    assertEquals(
        "2024-03-01",
        assertInstanceOf(DateTimeType.class, value(byId, DE_DATE)).getValueAsString());
    assertEquals(
        Instant.parse("2024-03-01T10:15:30Z"),
        assertInstanceOf(DateTimeType.class, value(byId, DE_DATETIME)).getValue().toInstant());
    assertEquals("08:30:15", assertInstanceOf(TimeType.class, value(byId, DE_TIME)).getValue());
    assertEquals(
        "Blood pressure within normal range",
        assertInstanceOf(StringType.class, value(byId, DE_TEXT)).getValue());

    Observation badNumber = observation(byId, DE_BAD_NUMBER);
    assertFalse(badNumber.hasValue(), "an unparseable value leaves value[x] absent");
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
    ResolvedMapping mapping = heightMapping();

    Set<EventStatus> covered = EnumSet.noneOf(EventStatus.class);
    for (EventStatus status : EventStatus.values()) {
      assertNotNull(expected.get(status), "expected Observation status for " + status);
      Observation observation =
          single(mapper.map(enrollment(ENR, TE, PROGRAM), heightEvent(status), mapping, false));
      assertEquals(
          expected.get(status), observation.getStatus(), "Observation status for " + status);
      covered.add(status);
    }
    assertEquals(EnumSet.allOf(EventStatus.class), covered);

    Observation withoutStatus =
        single(mapper.map(enrollment(ENR, TE, PROGRAM), heightEvent(null), mapping, false));
    assertEquals(
        ObservationStatus.PRELIMINARY,
        withoutStatus.getStatus(),
        "Observation status for an event without status");
  }

  @Test
  void emptyMappingYieldsOnlyStructuralElements() {
    Event event = heightEvent(EventStatus.COMPLETED);
    Enrollment enrollment = enrollment(ENR, TE, PROGRAM, event);

    ResolvedMapping empty = observationMapping(entries(), Map.of());
    assertTrue(mapper.map(enrollment, event, empty, true).isEmpty(), "empty mapping");

    ResolvedMapping mapping = heightMapping();
    Observation withoutEncounter = single(mapper.map(enrollment, event, mapping, false));
    assertFalse(withoutEncounter.hasEncounter(), "no Encounter reference when not mapped");
    Observation withEncounter = single(mapper.map(enrollment, event, mapping, true));
    assertEquals("Encounter/" + ENR + "-" + EVT, withEncounter.getEncounter().getReference());
    assertEquals("Patient/" + TE, withEncounter.getSubject().getReference());

    Event undated =
        event(EVT, STAGE, EventStatus.COMPLETED, null, null, null, dataValue(DE_HEIGHT, "172.5"));
    Observation withoutDates = single(mapper.map(enrollment, undated, mapping, false));
    assertEquals(id(ENR, EVT, DE_HEIGHT), withoutDates.getIdPart());
    assertFalse(withoutDates.hasEffective(), "no effective[x] without occurredAt");
    assertFalse(withoutDates.hasMeta(), "no meta.lastUpdated without updatedAt");
    assertQuantity(withoutDates.getValue(), "172.5", BODY_HEIGHT_UNIT);
  }

  @Test
  void outputIsValidR4() {
    Event event = fullEvent(EVT, EventStatus.COMPLETED);

    List<Observation> observations =
        mapper.map(enrollment(ENR, TE, PROGRAM, event), event, fullMapping(), true);

    assertEquals(3, observations.size());
    for (Observation observation : observations) {
      assertTrue(observation.hasValue(), "value[x] of " + observation.getIdPart());
      assertTrue(observation.hasEncounter(), "encounter of " + observation.getIdPart());
      FhirR4Validation.assertValid(observation);
    }
  }

  /** Height (NUMBER, cm), a BOOLEAN and a TEXT data element. */
  private static ResolvedMapping fullMapping() {
    return observationMapping(
        entries(
            loinc(DE_HEIGHT, LOINC_BODY_HEIGHT_CODE, LOINC_BODY_HEIGHT_DISPLAY)
                .unit(BODY_HEIGHT_UNIT),
            coded(DE_BOOLEAN, "smoker"),
            coded(DE_TEXT, "note")),
        Map.of(DE_HEIGHT, NUMBER, DE_BOOLEAN, BOOLEAN, DE_TEXT, TEXT));
  }

  /** An event of the mapped stage with a value for every data element of {@link #fullMapping}. */
  private static Event fullEvent(String uid, EventStatus status) {
    return stageEvent(
        uid,
        status,
        dataValue(DE_HEIGHT, "172.5"),
        dataValue(DE_BOOLEAN, "true"),
        dataValue(DE_TEXT, "No abnormal findings"));
  }

  private static ResolvedMapping heightMapping() {
    return observationMapping(
        entries(
            loinc(DE_HEIGHT, LOINC_BODY_HEIGHT_CODE, LOINC_BODY_HEIGHT_DISPLAY)
                .unit(BODY_HEIGHT_UNIT)),
        Map.of(DE_HEIGHT, NUMBER));
  }

  private static Event heightEvent(EventStatus status) {
    return stageEvent(EVT, status, dataValue(DE_HEIGHT, "172.5"));
  }

  /** An event of the mapped stage, occurred at {@code OCCURRED} and updated at {@code UPDATED}. */
  private static Event stageEvent(String uid, EventStatus status, DataValue... values) {
    return event(uid, STAGE, status, OCCURRED, null, UPDATED, values);
  }

  private static ResolvedMapping observationMapping(
      List<FhirFieldMapping> entries, Map<String, ValueType> valueTypes) {
    return resolved(
        FhirResourceType.OBSERVATION, TRACKED_ENTITY_TYPE, PROGRAM, STAGE, entries, valueTypes);
  }

  private static Entry loinc(String dataElement, String code, String display) {
    return Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, dataElement)
        .system(LOINC_SYSTEM)
        .code(code)
        .display(display);
  }

  /** An entry coded in {@link #TEST_SYSTEM} with the display {@code "Test " + code}. */
  private static Entry coded(String dataElement, String code) {
    return Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, dataElement)
        .system(TEST_SYSTEM)
        .code(code)
        .display("Test " + code);
  }

  private static String id(String enrollment, String event, String dataElement) {
    return enrollment + "-" + event + "-" + dataElement;
  }

  /** Indexes Observations by id and fails on a duplicate id. */
  private static Map<String, Observation> byId(List<Observation> observations) {
    Map<String, Observation> byId = new LinkedHashMap<>();
    for (Observation observation : observations) {
      assertNull(
          byId.put(observation.getIdPart(), observation),
          "duplicate id " + observation.getIdPart());
    }
    return byId;
  }

  private static Observation observation(Map<String, Observation> byId, String dataElement) {
    Observation observation = byId.get(id(ENR, EVT, dataElement));
    assertNotNull(observation, "Observation of data element " + dataElement);
    return observation;
  }

  private static Type value(Map<String, Observation> byId, String dataElement) {
    Observation observation = observation(byId, dataElement);
    assertTrue(observation.hasValue(), "value[x] of data element " + dataElement);
    return observation.getValue();
  }

  private static Observation single(List<Observation> observations) {
    assertEquals(1, observations.size(), "number of Observations");
    return observations.get(0);
  }

  private static void assertCoding(
      Observation observation, String system, String code, String display) {
    assertEquals(1, observation.getCode().getCoding().size(), "codings");
    Coding coding = observation.getCode().getCodingFirstRep();
    assertEquals(system, coding.getSystem());
    assertEquals(code, coding.getCode());
    assertEquals(display, coding.getDisplay());
  }

  private static void assertQuantity(Type value, String expectedValue, String expectedUnit) {
    Quantity quantity = assertInstanceOf(Quantity.class, value);
    assertEquals(
        0,
        new BigDecimal(expectedValue).compareTo(quantity.getValue()),
        "quantity " + quantity.getValue() + " equals " + expectedValue);
    assertEquals(expectedUnit, quantity.getUnit());
  }
}
