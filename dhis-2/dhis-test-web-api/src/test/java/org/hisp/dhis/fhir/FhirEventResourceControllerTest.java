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
package org.hisp.dhis.fhir;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.hisp.dhis.common.CodeGenerator;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.util.DateUtils;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.Test;

/** Tests FHIR R4 Encounter, Immunization and Observation reads and searches, also restricted. */
class FhirEventResourceControllerTest extends FhirPostgresControllerTestBase {
  private static final String ENROLLMENT_A = "nxP7UnKhomJ";
  private static final String EVENT_A = "pTzf9KYMk72";
  private static final String ENROLLMENT_B = "TvctPPhpD8z";
  private static final String EVENT_B = "D9PbzJY8bJM";
  private static final String DE_ADMINISTERED = "DATAEL00001";
  private static final String DE_INTEGER = "DATAEL00006";
  private static final String DE_NUMBER = "GieVkTxp4HH";
  private static final String ENCOUNTER_A = ENROLLMENT_A + "-" + EVENT_A;
  private static final String IMMUNIZATION_A = ENCOUNTER_A + "-" + DE_ADMINISTERED;
  private static final String OBSERVATION_A = ENCOUNTER_A + "-" + DE_INTEGER;
  private static final String ENCOUNTER_B = ENROLLMENT_B + "-" + EVENT_B;
  private static final String IMMUNIZATION_B = ENCOUNTER_B + "-" + DE_ADMINISTERED;
  private static final String OBSERVATION_B_INTEGER = ENCOUNTER_B + "-" + DE_INTEGER;
  private static final String OBSERVATION_B_NUMBER = ENCOUNTER_B + "-" + DE_NUMBER;
  private static final String ENCOUNTER = "/api/fhir/Encounter";
  private static final String IMMUNIZATION = "/api/fhir/Immunization";
  private static final String OBSERVATION = "/api/fhir/Observation";
  private static final List<String> EVENT_RESOURCES = List.of(ENCOUNTER, IMMUNIZATION, OBSERVATION);
  private static final String OBSERVATION_SYSTEM = "urn:dhis2:fhir-test:observation";
  private static final List<String> PROGRAM_DENIALS = List.of(NO_ACCESS, METADATA_ONLY);

