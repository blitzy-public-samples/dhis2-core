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

import static org.hisp.dhis.fhir.FhirResourceSerializer.FHIR_JSON_CONTENT_TYPE;
import static org.hisp.dhis.fhir.FhirTestFixtures.BODY_HEIGHT_UNIT;
import static org.hisp.dhis.fhir.FhirTestFixtures.BODY_WEIGHT_UNIT;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.CVX_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.IDENTIFIER_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_HEIGHT_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_HEIGHT_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_WEIGHT_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_BODY_WEIGHT_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.LOINC_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.OCCURRED;
import static org.hisp.dhis.fhir.FhirTestFixtures.UPDATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirVersionEnum;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.SearchEntryMode;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Encounter.EncounterStatus;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Immunization;
import org.hl7.fhir.r4.model.Immunization.ImmunizationStatus;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Observation.ObservationStatus;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Tests the FHIR JSON responses of {@link FhirResourceSerializer} and the factories of {@link
 * FhirApiException}.
 */
class FhirResourceSerializerTest {

  private static final String FORBIDDEN_DIAGNOSTICS =
      "Access to the requested resource is not permitted";
  private static final String NOT_SUPPORTED_DETAIL = "Write interactions are not supported";
  private static final String UCUM_SYSTEM = "http://unitsofmeasure.org";
  private static final int THREADS = 8;
  private static final int ITERATIONS = 200;

  private final FhirResourceSerializer serializer = new FhirResourceSerializer();

