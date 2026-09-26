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

import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.OCCURRED;
import static org.hisp.dhis.fhir.FhirTestFixtures.SCHEDULED;
import static org.hisp.dhis.fhir.FhirTestFixtures.dataValue;
import static org.hisp.dhis.fhir.FhirTestFixtures.enrollment;
import static org.hisp.dhis.fhir.FhirTestFixtures.entries;
import static org.hisp.dhis.fhir.FhirTestFixtures.resolved;
import static org.hisp.dhis.fhir.FhirTestFixtures.uid;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_ADMINISTERED;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_DOSE_NUMBER;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_LOT_NUMBER;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_VACCINE_CODE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.FhirTestFixtures;
import org.hisp.dhis.fhir.FhirTestFixtures.Entry;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.webapi.controller.tracker.view.DataValue;
import org.hisp.dhis.webapi.controller.tracker.view.Event;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.Immunization.ImmunizationStatus;
import org.hl7.fhir.r4.model.PositiveIntType;
import org.hl7.fhir.r4.model.Property;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.Test;

/** Unit tests of {@link FhirImmunizationMapper}. */
class FhirImmunizationMapperTest {
  private static final String TRACKED_ENTITY_TYPE = uid();
  private static final String PROGRAM = uid();
  private static final String STAGE = uid();
  private static final String TE = uid();
  private static final String ENR = uid();
  private static final String EVT = uid();
  private static final String DE_ADMINISTERED = uid();
  private static final String DE_LOT = uid();
  private static final String DE_DOSE = uid();
  private static final String DE_OTHER = uid();

  private static final Map<String, ValueType> VALUE_TYPES =
      Map.of(
          DE_ADMINISTERED, ValueType.BOOLEAN,
          DE_LOT, ValueType.TEXT,
          DE_DOSE, ValueType.TEXT,
          DE_OTHER, ValueType.TEXT);

  private static final Instant EVENT_UPDATED = Instant.parse("2024-03-12T08:30:45.123Z");

  private static final String LOT = "LOT-42";

  private static final String OTHER_VALUE = "other-distinctive-value";

  private final FhirImmunizationMapper mapper =
      new FhirImmunizationMapper(new FhirValueConverter());

  @Test
  void emitsOnlyWhenAdministeredValuePresent() {
    Immunization immunization = mapFull(administered("true"));

    String id = immunization.getIdElement().getIdPart();
    assertEquals(ENR + "-" + EVT + "-" + DE_ADMINISTERED, id);
    assertEquals(35, id.length());
    assertEquals("Patient/" + TE, immunization.getPatient().getReference());
    assertEquals(OCCURRED, immunization.getOccurrenceDateTimeType().getValue().toInstant());
    assertEquals(EVENT_UPDATED, immunization.getMeta().getLastUpdated().toInstant());
    assertEquals(1, immunization.getVaccineCode().getCoding().size());
    Coding coding = immunization.getVaccineCode().getCodingFirstRep();
    assertEquals(CVX_SYSTEM, coding.getSystem());
    assertEquals(CVX_CODE, coding.getCode());
    assertEquals(CVX_DISPLAY, coding.getDisplay());

    assertSuppressed(
        completed(dataValue(DE_OTHER, OTHER_VALUE)), fullMapping(), "no administered value");
    assertSuppressed(completed(administered(" ")), fullMapping(), "blank administered value");
    assertSuppressed(
        completed(administered("true"), dataValue(DE_LOT, LOT), dataValue(DE_DOSE, "2")),
        mapping(
            Entry.constant(IMMUNIZATION_VACCINE_CODE, CVX_SYSTEM, CVX_CODE, CVX_DISPLAY),
            Entry.field(IMMUNIZATION_LOT_NUMBER, DATA_ELEMENT, DE_LOT),
            Entry.field(IMMUNIZATION_DOSE_NUMBER, DATA_ELEMENT, DE_DOSE)),
        "mapping without an administered entry");
  }

