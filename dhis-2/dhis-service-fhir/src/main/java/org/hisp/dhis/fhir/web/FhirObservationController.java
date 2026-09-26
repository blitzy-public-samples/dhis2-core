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
import java.util.Objects;
import org.hisp.dhis.common.OpenApi;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.FhirResourceSerializer;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.service.FhirEventResourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FHIR R4 read-only endpoints for the {@code Observation} resource: read and search-type.
 *
 * <ul>
 *   <li>{@code GET /api/fhir/Observation/{id}} returns one {@code Observation}, whose logical id is
 *       {@code {enrollmentUid}-{eventUid}-{dataElementUid}}.
 *   <li>{@code GET /api/fhir/Observation} returns a {@code searchset} Bundle of {@code
 *       Observation}s, selected by {@code patient}, {@code subject}, {@code _id} and {@code code},
 *       and paged by {@code _count} and {@code _page}.
 * </ul>
 *
 * <p>Both handlers delegate to {@link FhirEventResourceService} with {@link
 * FhirResourceType#OBSERVATION} and return the result as FHIR JSON through {@link
 * FhirResourceSerializer#ok}. Every {@link FhirApiException} the service raises propagates to the
 * FHIR exception handler, which renders it as an {@code OperationOutcome}.
 */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:data"})
@RestController
@RequestMapping("/api/fhir/Observation")
public class FhirObservationController {

  private final FhirEventResourceService eventResourceService;

  private final FhirResourceSerializer serializer;

  /**
   * Creates the controller.
   *
   * @param eventResourceService reads and searches the event-derived FHIR resources
   * @param serializer encodes the returned resources as FHIR JSON
   * @throws NullPointerException if an argument is {@code null}
   */
  public FhirObservationController(
      FhirEventResourceService eventResourceService, FhirResourceSerializer serializer) {
    this.eventResourceService =
        Objects.requireNonNull(eventResourceService, "eventResourceService");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
  }

  /**
   * Reads one {@code Observation} by its logical id.
   *
   * @param id the logical id from the request path
   * @param request the current HTTP request, whose query parameters the service validates
   * @return {@code 200} with the {@code Observation} as FHIR JSON
   * @throws FhirApiException {@code 501 not-supported}, {@code 400 invalid}, {@code 403 forbidden}
   *     or {@code 404 not-found}, as raised by the service
   */
  @GetMapping("/{id}")
  public ResponseEntity<String> readObservation(
      @PathVariable String id, HttpServletRequest request) {
    return serializer.ok(eventResourceService.read(FhirResourceType.OBSERVATION, id, request));
  }

  /**
   * Searches {@code Observation}s with the query parameters of the request.
   *
   * @param request the current HTTP request carrying the search parameters
   * @return {@code 200} with the {@code searchset} Bundle as FHIR JSON
   * @throws FhirApiException {@code 501 not-supported}, {@code 400 invalid} or {@code 403
   *     forbidden}, as raised by the service
   */
  @GetMapping
  public ResponseEntity<String> searchObservations(HttpServletRequest request) {
    return serializer.ok(eventResourceService.search(FhirResourceType.OBSERVATION, request));
  }
}