  @Test
  void everyFactoryProducesSpecifiedStatusAndCode() throws IOException {
    assertErrorResponse(
        FhirApiException.notFound(),
        404,
        IssueType.NOTFOUND,
        diagnostics -> assertFalse(diagnostics.isBlank()));
    assertErrorResponse(
        FhirApiException.forbidden(),
        403,
        IssueType.FORBIDDEN,
        diagnostics -> assertEquals(FORBIDDEN_DIAGNOSTICS, diagnostics));
    assertErrorResponse(
        FhirApiException.invalidParameter("family", "must not be empty"),
        400,
        IssueType.INVALID,
        diagnostics -> assertTrue(diagnostics.contains("family"), diagnostics));
    assertErrorResponse(
        FhirApiException.notSupported(NOT_SUPPORTED_DETAIL),
        501,
        IssueType.NOTSUPPORTED,
        diagnostics -> assertEquals(NOT_SUPPORTED_DETAIL, diagnostics));

    // The four factories are the only public static methods and there is no public constructor.
    List<String> factories =
        Arrays.stream(FhirApiException.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> Modifier.isStatic(method.getModifiers()))
            .map(Method::getName)
            .sorted()
            .toList();
    assertEquals(List.of("forbidden", "invalidParameter", "notFound", "notSupported"), factories);
    assertEquals(0, FhirApiException.class.getConstructors().length);

    // ok() answers 200 with the FHIR content type and the resource as strict FHIR R4 JSON.
    Patient patient = patient("patient-ok", "Okafor", "Chidi");
    ResponseEntity<String> response = serializer.ok(patient);
    assertEquals(200, response.getStatusCode().value());
    assertEquals(FHIR_JSON_CONTENT_TYPE, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    Patient parsed = FhirR4Validation.parseStrict(response.getBody(), Patient.class);
    assertEquals("patient-ok", parsed.getIdElement().getIdPart());
    assertEquals("Okafor", parsed.getNameFirstRep().getFamily());
    FhirR4Validation.assertValid(parsed);
    assertEquals(FhirVersionEnum.R4, serializer.context().getVersion().getVersion());
  }

  @Test
  void concurrentSerialisationProducesIdenticalOutput() throws Exception {
    List<IBaseResource> resources = resources();
    List<String> baselines = resources.stream().map(r -> serializer.ok(r).getBody()).toList();
    assertEquals(THREADS, new HashSet<>(baselines).size());

    ExecutorService executor = Executors.newFixedThreadPool(THREADS);
    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < THREADS; i++) {
        IBaseResource resource = resources.get(i);
        String baseline = baselines.get(i);
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  int identical = 0;
                  for (int n = 0; n < ITERATIONS; n++) {
                    if (baseline.equals(serializer.ok(resource).getBody())) {
                      identical++;
                    }
                  }
                  return identical;
                }));
      }
      assertTrue(ready.await(30, TimeUnit.SECONDS));
      start.countDown();

      // Every thread produced its resource's single-threaded output on every iteration.
      for (Future<Integer> future : futures) {
        assertEquals(ITERATIONS, future.get(60, TimeUnit.SECONDS));
      }
    } finally {
      executor.shutdownNow();
      executor.awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /**
   * Asserts that {@code error} and {@code writeError} both render the exception with the given
   * status as one identical {@code OperationOutcome} body: one valid {@code error} issue with the
   * given code and the exception's diagnostics, which also satisfy {@code diagnosticsCheck}.
   */
  private void assertErrorResponse(
      FhirApiException exception, int status, IssueType code, Consumer<String> diagnosticsCheck)
      throws IOException {
    assertEquals(status, exception.getStatus().value());
    assertEquals(code, exception.getIssueType());

    ResponseEntity<String> response = serializer.error(exception);
    assertEquals(status, response.getStatusCode().value());
    assertEquals(FHIR_JSON_CONTENT_TYPE, response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE));
    assertOutcome(response.getBody(), exception, code, diagnosticsCheck);

    MockHttpServletResponse servletResponse = new MockHttpServletResponse();
    serializer.writeError(servletResponse, exception);
    assertEquals(status, servletResponse.getStatus());
    assertEquals(FHIR_JSON_CONTENT_TYPE, servletResponse.getContentType());
    String written = servletResponse.getContentAsString(StandardCharsets.UTF_8);
    assertOutcome(written, exception, code, diagnosticsCheck);
    assertEquals(response.getBody(), written);
  }

  private static void assertOutcome(
      String body, FhirApiException exception, IssueType code, Consumer<String> diagnosticsCheck) {
    OperationOutcome outcome = FhirR4Validation.parseStrict(body, OperationOutcome.class);
    assertEquals(1, outcome.getIssue().size());
    OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
    assertEquals(IssueSeverity.ERROR, issue.getSeverity());
    assertEquals(code, issue.getCode());
    assertEquals(exception.getDiagnostics(), issue.getDiagnostics());
    diagnosticsCheck.accept(issue.getDiagnostics());
    FhirR4Validation.assertValid(outcome);
  }

  /** Eight distinct resources with fixed values and explicit ids, contained resources included. */
  private static List<IBaseResource> resources() {
    Patient first = patient("patient-1", "Nakamura", "Aiko");
    first.getMeta().setLastUpdated(Date.from(UPDATED));

    Patient second = patient("patient-2", "Okafor", "Chidi");
    second.setGender(AdministrativeGender.FEMALE).setBirthDateElement(new DateType("1990-05-17"));

    Patient third = patient("patient-3", "Haugen", "Ingrid");
    third.addIdentifier().setSystem(IDENTIFIER_SYSTEM).setValue("12345678");

    Encounter encounter = new Encounter();
    encounter.setId("enrollment1-event1");
    encounter.getMeta().setLastUpdated(Date.from(UPDATED));
    encounter
        .setStatus(EncounterStatus.FINISHED)
        .setClass_(
            new Coding(ENCOUNTER_CLASS_SYSTEM, ENCOUNTER_CLASS_CODE, ENCOUNTER_CLASS_DISPLAY))
        .setSubject(new Reference("Patient/patient-1"))
        .getPeriod()
        .setStart(Date.from(OCCURRED));

    Observation weight = new Observation();
    weight.setId("enrollment1-event1-weight");
    weight
        .setStatus(ObservationStatus.FINAL)
        .setCode(concept(LOINC_SYSTEM, LOINC_BODY_WEIGHT_CODE, LOINC_BODY_WEIGHT_DISPLAY))
        .setSubject(new Reference("Patient/patient-1"))
        .setEffective(new DateTimeType(Date.from(OCCURRED)))
        .setValue(quantity(72.5, BODY_WEIGHT_UNIT));

    Immunization immunization = new Immunization();
    immunization.setId("enrollment1-event1-vaccine");
    immunization
        .setStatus(ImmunizationStatus.COMPLETED)
        .setVaccineCode(concept(CVX_SYSTEM, CVX_CODE, CVX_DISPLAY))
        .setPatient(new Reference("Patient/patient-1"))
        .setOccurrence(new DateTimeType(Date.from(OCCURRED)))
        .setLotNumber("LOT-2024-03");

    Bundle bundle = new Bundle().setType(BundleType.SEARCHSET).setTotal(1);
    bundle.setId("bundle-1");
    bundle
        .addEntry()
        .setFullUrl("http://localhost/api/fhir/Patient/patient-4")
        .setResource(patient("patient-4", "Silva", "Ana"))
        .getSearch()
        .setMode(SearchEntryMode.MATCH);

    Observation height = new Observation();
    height.setId("enrollment1-event1-height");
    height.addContained(patient("p1", "Mensah", "Kofi"));
    height
        .setStatus(ObservationStatus.PRELIMINARY)
        .setCode(concept(LOINC_SYSTEM, LOINC_BODY_HEIGHT_CODE, LOINC_BODY_HEIGHT_DISPLAY))
        .setSubject(new Reference("#p1"))
        .setValue(quantity(172.0, BODY_HEIGHT_UNIT));

    return List.of(first, second, third, encounter, weight, immunization, bundle, height);
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

  private static Quantity quantity(double value, String unit) {
    return new Quantity().setValue(value).setUnit(unit).setSystem(UCUM_SYSTEM).setCode(unit);
  }
}
