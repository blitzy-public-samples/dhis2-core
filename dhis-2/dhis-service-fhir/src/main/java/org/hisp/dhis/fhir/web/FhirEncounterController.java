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
 * FHIR R4 read-only endpoints for the Encounter resource: read and search-type over Tracker events
 * of mapped program stages.
 *
 * <p>Each handler passes the whole request to {@link FhirEventResourceService}, which validates the
 * query string, and returns the result as FHIR JSON through {@link FhirResourceSerializer}.
 */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:data"})
@RestController
@RequestMapping("/api/fhir/Encounter")
public class FhirEncounterController {
  private final FhirEventResourceService eventResourceService;

  private final FhirResourceSerializer serializer;

  /**
   * Creates the controller.
   *
   * @param eventResourceService reads and searches the event-derived FHIR resources
   * @param serializer encodes the resulting resources as FHIR JSON
   */
  public FhirEncounterController(
      FhirEventResourceService eventResourceService, FhirResourceSerializer serializer) {
    this.eventResourceService = eventResourceService;
    this.serializer = serializer;
  }

  /** Returns the Encounter with the logical id {@code {enrollmentUid}-{eventUid}}. */
  @GetMapping("/{id}")
  public ResponseEntity<String> readEncounter(@PathVariable String id, HttpServletRequest request) {
    return serializer.ok(eventResourceService.read(FhirResourceType.ENCOUNTER, id, request));
  }

  /** Returns a {@code searchset} Bundle of the Encounters matching the query parameters. */
  @GetMapping
  public ResponseEntity<String> searchEncounters(HttpServletRequest request) {
    return serializer.ok(eventResourceService.search(FhirResourceType.ENCOUNTER, request));
  }
}
