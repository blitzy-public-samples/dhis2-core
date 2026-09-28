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
package org.hisp.dhis.fhir;

import static org.hisp.dhis.fhir.FhirApiException.*;
import static org.hisp.dhis.fhir.FhirResourceSerializer.FHIR_JSON_CONTENT_TYPE;
import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hl7.fhir.r4.model.Bundle.SearchEntryMode.MATCH;
import static org.hl7.fhir.r4.model.OperationOutcome.IssueType.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.OperationOutcome.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletResponse;

class FhirResourceSerializerTest {
  private static final int THREADS = 8;
  private static final int ITERATIONS = 200;
  private final FhirResourceSerializer serializer = new FhirResourceSerializer();

  @Test
  void everyFactoryProducesSpecifiedStatusAndCode() throws Exception {
    assertError(notFound(), 404, NOTFOUND, d -> !d.isBlank());
    String forbidden = "Access to the requested resource is not permitted";
    assertError(forbidden(), 403, FORBIDDEN, forbidden::equals);
    assertError(invalidParameter("name", "empty"), 400, INVALID, d -> d.contains("name"));
    assertError(notSupported("read-only"), 501, NOTSUPPORTED, "read-only"::equals);
    int factory = Modifier.PUBLIC | Modifier.STATIC;
    var methods = Arrays.stream(FhirApiException.class.getDeclaredMethods());
    var names = methods.filter(m -> (m.getModifiers() & factory) == factory).map(Method::getName);
    List<String> factories = names.sorted().toList();
    assertEquals(List.of("forbidden", "invalidParameter", "notFound", "notSupported"), factories);
    assertEquals(0, FhirApiException.class.getConstructors().length);
    ResponseEntity<String> ok = serializer.ok(patient("patient-ok", "Okafor", "Chidi"));
    assertEquals(200, ok.getStatusCode().value());
    assertEquals(FHIR_JSON_CONTENT_TYPE, ok.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    assertEquals("no-store, private", ok.getHeaders().getCacheControl());
    assertEquals(FhirR4Validation.encode(patient("patient-ok", "Okafor", "Chidi")), ok.getBody());
    FhirR4Validation.assertValid(FhirR4Validation.parseStrict(ok.getBody(), Patient.class));
  }

  @Test
  void concurrentSerialisationProducesIdenticalOutput() throws Exception {
    Reference subject = new Reference("Patient/patient-1");
    Patient first = patient("patient-1", "Nakamura", "Aiko");
    first.getMeta().setLastUpdated(Date.from(UPDATED));
    Patient second = patient("patient-2", "Okafor", "Chidi");
    second.setGender(Enumerations.AdministrativeGender.FEMALE).setBirthDate(Date.from(OCCURRED));
    Patient third = patient("patient-3", "Haugen", "Ingrid");
    third.addIdentifier().setSystem(IDENTIFIER_SYSTEM).setValue("12345678");
    Encounter encounter = new Encounter().setStatus(Encounter.EncounterStatus.FINISHED);
    encounter.setClass_(new Coding(ENCOUNTER_CLASS_SYSTEM, ENCOUNTER_CLASS_CODE, null));
    encounter.setSubject(subject).setId("enrollment1-event1");
    Observation weight = observation("weight", LOINC_BODY_WEIGHT_CODE, 72.5, BODY_WEIGHT_UNIT);
    weight.setSubject(subject).setEffective(new DateTimeType(Date.from(OCCURRED)));
    Immunization immunization = new Immunization().setPatient(subject).setLotNumber("LOT-2024");
    immunization.setStatus(Immunization.ImmunizationStatus.COMPLETED).setId("vaccine");
    immunization.setVaccineCode(new CodeableConcept(new Coding(CVX_SYSTEM, CVX_CODE, CVX_DISPLAY)));
    immunization.setOccurrence(new DateTimeType(Date.from(OCCURRED)));
    Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET).setTotal(1);
    var entry = bundle.addEntry().setFullUrl("http://localhost/api/fhir/Patient/patient-4");
    entry.setResource(patient("patient-4", "Silva", "Ana")).getSearch().setMode(MATCH);
    Observation height = observation("height", LOINC_BODY_HEIGHT_CODE, 172.0, BODY_HEIGHT_UNIT);
    height.setSubject(new Reference("#p1")).addContained(patient("p1", "Mensah", "Kofi"));
    var inputs = List.of(first, second, third, encounter, weight, immunization, bundle, height);
    List<String> baselines = inputs.stream().map(r -> serializer.ok(r).getBody()).toList();
    assertEquals(List.of(THREADS, THREADS), List.of(inputs.size(), Set.copyOf(baselines).size()));
    CyclicBarrier start = new CyclicBarrier(THREADS);
    List<Callable<Long>> tasks = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      Resource resource = inputs.get(i);
      String baseline = baselines.get(i);
      FhirR4Validation.assertValid(FhirR4Validation.parseStrict(baseline, resource.getClass()));
      tasks.add(
          () -> {
            start.await(30, TimeUnit.SECONDS);
            var runs = IntStream.range(0, ITERATIONS);
            return runs.filter(n -> baseline.equals(serializer.ok(resource).getBody())).count();
          });
    }
    ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    try {
      for (Future<Long> future : executor.invokeAll(tasks, 60, TimeUnit.SECONDS)) {
        assertEquals(ITERATIONS, future.get());
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  private void assertError(FhirApiException e, int status, IssueType code, Predicate<String> check)
      throws Exception {
    ResponseEntity<String> response = serializer.error(e);
    assertEquals(status, response.getStatusCode().value());
    assertEquals(FHIR_JSON_CONTENT_TYPE, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    assertEquals("no-store, private", response.getHeaders().getCacheControl());
    var outcome = FhirR4Validation.parseStrict(response.getBody(), OperationOutcome.class);
    assertEquals(1, outcome.getIssue().size());
    OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
    assertEquals(List.of(IssueSeverity.ERROR, code), List.of(issue.getSeverity(), issue.getCode()));
    assertEquals(e.getDiagnostics(), issue.getDiagnostics());
    assertTrue(check.test(issue.getDiagnostics()), issue.getDiagnostics());
    FhirR4Validation.assertValid(outcome);
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    serializer.writeError(servletResponse, e);
    assertEquals(status, servletResponse.getStatus());
    assertEquals(FHIR_JSON_CONTENT_TYPE, servletResponse.getContentType());
    assertEquals("no-store, private", servletResponse.getHeader(HttpHeaders.CACHE_CONTROL));
    assertEquals(response.getBody(), servletResponse.getContentAsString());
  }

  private static Observation observation(String id, String code, double value, String unit) {
    Observation observation = new Observation().setStatus(Observation.ObservationStatus.FINAL);
    observation.setCode(new CodeableConcept(new Coding(LOINC_SYSTEM, code, null))).setId(id);
    return observation.setValue(new Quantity(null, value, "http://unitsofmeasure.org", unit, unit));
  }

  private static Patient patient(String id, String family, String given) {
    Patient patient = (Patient) new Patient().setId(id);
    return patient.addName(new HumanName().setFamily(family).addGiven(given));
  }
}
