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
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.service.FhirEventResourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FHIR R4 read-only endpoints for the {@code Immunization} resource: read and search-type.
 *
 * <ul>
 *   <li>{@code GET /api/fhir/Immunization/{id}} reads one Immunization by its logical id {@code
 *       {enrollment}-{event}-{dataElement}}.
 *   <li>{@code GET /api/fhir/Immunization} searches Immunizations and returns a {@code searchset}
 *       Bundle.
 * </ul>
 *
 * <p>Every handler delegates to {@link FhirEventResourceService} with {@link
 * FhirResourceType#IMMUNIZATION} and returns the result as FHIR JSON with the content type {@value
 * FhirResourceSerializer#FHIR_JSON_CONTENT_TYPE}. Errors propagate as exceptions: a {@link
 * org.hisp.dhis.fhir.FhirApiException} is rendered as an {@code OperationOutcome} by the FHIR
 * exception handler, and any other exception by the platform's exception handling.
 */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:data"})
@RestController
@RequestMapping("/api/fhir/Immunization")
public class FhirImmunizationController {

  private final FhirEventResourceService eventResourceService;

  private final FhirResourceSerializer serializer;

  /**
   * Creates the controller.
   *
   * @param eventResourceService reads and searches the event-derived FHIR resources
   * @param serializer encodes the resources as FHIR JSON responses
   */
  public FhirImmunizationController(
      FhirEventResourceService eventResourceService, FhirResourceSerializer serializer) {
    this.eventResourceService = eventResourceService;
    this.serializer = serializer;
  }

  /** Returns the {@code Immunization} with the logical id {@code id} as a {@code 200} response. */
  @GetMapping("/{id}")
  public ResponseEntity<String> readImmunization(
      @PathVariable String id, HttpServletRequest request) {
    return serializer.ok(eventResourceService.read(FhirResourceType.IMMUNIZATION, id, request));
  }

  /** Returns the {@code searchset} Bundle of the Immunizations matching the query parameters. */
  @GetMapping
  public ResponseEntity<String> searchImmunizations(HttpServletRequest request) {
    return serializer.ok(eventResourceService.search(FhirResourceType.IMMUNIZATION, request));
  }
}