  @Test
  void falseValueIsNotDone() {
    Map<String, ImmunizationStatus> expected = new LinkedHashMap<>();
    expected.put("false", ImmunizationStatus.NOTDONE);
    expected.put("true", ImmunizationStatus.COMPLETED);
    expected.put("given", ImmunizationStatus.COMPLETED);

    expected.forEach(
        (value, status) ->
            assertEquals(
                status, mapFull(administered(value)).getStatus(), "administered value " + value));
    assertEquals("not-done", ImmunizationStatus.NOTDONE.toCode());
  }

  @Test
  void suppressedWhenOccurredAtMissing() {
    assertSuppressed(
        event(EventStatus.SCHEDULE, null, SCHEDULED, administered("true")),
        fullMapping(),
        "SCHEDULE event with only scheduledAt");
    assertSuppressed(
        event(EventStatus.ACTIVE, null, null, administered("false")),
        fullMapping(),
        "ACTIVE event with a false value and no occurredAt");

    for (EventStatus status : EventStatus.values()) {
      for (String value : List.of("true", "false")) {
        for (Instant scheduledAt : new Instant[] {null, SCHEDULED}) {
          assertSuppressed(
              event(status, null, scheduledAt, administered(value)),
              fullMapping(),
              status + " event with value " + value + " and scheduledAt " + scheduledAt);
        }
      }
    }

    Immunization withBothDates =
        mapPresent(
            event(EventStatus.COMPLETED, OCCURRED, SCHEDULED, administered("true")),
            fullMapping(),
            false);
    assertEquals(OCCURRED, withBothDates.getOccurrenceDateTimeType().getValue().toInstant());
  }

  @Test
  void lotAndDoseNumber() {
    Immunization immunization =
        mapFull(administered("true"), dataValue(DE_LOT, LOT), dataValue(DE_DOSE, "2"));
    assertEquals(LOT, immunization.getLotNumber());
    assertEquals(1, immunization.getProtocolApplied().size());
    PositiveIntType positive =
        assertInstanceOf(
            PositiveIntType.class, immunization.getProtocolApplied().get(0).getDoseNumber());
    assertEquals(2, positive.getValue().intValue());

    for (String dose : List.of("0", "-1", "second")) {
      Immunization withTextDose = mapFull(administered("true"), dataValue(DE_DOSE, dose));
      StringType text =
          assertInstanceOf(
              StringType.class,
              withTextDose.getProtocolApplied().get(0).getDoseNumber(),
              "dose number " + dose);
      assertEquals(dose, text.getValue(), "dose number " + dose);
    }

    Immunization withoutLotAndDose = mapFull(administered("true"));
    assertFalse(withoutLotAndDose.hasLotNumber());
    assertFalse(withoutLotAndDose.hasProtocolApplied());
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
    String unmappedLot = "LOT-UNMAPPED-7";
    String unmappedDose = "dose-unmapped-3";
    Event event =
        completed(
            administered("true"),
            dataValue(DE_LOT, unmappedLot),
            dataValue(DE_DOSE, unmappedDose),
            dataValue(DE_OTHER, OTHER_VALUE));

    assertSuppressed(event, mapping(), "mapping without entries");

    Immunization immunization =
        mapPresent(
            event,
            mapping(Entry.field(IMMUNIZATION_ADMINISTERED, DATA_ELEMENT, DE_ADMINISTERED)),
            false);
    assertEquals(ENR + "-" + EVT + "-" + DE_ADMINISTERED, immunization.getIdElement().getIdPart());
    assertEquals(EVENT_UPDATED, immunization.getMeta().getLastUpdated().toInstant());
    assertEquals(ImmunizationStatus.COMPLETED, immunization.getStatus());
    assertEquals("Patient/" + TE, immunization.getPatient().getReference());
    assertEquals(OCCURRED, immunization.getOccurrenceDateTimeType().getValue().toInstant());
    assertFalse(immunization.hasVaccineCode());
    assertFalse(immunization.hasLotNumber());
    assertFalse(immunization.hasProtocolApplied());
    assertFalse(immunization.hasEncounter());

    Set<String> populated = new TreeSet<>();
    for (Property property : immunization.children()) {
      if (property.hasValues()) {
        populated.add(property.getName());
      }
    }
    assertEquals(Set.of("id", "meta", "occurrence[x]", "patient", "status"), populated);

    String json = FhirR4Validation.encode(immunization);
    for (String absent :
        List.of(unmappedLot, unmappedDose, OTHER_VALUE, DE_LOT, DE_DOSE, DE_OTHER, CVX_SYSTEM)) {
      assertFalse(json.contains(absent), "encoded Immunization contains " + absent);
    }
  }

