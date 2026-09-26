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
package org.hisp.dhis.fhir;

import java.util.Objects;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.http.HttpStatus;

/** FHIR R4 API error with the status, issue type and diagnostics of its {@code error} issue. */
public final class FhirApiException extends RuntimeException {
  private final HttpStatus status;
  private final OperationOutcome.IssueType issueType;
  private final String diagnostics;

  private FhirApiException(
      HttpStatus status, OperationOutcome.IssueType issueType, String diagnostics) {
    super(diagnostics);
    this.status = status;
    this.issueType = issueType;
    this.diagnostics = diagnostics;
  }

  /** Creates the {@code 404 not-found} error with fixed diagnostics naming no id or path. */
  public static FhirApiException notFound() {
    return new FhirApiException(
        HttpStatus.NOT_FOUND,
        OperationOutcome.IssueType.NOTFOUND,
        "The requested resource was not found");
  }

  /** Creates the {@code 403 forbidden} error with fixed diagnostics naming no id or path. */
  public static FhirApiException forbidden() {
    return new FhirApiException(
        HttpStatus.FORBIDDEN,
        OperationOutcome.IssueType.FORBIDDEN,
        "Access to the requested resource is not permitted");
  }

  /** Creates the {@code 400 invalid} error: {@code Invalid parameter '<name>': <detail>}. */
  public static FhirApiException invalidParameter(String name, String detail) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(detail, "detail");
    return new FhirApiException(
        HttpStatus.BAD_REQUEST,
        OperationOutcome.IssueType.INVALID,
        "Invalid parameter '" + name + "': " + detail);
  }

  /** Creates the {@code 501 not-supported} error with {@code detail} as its diagnostics. */
  public static FhirApiException notSupported(String detail) {
    Objects.requireNonNull(detail, "detail");
    return new FhirApiException(
        HttpStatus.NOT_IMPLEMENTED, OperationOutcome.IssueType.NOTSUPPORTED, detail);
  }

  public HttpStatus getStatus() {
    return status;
  }

  public OperationOutcome.IssueType getIssueType() {
    return issueType;
  }

  public String getDiagnostics() {
    return diagnostics;
  }
}
