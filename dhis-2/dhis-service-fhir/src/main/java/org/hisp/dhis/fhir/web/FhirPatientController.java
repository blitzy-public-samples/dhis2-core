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
package org.hisp.dhis.fhir.web;

import jakarta.servlet.http.HttpServletRequest;
import org.hisp.dhis.common.OpenApi;
import org.hisp.dhis.fhir.FhirResourceSerializer;
import org.hisp.dhis.fhir.service.FhirPatientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FHIR R4 read-only endpoints for the {@code Patient} resource: read, search-type and {@code
 * $everything}.
 *
 * <ul>
 *   <li>{@code GET /api/fhir/Patient/{id}} reads one {@code Patient} by its tracked entity UID.
 *   <li>{@code GET /api/fhir/Patient} searches {@code Patient}s and returns a {@code searchset}
 *       Bundle.
 *   <li>{@code GET /api/fhir/Patient/{id}/$everything} returns a {@code searchset} Bundle holding
 *       the {@code Patient} followed by its {@code Encounter}s, {@code Immunization}s and {@code
 *       Observation}s.
 * </ul>
 *
 * <p>Every handler passes the whole request to {@link FhirPatientService}, which validates the
 * query string, and returns the result as FHIR JSON with the content type {@value
 * FhirResourceSerializer#FHIR_JSON_CONTENT_TYPE}. Errors propagate as exceptions: a {@link
 * org.hisp.dhis.fhir.FhirApiException} is rendered as an {@code OperationOutcome} by the FHIR
 * exception handler, and any other exception by the platform's exception handling.
 */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:data"})
@RestController
@RequestMapping("/api/fhir/Patient")
public class FhirPatientController {

  private final FhirPatientService patientService;

  private final FhirResourceSerializer serializer;

  /**
   * Creates the controller.
   *
   * @param patientService reads, searches and assembles the {@code Patient} resources
   * @param serializer encodes the resulting resources as FHIR JSON responses
   */
  public FhirPatientController(
      FhirPatientService patientService, FhirResourceSerializer serializer) {
    this.patientService = patientService;
    this.serializer = serializer;
  }

  /** Returns the {@code Patient} whose logical id is the tracked entity UID {@code id}. */
  @GetMapping("/{id}")
  public ResponseEntity<String> readPatient(@PathVariable String id, HttpServletRequest request) {
    return serializer.ok(patientService.read(id, request));
  }

  /** Returns a {@code searchset} Bundle of the {@code Patient}s matching the query parameters. */
  @GetMapping
  public ResponseEntity<String> searchPatients(HttpServletRequest request) {
    return serializer.ok(patientService.search(request));
  }

  /**
   * Returns a {@code searchset} Bundle holding the {@code Patient} {@code id} followed by its
   * event-derived resources.
   */
  @GetMapping("/{id}/$everything")
  public ResponseEntity<String> patientEverything(
      @PathVariable String id, HttpServletRequest request) {
    return serializer.ok(patientService.everything(id, request));
  }
}