  @Test
  void outputIsValidR4() {
    Immunization completedImmunization =
        mapPresent(
            completed(administered("true"), dataValue(DE_LOT, LOT), dataValue(DE_DOSE, "2")),
            fullMapping(),
            true);
    assertEquals(ImmunizationStatus.COMPLETED, completedImmunization.getStatus());
    assertTrue(completedImmunization.hasEncounter());
    assertTrue(completedImmunization.hasLotNumber());
    assertInstanceOf(
        PositiveIntType.class, completedImmunization.getProtocolApplied().get(0).getDoseNumber());
    FhirR4Validation.assertValid(completedImmunization);

    Immunization notDone =
        mapPresent(
            event(
                EventStatus.ACTIVE,
                OCCURRED,
                null,
                administered("false"),
                dataValue(DE_LOT, LOT),
                dataValue(DE_DOSE, "second")),
            fullMapping(),
            false);
    assertEquals(ImmunizationStatus.NOTDONE, notDone.getStatus());
    assertInstanceOf(StringType.class, notDone.getProtocolApplied().get(0).getDoseNumber());
    FhirR4Validation.assertValid(notDone);
  }

  private Optional<Immunization> map(
      Event event, ResolvedMapping mapping, boolean encounterMapped) {
    return mapper.map(enrollment(ENR, TE, PROGRAM, event), event, mapping, encounterMapped);
  }

  private Immunization mapPresent(Event event, ResolvedMapping mapping, boolean encounterMapped) {
    Optional<Immunization> immunization = map(event, mapping, encounterMapped);
    assertTrue(immunization.isPresent(), "an Immunization is expected");
    return immunization.get();
  }

  /** Maps a COMPLETED event with {@code occurredAt} through the full mapping, without encounter. */
  private Immunization mapFull(DataValue... values) {
    return mapPresent(completed(values), fullMapping(), false);
  }

  /** Asserts that no Immunization is produced, with and without an Encounter mapping. */
  private void assertSuppressed(Event event, ResolvedMapping mapping, String message) {
    assertTrue(map(event, mapping, true).isEmpty(), message);
    assertTrue(map(event, mapping, false).isEmpty(), message);
  }

  private static DataValue administered(String value) {
    return dataValue(DE_ADMINISTERED, value);
  }

  private static Event completed(DataValue... values) {
    return event(EventStatus.COMPLETED, OCCURRED, null, values);
  }

  private static Event event(
      EventStatus status, Instant occurredAt, Instant scheduledAt, DataValue... values) {
    return FhirTestFixtures.event(
        EVT, STAGE, status, occurredAt, scheduledAt, EVENT_UPDATED, values);
  }

  private static ResolvedMapping fullMapping() {
    return mapping(
        Entry.field(IMMUNIZATION_ADMINISTERED, DATA_ELEMENT, DE_ADMINISTERED),
        Entry.constant(IMMUNIZATION_VACCINE_CODE, CVX_SYSTEM, CVX_CODE, CVX_DISPLAY),
        Entry.field(IMMUNIZATION_LOT_NUMBER, DATA_ELEMENT, DE_LOT),
        Entry.field(IMMUNIZATION_DOSE_NUMBER, DATA_ELEMENT, DE_DOSE));
  }

  /** Builds an IMMUNIZATION mapping carrying the value types of the entries' data elements. */
  private static ResolvedMapping mapping(Entry... fieldMappings) {
    List<FhirFieldMapping> built = entries(fieldMappings);
    Map<String, ValueType> valueTypes = new LinkedHashMap<>();
    for (FhirFieldMapping entry : built) {
      if (entry.getSource() != null) {
        valueTypes.put(entry.getSource(), VALUE_TYPES.get(entry.getSource()));
      }
    }
    return resolved(
        FhirResourceType.IMMUNIZATION, TRACKED_ENTITY_TYPE, PROGRAM, STAGE, built, valueTypes);
  }
}
