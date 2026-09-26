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

import static org.hisp.dhis.fhir.web.FhirOpenApi.FHIR_JSON;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.hisp.dhis.common.OpenApi;
import org.hisp.dhis.fhir.*;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.service.FhirEventResourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** FHIR R4 read and search-type endpoints for {@code Observation}. */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:data"})
@RestController
@RequestMapping("/api/fhir/Observation")
public class FhirObservationController {
  private final FhirEventResourceService eventResourceService;
  private final FhirResourceSerializer serializer;

  public FhirObservationController(
      FhirEventResourceService eventResourceService, FhirResourceSerializer serializer) {
    this.eventResourceService =
        Objects.requireNonNull(eventResourceService, "eventResourceService");
    this.serializer = Objects.requireNonNull(serializer, "serializer");
  }

  @OpenApi.Response(value = FhirOpenApi.FhirObservationResource.class, mediaTypes = FHIR_JSON)
  @OpenApi.Params(FhirOpenApi.FhirFormatParameter.class)
  @GetMapping("/{id}")
  public ResponseEntity<String> readObservation(
      @OpenApi.Description("`{enrollmentUid}-{eventUid}-{dataElementUid}`") @PathVariable String id,
      HttpServletRequest request) {
    return serializer.ok(eventResourceService.read(FhirResourceType.OBSERVATION, id, request));
  }

  @OpenApi.Response(value = FhirOpenApi.FhirSearchsetBundle.class, mediaTypes = FHIR_JSON)
  @OpenApi.Params(FhirOpenApi.FhirObservationSearchParameters.class)
  @OpenApi.Description("Requires `patient`, `subject` or `_id`.")
  @GetMapping
  public ResponseEntity<String> searchObservations(HttpServletRequest request) {
    return serializer.ok(eventResourceService.search(FhirResourceType.OBSERVATION, request));
  }
}