  @Test
  void readEncounterImmunizationObservationReturnValidResources() {
    Encounter encounter = read(Encounter.class, ENCOUNTER_A);
    assertEquals(Encounter.EncounterStatus.FINISHED, encounter.getStatus());
    assertCoding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "AMB", encounter.getClass_());
    assertEquals(1, encounter.getType().size());
    Coding type = encounter.getTypeFirstRep().getCodingFirstRep();
    assertCoding("urn:dhis2:fhir-test:encounter-type", "option1", type);
    assertEquals("Patient/" + SUMMER, encounter.getSubject().getReference());
    Instant occurredAtA = occurredAt("2019-01-25T12:10:38.100");
    assertEquals(occurredAtA, encounter.getPeriod().getStart().toInstant());
    Immunization immunization = read(Immunization.class, IMMUNIZATION_A);
    assertEquals(Immunization.ImmunizationStatus.COMPLETED, immunization.getStatus());
    assertEquals(1, immunization.getVaccineCode().getCoding().size());
    assertCoding(
        "http://hl7.org/fhir/sid/cvx", "08", immunization.getVaccineCode().getCodingFirstRep());
    assertEquals("Patient/" + SUMMER, immunization.getPatient().getReference());
    assertEquals("Encounter/" + ENCOUNTER_A, immunization.getEncounter().getReference());
    assertEquals(occurredAtA, immunization.getOccurrenceDateTimeType().getValue().toInstant());
    assertFalse(immunization.hasLotNumber());
    assertEquals("value00002", read(Immunization.class, IMMUNIZATION_B).getLotNumber());
    Observation observation = read(Observation.class, OBSERVATION_B_NUMBER);
    assertEquals(Observation.ObservationStatus.FINAL, observation.getStatus());
    assertEquals(1, observation.getCode().getCoding().size());
    assertCoding(OBSERVATION_SYSTEM, "number-value", observation.getCode().getCodingFirstRep());
    assertEquals(15.0, observation.getValueQuantity().getValue().doubleValue());
    assertEquals("cm", observation.getValueQuantity().getUnit());
    assertEquals("Patient/" + FRANK, observation.getSubject().getReference());
    assertEquals("Encounter/" + ENCOUNTER_B, observation.getEncounter().getReference());
    Instant occurredAtB = occurredAt("2020-01-28T00:00:00.000");
    assertEquals(occurredAtB, observation.getEffectiveDateTimeType().getValue().toInstant());
  }

  @Test
  void readAcceptsFormatAndRejectsOtherParameters() {
    Map.of(
            ENCOUNTER_A, Encounter.class,
            IMMUNIZATION_A, Immunization.class,
            OBSERVATION_A, Observation.class)
        .forEach(
            (id, type) -> {
              String url = "/api/fhir/" + type.getSimpleName() + "/" + id;
              read(type, id + "?_format=json");
              assertInvalid(url + "?_format=xml", "_format");
              assertInvalid(url + "?foo=1", "foo");
            });
  }

  @Test
  void searchByPatientAndSubjectReturnsBundlesForEachType() {
    Map<String, String> expected =
        Map.of(ENCOUNTER, ENCOUNTER_A, IMMUNIZATION, IMMUNIZATION_A, OBSERVATION, OBSERVATION_A);
    for (String path : EVENT_RESOURCES) {
      for (String name :
          IMMUNIZATION.equals(path) ? List.of("patient") : List.of("patient", "subject")) {
        assertSearch(path, name + "=" + SUMMER, expected.get(path));
        assertSearch(path, name + "=Patient/" + SUMMER, expected.get(path));
      }
      assertSearch(path, "patient=" + CodeGenerator.generateUid());
    }
  }

  @Test
  void searchByIdAndCodeSelectsResources() {
    String byPatient = "&patient=" + FRANK;
    String systemCode = "code=" + OBSERVATION_SYSTEM + "|integer-value";
    assertSearch(ENCOUNTER, "_id=" + ENCOUNTER_B, ENCOUNTER_B);
    assertSearch(IMMUNIZATION, "_id=" + IMMUNIZATION_B, IMMUNIZATION_B);
    assertSearch(OBSERVATION, "_id=" + OBSERVATION_B_NUMBER, OBSERVATION_B_NUMBER);
    assertSearch(OBSERVATION, systemCode + byPatient, OBSERVATION_B_INTEGER);
    assertSearch(OBSERVATION, "code=integer-value" + byPatient, OBSERVATION_B_INTEGER);
    assertSearch(OBSERVATION, "code=urn:x|nope" + byPatient);
  }

  @Test
  void searchPagesWithCountAndPage() {
    String query = OBSERVATION + "?patient=" + FRANK + "&_count=1&_page=";
    for (String page : List.of("1", "2", "2&_format=json")) {
      boolean first = page.equals("1");
      Bundle bundle = searchset(query + page, fhirBody(GET(query + page), HttpStatus.OK));
      String id = first ? OBSERVATION_B_INTEGER : OBSERVATION_B_NUMBER;
      assertEquals(List.of(id), entryIds(bundle), page);
      assertEquals(2, bundle.getTotal(), page);
      Bundle.BundleLinkComponent link = bundle.getLink(first ? Bundle.LINK_NEXT : Bundle.LINK_PREV);
      assertTrue(link != null && link.getUrl().contains(first ? "_page=2" : "_page=1"), page);
      assertNull(bundle.getLink(first ? Bundle.LINK_PREV : Bundle.LINK_NEXT), page);
    }
  }

  @Test
  void searchRejectsInvalidParameters() {
    for (String path : EVENT_RESOURCES) {
      String byPatient = path + "?patient=" + SUMMER;
      assertInvalid(path, "patient");
      assertInvalid(path + "?patient=bad", "patient");
      assertInvalid(path + "?_id=bad", "_id");
      assertInvalid(byPatient + "&_count=0", "_count");
      assertInvalid(byPatient + "&_count=x", "_count");
      assertInvalid(byPatient + "&_page=0", "_page");
      assertInvalid(byPatient + "&_format=xml", "_format");
      assertInvalid(byPatient + "&foo=1", "foo");
    }
    assertInvalid(ENCOUNTER + "?patient=" + SUMMER + "&subject=" + SUMMER, "subject");
    assertInvalid(OBSERVATION + "?patient=" + SUMMER + "&subject=" + SUMMER, "subject");
    assertInvalid(IMMUNIZATION + "?subject=" + SUMMER, "subject");
  }

  @Test
  void readUnknownEncounterImmunizationObservationReturnsNotFound() {
    String unknownEvent = CodeGenerator.generateUid();
    String unknownPerDataElement = String.join("-", unknownEvent, unknownEvent, unknownEvent);
    List.of(
            ENCOUNTER + "/" + CodeGenerator.generateUid() + "-" + CodeGenerator.generateUid(),
            ENCOUNTER + "/" + ENROLLMENT_A + "-" + unknownEvent,
            IMMUNIZATION + "/" + unknownPerDataElement,
            IMMUNIZATION + "/" + ENROLLMENT_A + "-" + unknownEvent + "-" + DE_ADMINISTERED,
            IMMUNIZATION + "/" + ENCOUNTER_A + "-" + DE_INTEGER,
            OBSERVATION + "/" + unknownPerDataElement,
            OBSERVATION + "/" + ENROLLMENT_A + "-" + unknownEvent + "-" + DE_INTEGER,
            OBSERVATION + "/" + ENCOUNTER_A + "-" + DE_NUMBER)
        .forEach(url -> assertNotFound(GET(url)));
  }

  @Test
  void readMalformedIdReturnsNotFound() {
    List.of(
            ENCOUNTER + "/" + ENROLLMENT_A,
            ENCOUNTER + "/" + IMMUNIZATION_A,
            ENCOUNTER + "/1xP7UnKhomJ-" + EVENT_A,
            OBSERVATION + "/" + ENCOUNTER_A,
            OBSERVATION + "/" + ENCOUNTER_A + "-",
            IMMUNIZATION + "/abc-def-ghi")
        .forEach(url -> assertNotFound(GET(url)));
  }

  @Test
  void forbiddenResponseIsIdenticalForExistingAndUnknownIds() {
    String unknownId = CodeGenerator.generateUid() + "-" + CodeGenerator.generateUid();
    List<String> reads = List.of(ENCOUNTER + "/" + ENCOUNTER_A, ENCOUNTER + "/" + unknownId);
    asImportAndBaselineUser(() -> read(Encounter.class, ENCOUNTER_A));
    Set<String> bodies = new HashSet<>();
    for (String denial : PROGRAM_DENIALS) {
      asRestrictedUser(
          denied(denial, PROGRAM, SECOND_PROGRAM),
          () -> bodies.add(assertSameForbidden(reads, ENROLLMENT_A, EVENT_A, unknownId)));
      asRestrictedUser(
          denied(denial, PROGRAM),
          () -> assertEquals(assertNotFound(GET(reads.get(0))), assertNotFound(GET(reads.get(1)))));
    }
    assertEquals(1, bodies.size(), bodies::toString);
  }

  @Test
  void searchForbiddenOnlyWhenEveryProgramIsForbidden() {
    String encounters = ENCOUNTER + "?patient=" + SUMMER;
    String unknownPatientEncounters = ENCOUNTER + "?patient=" + CodeGenerator.generateUid();
    assertSearch(ENCOUNTER, "patient=" + SUMMER, ENCOUNTER_A);
    assertSearch(OBSERVATION, "patient=" + FRANK, OBSERVATION_B_INTEGER, OBSERVATION_B_NUMBER);
    for (String denial : PROGRAM_DENIALS) {
      asRestrictedUser(
          denied(denial, PROGRAM, SECOND_PROGRAM),
          () -> assertSameForbidden(List.of(encounters, unknownPatientEncounters), SUMMER));
      asRestrictedUser(
          denied(denial, PROGRAM), () -> assertForbidden(OBSERVATION + "?patient=" + FRANK));
    }
  }

  @Test
  void searchWithMixedProgramAccessReturnsAccessibleResources() {
    assertSearch(ENCOUNTER, "patient=" + SUMMER, ENCOUNTER_A);
    assertSearch(ENCOUNTER, "patient=" + FRANK, ENCOUNTER_B);
    for (String denial : PROGRAM_DENIALS) {
      asRestrictedUser(
          denied(denial, SECOND_PROGRAM),
          () -> {
            String body = assertSearch(ENCOUNTER, "patient=" + SUMMER, ENCOUNTER_A);
            assertFalse(body.contains("nxP8UnKhomJ"), body);
            assertFalse(body.contains(SECOND_PROGRAM), body);
          });
      asRestrictedUser(
          denied(denial, PROGRAM),
          () -> {
            String body = assertSearch(ENCOUNTER, "patient=" + FRANK);
            assertFalse(body.contains(EVENT_B), body);
            assertFalse(body.contains(ENROLLMENT_B), body);
          });
    }
  }

  @Test
  void hiddenDataElementProducesNoImmunizationOrObservation() {
    String byPatient = "patient=" + FRANK;
    asImportAndBaselineUser(
        () -> {
          assertSearch(OBSERVATION, byPatient, OBSERVATION_B_INTEGER, OBSERVATION_B_NUMBER);
          read(Observation.class, OBSERVATION_B_NUMBER);
          assertSearch(IMMUNIZATION, byPatient, IMMUNIZATION_B);
          read(Immunization.class, IMMUNIZATION_B);
        });
    asRestrictedUser(
        List.of(new Restriction("dataElement", DE_NUMBER, NO_ACCESS)),
        () -> {
          assertSearch(OBSERVATION, byPatient, OBSERVATION_B_INTEGER);
          assertNotFound(GET(OBSERVATION + "/" + OBSERVATION_B_NUMBER));
        });
    asRestrictedUser(
        List.of(new Restriction("dataElement", DE_ADMINISTERED, NO_ACCESS)),
        () -> {
          assertSearch(IMMUNIZATION, byPatient);
          assertNotFound(GET(IMMUNIZATION + "/" + IMMUNIZATION_B));
        });
  }

  /** Asserts the search matches exactly {@code ids}, in order and totalled; returns the body. */
  private String assertSearch(String path, String query, String... ids) {
    String url = path + "?" + query;
    String body = fhirBody(GET(url), HttpStatus.OK);
    Bundle bundle = searchset(url, body);
    assertEquals(List.of(ids), entryIds(bundle), url);
    assertTrue(bundle.hasTotal(), url);
    assertEquals(ids.length, bundle.getTotal(), url);
    return body;
  }

  private static List<Restriction> denied(String access, String... programs) {
    return Stream.of(programs).map(uid -> new Restriction("program", uid, access)).toList();
  }

  private static void assertCoding(String system, String code, Coding coding) {
    assertEquals(system, coding.getSystem());
    assertEquals(code, coding.getCode());
  }

  /** Returns the fixture timestamp as the instant it was imported as, to the millisecond. */
  private static Instant occurredAt(String fixtureTimestamp) {
    return DateUtils.parseDate(fixtureTimestamp).toInstant();
  }
}
