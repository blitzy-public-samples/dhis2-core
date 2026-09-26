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
import static org.hl7.fhir.r4.model.OperationOutcome.IssueType.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.OperationOutcome.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletResponse;

class FhirResourceSerializerTest {
  private static final int THREADS = 8, ITERATIONS = 200;
  private final FhirResourceSerializer serializer = new FhirResourceSerializer();

  @Test
  void everyFactoryProducesSpecifiedStatusAndCode() throws IOException {
    assertErrorResponse(notFound(), 404, NOTFOUND, d -> !d.isBlank());
    String forbidden = "Access to the requested resource is not permitted";
    assertErrorResponse(forbidden(), 403, FORBIDDEN, forbidden::equals);
    assertErrorResponse(invalidParameter("name", "empty"), 400, INVALID, d -> d.contains("name"));
    assertErrorResponse(notSupported("read-only"), 501, NOTSUPPORTED, "read-only"::equals);
    List<String> factories =
        Arrays.stream(FhirApiException.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
            .map(Method::getName)
            .sorted()
            .toList();
    assertEquals(List.of("forbidden", "invalidParameter", "notFound", "notSupported"), factories);
    assertEquals(0, FhirApiException.class.getConstructors().length);
  }

  @Test
  void concurrentSerialisationProducesIdenticalOutput() throws Exception {
    List<IBaseResource> resources = resources();
    List<String> baselines = resources.stream().map(r -> serializer.ok(r).getBody()).toList();
    assertEquals(resources.size(), new HashSet<>(baselines).size());
    CyclicBarrier start = new CyclicBarrier(THREADS);
    List<Callable<Long>> tasks = new ArrayList<>();
    for (int i = 0; i < THREADS; i++) {
      IBaseResource resource = resources.get(i % resources.size());
      String baseline = baselines.get(i % resources.size());
      tasks.add(
          () -> {
            start.await(30, TimeUnit.SECONDS);
            return IntStream.range(0, ITERATIONS)
                .filter(n -> baseline.equals(serializer.ok(resource).getBody()))
                .count();
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

  private void assertErrorResponse(
      FhirApiException exception, int status, IssueType code, Predicate<String> diagnosticsCheck)
      throws IOException {
    ResponseEntity<String> response = serializer.error(exception);
    assertEquals(status, response.getStatusCode().value());
    assertEquals(FHIR_JSON_CONTENT_TYPE, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    OperationOutcome outcome =
        FhirR4Validation.parseStrict(response.getBody(), OperationOutcome.class);
    assertEquals(1, outcome.getIssue().size());
    OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
    assertEquals(IssueSeverity.ERROR, issue.getSeverity());
    assertEquals(code, issue.getCode());
    assertEquals(exception.getDiagnostics(), issue.getDiagnostics());
    assertTrue(diagnosticsCheck.test(issue.getDiagnostics()), issue.getDiagnostics());
    FhirR4Validation.assertValid(outcome);
    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    serializer.writeError(servletResponse, exception);
    assertEquals(status, servletResponse.getStatus());
    assertEquals(FHIR_JSON_CONTENT_TYPE, servletResponse.getContentType());
    assertEquals(response.getBody(), servletResponse.getContentAsString(StandardCharsets.UTF_8));
  }

  private static List<IBaseResource> resources() {
    Reference subject = new Reference("Patient/patient-1");
    Patient first = patient("patient-1", "Nakamura", "Aiko");
    first.getMeta().setLastUpdated(Date.from(UPDATED));
    first.setGender(Enumerations.AdministrativeGender.FEMALE).setBirthDate(Date.from(OCCURRED));
    first.addIdentifier().setSystem(IDENTIFIER_SYSTEM).setValue("12345678");
    Encounter encounter = new Encounter().setStatus(Encounter.EncounterStatus.FINISHED);
    encounter.setClass_(new Coding(ENCOUNTER_CLASS_SYSTEM, ENCOUNTER_CLASS_CODE, null));
    encounter.setSubject(subject).setId("enrollment1-event1");
    Observation weight = observation("weight", LOINC_BODY_WEIGHT_CODE, 72.5, BODY_WEIGHT_UNIT);
    weight.setSubject(subject).setEffective(new DateTimeType(Date.from(OCCURRED)));
    Immunization immunization = new Immunization().setPatient(subject).setLotNumber("LOT-2024");
    immunization.setStatus(Immunization.ImmunizationStatus.COMPLETED).setId("vaccine");
    immunization.setVaccineCode(concept(CVX_SYSTEM, CVX_CODE, CVX_DISPLAY));
    Bundle bundle = new Bundle().setType(Bundle.BundleType.SEARCHSET).setTotal(1);
    bundle.addEntry().setResource(patient("patient-4", "Silva", "Ana")).setFullUrl("Patient/4");
    Observation height = observation("height", LOINC_BODY_HEIGHT_CODE, 172.0, BODY_HEIGHT_UNIT);
    height.setSubject(new Reference("#p1")).addContained(patient("p1", "Mensah", "Kofi"));
    return List.of(first, encounter, weight, immunization, bundle, height);
  }

  private static Observation observation(String id, String code, double value, String unit) {
    Observation observation = new Observation().setStatus(Observation.ObservationStatus.FINAL);
    observation.setCode(concept(LOINC_SYSTEM, code, null)).setId(id);
    Quantity quantity = new Quantity().setValue(value).setUnit(unit).setCode(unit);
    observation.setValue(quantity.setSystem("http://unitsofmeasure.org"));
    return observation;
  }

  private static Patient patient(String id, String family, String given) {
    Patient patient = new Patient();
    patient.setId(id);
    patient.addName().setFamily(family).addGiven(given);
    return patient;
  }

  private static CodeableConcept concept(String system, String code, String display) {
    return new CodeableConcept(new Coding(system, code, display));
  }
}
