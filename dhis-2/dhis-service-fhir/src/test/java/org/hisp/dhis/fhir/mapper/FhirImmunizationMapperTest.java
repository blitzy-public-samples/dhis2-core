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
import static org.hisp.dhis.common.ValueType.*;
import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.IMMUNIZATION;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.hl7.fhir.r4.model.Immunization.ImmunizationStatus.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.FhirTestFixtures;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.webapi.controller.tracker.view.*;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link FhirImmunizationMapper}. */
class FhirImmunizationMapperTest {
  private static final String TE_TYPE = uid();
  private static final String PROGRAM = uid();
  private static final String STAGE = uid();
  private static final String TE = uid();
  private static final String ENR = uid();
  private static final String EVT = uid();
  private static final String DE_ADMINISTERED = uid();
  private static final String DE_TEXT = uid();
  private static final String DE_LOT = uid();
  private static final String DE_DOSE = uid();
  private static final String DE_OTHER = uid();
  private static final Map<String, ValueType> VALUE_TYPES =
      Map.of(DE_ADMINISTERED, BOOLEAN, DE_TEXT, TEXT, DE_LOT, TEXT, DE_DOSE, TEXT, DE_OTHER, TEXT);
  private static final Instant UPDATED_AT = Instant.parse("2024-03-12T08:30:45.123Z");
  private static final String LOT = "LOT-42";
  private static final String OTHER_VALUE = "other-distinctive-value";
  private final FhirImmunizationMapper mapper =
      new FhirImmunizationMapper(new FhirValueConverter());

  @Test
  void emitsOnlyWhenAdministeredValuePresent() {
    Immunization immunization = mapFull(administered("true"));
    assertEquals(ENR + "-" + EVT + "-" + DE_ADMINISTERED, immunization.getIdElement().getIdPart());
    assertEquals("Patient/" + TE, immunization.getPatient().getReference());
    assertEquals(OCCURRED, immunization.getOccurrenceDateTimeType().getValue().toInstant());
    assertEquals(UPDATED_AT, immunization.getMeta().getLastUpdated().toInstant());
    var codings = immunization.getVaccineCode().getCoding().stream();
    var codes = codings.map(c -> c.getSystem() + "|" + c.getCode() + "|" + c.getDisplay()).toList();
    assertEquals(List.of(CVX_SYSTEM + "|" + CVX_CODE + "|" + CVX_DISPLAY), codes);
    assertSuppressed(completed(dataValue(DE_OTHER, OTHER_VALUE)), fullMapping());
    assertSuppressed(completed(administered(" ")), fullMapping());
    assertSuppressed(
        completed(administered("true"), dataValue(DE_LOT, LOT), dataValue(DE_DOSE, "2")),
        mapping(
            Entry.constant(IMMUNIZATION_VACCINE_CODE, CVX_SYSTEM, CVX_CODE, CVX_DISPLAY),
            Entry.field(IMMUNIZATION_LOT_NUMBER, DATA_ELEMENT, DE_LOT),
            Entry.field(IMMUNIZATION_DOSE_NUMBER, DATA_ELEMENT, DE_DOSE)));
  }

  @Test
  void falseValueIsNotDone() {
    assertEquals(NOTDONE, mapFull(administered("false")).getStatus());
    assertEquals(COMPLETED, mapFull(administered("true")).getStatus());
    ResolvedMapping text = mapping(Entry.field(IMMUNIZATION_ADMINISTERED, DATA_ELEMENT, DE_TEXT));
    assertEquals(TEXT, text.valueTypes().get(DE_TEXT));
    for (var row : Map.of("given", COMPLETED, "false", NOTDONE).entrySet()) {
      Event event = completed(dataValue(DE_TEXT, row.getKey()));
      assertEquals(row.getValue(), mapPresent(event, text, false).getStatus(), row.getKey());
    }
    assertEquals("not-done", NOTDONE.toCode());
  }

  @Test
  void suppressedWhenOccurredAtMissing() {
    for (EventStatus status : EventStatus.values()) {
      for (String value : List.of("true", "false")) {
        for (Instant scheduledAt : new Instant[] {null, SCHEDULED}) {
          assertSuppressed(event(status, null, scheduledAt, administered(value)), fullMapping());
        }
      }
    }
    Event bothDates = event(EventStatus.COMPLETED, OCCURRED, SCHEDULED, administered("true"));
    Immunization withBothDates = mapPresent(bothDates, fullMapping(), false);
    assertEquals(OCCURRED, withBothDates.getOccurrenceDateTimeType().getValue().toInstant());
  }

  @Test
  void lotAndDoseNumber() {
    Immunization immunization =
        mapFull(administered("true"), dataValue(DE_LOT, LOT), dataValue(DE_DOSE, "2"));
    assertEquals(LOT, immunization.getLotNumber());
    assertEquals(1, immunization.getProtocolApplied().size());
    for (var e : Map.of("00000000002", 2, "2147483647", 2147483647, " 7 ", 7).entrySet()) {
      Type value = doseNumber(mapFull(administered("true"), dataValue(DE_DOSE, e.getKey())));
      assertEquals(e.getValue(), assertInstanceOf(PositiveIntType.class, value).getValue());
    }
    for (String dose : List.of("0", "\u0662", "second", "2147483648", "99999999999999999999")) {
      Type value = doseNumber(mapFull(administered("true"), dataValue(DE_DOSE, dose)));
      assertEquals(dose, assertInstanceOf(StringType.class, value, dose).getValue(), dose);
    }
    Immunization bare = mapFull(administered("true"));
    assertFalse(bare.hasLotNumber() || bare.hasProtocolApplied());
  }

