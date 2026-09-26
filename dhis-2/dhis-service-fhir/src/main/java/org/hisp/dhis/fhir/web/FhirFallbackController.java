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

import org.hisp.dhis.common.OpenApi;
import org.hisp.dhis.fhir.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Answers unmapped FHIR GET, HEAD, POST, PUT, PATCH and DELETE requests: 501 for R4 resource types
 * and write methods, else 404.
 */
@OpenApi.Ignore
@RestController
public class FhirFallbackController {
  private final FhirResourceSerializer serializer;

  public FhirFallbackController(FhirResourceSerializer serializer) {
    this.serializer = serializer;
  }

  /** Rejects a type-level GET: {@code 501} for an R4 resource type, otherwise {@code 404}. */
  @GetMapping("/api/fhir/{type}")
  public ResponseEntity<String> unbridgedType(@PathVariable String type) {
    throw unsupportedOrUnknown(type);
  }

  /** Rejects an instance GET: {@code 501} for an R4 resource type, otherwise {@code 404}. */
  @GetMapping("/api/fhir/{type}/{id}")
  public ResponseEntity<String> unbridgedInstance(@PathVariable String type) {
    throw unsupportedOrUnknown(type);
  }

  /** Rejects every other GET under {@code /api/fhir} with {@code 404 not-found}. */
  @GetMapping({"/api/fhir", "/api/fhir/**"})
  public ResponseEntity<String> unknownPath() {
    throw FhirApiException.notFound();
  }

  /** Rejects every write method under {@code /api/fhir} with {@code 501 not-supported}. */
  @RequestMapping(
      value = {"/api/fhir", "/api/fhir/**"},
      method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
  public ResponseEntity<String> writeNotSupported() {
    throw FhirApiException.notSupported("Write interactions are not supported");
  }

  private FhirApiException unsupportedOrUnknown(String type) {
    return serializer.context().getResourceTypes().contains(type)
        ? FhirApiException.notSupported("Resource type " + type + " is not supported")
        : FhirApiException.notFound();
  }
}
