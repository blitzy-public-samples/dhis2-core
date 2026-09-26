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
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.FhirResourceSerializer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers every {@code /api/fhir/**} request not handled by a resource controller: {@code 501
 * not-supported} for known but unmapped R4 resource types and for write methods, {@code 404
 * not-found} otherwise.
 *
 * <p>Every handler throws a {@link FhirApiException}, which the FHIR exception handler renders as
 * an {@code OperationOutcome}:
 *
 * <ul>
 *   <li>{@code GET /api/fhir/{type}} and {@code GET /api/fhir/{type}/{id}}: {@code 501} when {@code
 *       type} is an R4 resource type name (case-sensitive, for example {@code Condition}),
 *       otherwise {@code 404}.
 *   <li>Any other {@code GET} on {@code /api/fhir} or below: {@code 404}.
 *   <li>{@code POST}, {@code PUT}, {@code PATCH} and {@code DELETE} on {@code /api/fhir} or below:
 *       {@code 501}.
 * </ul>
 *
 * <p>Paths mapped by the resource controllers, for example {@code /api/fhir/Patient/{id}} or {@code
 * /api/fhir/metadata}, are more specific and take precedence over these mappings. The controller is
 * excluded from the OpenAPI documentation.
 */
@OpenApi.Ignore
@RestController
public class FhirFallbackController {

  private final FhirResourceSerializer serializer;

  /**
   * Creates the controller.
   *
   * @param serializer the FHIR serializer whose R4 context supplies the known resource type names
   */
  public FhirFallbackController(FhirResourceSerializer serializer) {
    this.serializer = serializer;
  }

  /**
   * Handles a type-level request for a resource type no resource controller maps.
   *
   * @param type the resource type segment of the path
   * @return never returns normally
   * @throws FhirApiException {@code 501 not-supported} when {@code type} is an R4 resource type
   *     name, otherwise {@code 404 not-found}
   */
  @GetMapping("/api/fhir/{type}")
  public ResponseEntity<String> unbridgedType(@PathVariable String type) {
    throw unsupportedOrUnknown(type);
  }

  /**
   * Handles an instance-level request for a resource type no resource controller maps.
   *
   * @param type the resource type segment of the path
   * @return never returns normally
   * @throws FhirApiException {@code 501 not-supported} when {@code type} is an R4 resource type
   *     name, otherwise {@code 404 not-found}
   */
  @GetMapping("/api/fhir/{type}/{id}")
  public ResponseEntity<String> unbridgedInstance(@PathVariable String type) {
    throw unsupportedOrUnknown(type);
  }

  /**
   * Handles every other {@code GET} on {@code /api/fhir} or below.
   *
   * @return never returns normally
   * @throws FhirApiException always {@code 404 not-found}
   */
  @GetMapping({"/api/fhir", "/api/fhir/**"})
  public ResponseEntity<String> unknownPath() {
    throw FhirApiException.notFound();
  }

  /**
   * Handles every {@code POST}, {@code PUT}, {@code PATCH} and {@code DELETE} on {@code /api/fhir}
   * or below.
   *
   * @return never returns normally
   * @throws FhirApiException always {@code 501 not-supported}
   */
  @RequestMapping(
      value = {"/api/fhir", "/api/fhir/**"},
      method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE})
  public ResponseEntity<String> writeNotSupported() {
    throw FhirApiException.notSupported("Write interactions are not supported");
  }

  /**
   * Returns {@code 501 not-supported} when {@code type} is an R4 resource type name of the shared
   * FHIR context, otherwise {@code 404 not-found}.
   */
  private FhirApiException unsupportedOrUnknown(String type) {
    return serializer.context().getResourceTypes().contains(type)
        ? FhirApiException.notSupported("Resource type " + type + " is not supported")
        : FhirApiException.notFound();
  }
}