  @Test
  void encounterReferenceOnlyWhenEncounterMapped() {
    Event event = completed(administered("true"));
    Immunization withEncounter = mapPresent(event, fullMapping(), true);
    assertEquals("Encounter/" + ENR + "-" + EVT, withEncounter.getEncounter().getReference());
    assertFalse(mapPresent(event, fullMapping(), false).hasEncounter());
  }

  @Test
  void emptyMappingYieldsOnlyStructuralElements() {
    DataValue lot = dataValue(DE_LOT, "LOT-UNMAPPED");
    DataValue dose = dataValue(DE_DOSE, "DOSE-UNMAPPED");
    Event event = completed(administered("true"), lot, dose, dataValue(DE_OTHER, OTHER_VALUE));
    assertSuppressed(event, mapping());
    ResolvedMapping administeredOnly =
        mapping(Entry.field(IMMUNIZATION_ADMINISTERED, DATA_ELEMENT, DE_ADMINISTERED));
    Immunization immunization = mapPresent(event, administeredOnly, false);
    var populated = immunization.children().stream().filter(Property::hasValues);
    var names = populated.map(Property::getName).collect(toSet());
    assertEquals(Set.of("id", "meta", "occurrence[x]", "patient", "status"), names);
    String json = FhirR4Validation.encode(immunization);
    for (String absent : List.of("UNMAPPED", OTHER_VALUE, DE_LOT, DE_DOSE, DE_OTHER, CVX_SYSTEM)) {
      assertFalse(json.contains(absent), absent);
    }
  }

  @Test
  void outputIsValidR4() {
    DataValue lot = dataValue(DE_LOT, LOT);
    Event completedEvent = completed(administered("true"), lot, dataValue(DE_DOSE, "2"));
    Immunization completedImmunization = mapPresent(completedEvent, fullMapping(), true);
    assertEquals(COMPLETED, completedImmunization.getStatus());
    assertTrue(completedImmunization.hasEncounter() && completedImmunization.hasLotNumber());
    assertInstanceOf(PositiveIntType.class, doseNumber(completedImmunization));
    FhirR4Validation.assertValid(completedImmunization);
    DataValue textDose = dataValue(DE_DOSE, "second");
    Event active = event(EventStatus.ACTIVE, OCCURRED, null, administered("false"), lot, textDose);
    Immunization notDone = mapPresent(active, fullMapping(), false);
    assertEquals(NOTDONE, notDone.getStatus());
    assertInstanceOf(StringType.class, doseNumber(notDone));
    FhirR4Validation.assertValid(notDone);
  }

  private Optional<Immunization> map(Event event, ResolvedMapping mapping, boolean withEncounter) {
    return mapper.map(enrollment(ENR, TE, PROGRAM, event), event, mapping, withEncounter);
  }

  private Immunization mapPresent(Event event, ResolvedMapping mapping, boolean withEncounter) {
    return map(event, mapping, withEncounter).orElseThrow();
  }

  private Immunization mapFull(DataValue... values) {
    return mapPresent(completed(values), fullMapping(), false);
  }

  private void assertSuppressed(Event event, ResolvedMapping mapping) {
    assertTrue(map(event, mapping, true).isEmpty(), event::toString);
    assertTrue(map(event, mapping, false).isEmpty(), event::toString);
  }

  private static DataValue administered(String value) {
    return dataValue(DE_ADMINISTERED, value);
  }

  private static Event completed(DataValue... values) {
    return event(EventStatus.COMPLETED, OCCURRED, null, values);
  }

  private static Event event(
      EventStatus status, Instant occurredAt, Instant scheduledAt, DataValue... values) {
    return FhirTestFixtures.event(EVT, STAGE, status, occurredAt, scheduledAt, UPDATED_AT, values);
  }

  private static ResolvedMapping fullMapping() {
    return mapping(
        Entry.field(IMMUNIZATION_ADMINISTERED, DATA_ELEMENT, DE_ADMINISTERED),
        Entry.constant(IMMUNIZATION_VACCINE_CODE, CVX_SYSTEM, CVX_CODE, CVX_DISPLAY),
        Entry.field(IMMUNIZATION_LOT_NUMBER, DATA_ELEMENT, DE_LOT),
        Entry.field(IMMUNIZATION_DOSE_NUMBER, DATA_ELEMENT, DE_DOSE));
  }

  private static ResolvedMapping mapping(Entry... fields) {
    return resolved(IMMUNIZATION, TE_TYPE, PROGRAM, STAGE, entries(fields), VALUE_TYPES);
  }

  private static Type doseNumber(Immunization immunization) {
    return immunization.getProtocolApplied().get(0).getDoseNumber();
  }
}
