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

import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.FhirResourceSerializer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link FhirApiException}s raised by the FHIR controllers as FHIR {@code OperationOutcome}
 * responses.
 *
 * <p>The advice applies only to controllers in the package {@code org.hisp.dhis.fhir.web} and is
 * consulted before any other controller advice. Each response carries the exception's HTTP status
 * ({@code 404}, {@code 403}, {@code 400} or {@code 501}), the content type {@value
 * FhirResourceSerializer#FHIR_JSON_CONTENT_TYPE} and an {@code OperationOutcome} with one issue of
 * severity {@code error}, whose {@code code} and {@code diagnostics} are the exception's issue type
 * and diagnostics.
 *
 * <p>No other exception type is handled here. Any other exception raised by a FHIR controller, for
 * example an exceeded Tracker export deadline, is rendered by the platform's exception handling
 * with its usual status and body.
 */
@RestControllerAdvice(basePackageClasses = FhirPatientController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FhirExceptionHandler {

  private final FhirResourceSerializer serializer;

  /**
   * Creates the exception handler.
   *
   * @param serializer encodes the {@code OperationOutcome} of each handled exception
   */
  public FhirExceptionHandler(FhirResourceSerializer serializer) {
    this.serializer = serializer;
  }

  /**
   * Returns the {@code OperationOutcome} response of the exception: its HTTP status, FHIR JSON
   * content type and single {@code error} issue.
   *
   * @param ex the FHIR error raised by a FHIR controller
   * @return the response carrying the encoded {@code OperationOutcome}
   */
  @ExceptionHandler(FhirApiException.class)
  public ResponseEntity<String> handle(FhirApiException ex) {
    return serializer.error(ex);
  }
}
