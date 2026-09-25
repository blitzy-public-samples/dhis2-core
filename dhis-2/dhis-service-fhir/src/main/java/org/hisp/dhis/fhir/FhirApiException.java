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

/**
 * The error raised by the FHIR R4 API under {@code /api/fhir/**}. Each instance carries the HTTP
 * status, the FHIR {@link OperationOutcome.IssueType issue type} and the diagnostics text of the
 * {@code OperationOutcome} issue (severity {@code error}) that is rendered for it.
 *
 * <p>Instances are created only through the four factories, each producing one fixed pairing of
 * status and issue type:
 *
 * <ul>
 *   <li>{@link #notFound()}: {@code 404}, {@code not-found}, fixed diagnostics.
 *   <li>{@link #forbidden()}: {@code 403}, {@code forbidden}, fixed diagnostics.
 *   <li>{@link #invalidParameter(String, String)}: {@code 400}, {@code invalid}, diagnostics naming
 *       the parameter and stating the detail.
 *   <li>{@link #notSupported(String)}: {@code 501}, {@code not-supported}, the given diagnostics.
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * throw FhirApiException.invalidParameter("_count", "must be a positive integer");
 * }</pre>
 *
 * <p>The exception message ({@link #getMessage()}) equals {@link #getDiagnostics()}. Instances are
 * immutable.
 */
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

  /**
   * Creates the not-found error: HTTP {@code 404}, issue code {@code not-found}, diagnostics "The
   * requested resource was not found". The diagnostics are identical for every call and contain no
   * id or path.
   *
   * @return the not-found error
   */
  public static FhirApiException notFound() {
    return new FhirApiException(
        HttpStatus.NOT_FOUND,
        OperationOutcome.IssueType.NOTFOUND,
        "The requested resource was not found");
  }

  /**
   * Creates the forbidden error: HTTP {@code 403}, issue code {@code forbidden}, diagnostics
   * "Access to the requested resource is not permitted". The diagnostics are identical for every
   * call and contain no id, path or metadata name.
   *
   * @return the forbidden error
   */
  public static FhirApiException forbidden() {
    return new FhirApiException(
        HttpStatus.FORBIDDEN,
        OperationOutcome.IssueType.FORBIDDEN,
        "Access to the requested resource is not permitted");
  }

  /**
   * Creates the invalid-parameter error: HTTP {@code 400}, issue code {@code invalid}, diagnostics
   * {@code Invalid parameter '<name>': <detail>}.
   *
   * @param name the offending FHIR search or control parameter name, for example {@code
   *     identifier}, or several parameter names joined by {@code ", "} when one rejection concerns
   *     several parameters
   * @param detail what is wrong with the parameter; it names no parameter other than {@code name}
   * @return the invalid-parameter error
   * @throws NullPointerException if {@code name} or {@code detail} is {@code null}
   */
  public static FhirApiException invalidParameter(String name, String detail) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(detail, "detail");
    return new FhirApiException(
        HttpStatus.BAD_REQUEST,
        OperationOutcome.IssueType.INVALID,
        "Invalid parameter '" + name + "': " + detail);
  }

  /**
   * Creates the not-supported error: HTTP {@code 501}, issue code {@code not-supported},
   * diagnostics equal to {@code detail}.
   *
   * @param detail the diagnostics text, used verbatim, for example "Write interactions are not
   *     supported"
   * @return the not-supported error
   * @throws NullPointerException if {@code detail} is {@code null}
   */
  public static FhirApiException notSupported(String detail) {
    Objects.requireNonNull(detail, "detail");
    return new FhirApiException(
        HttpStatus.NOT_IMPLEMENTED, OperationOutcome.IssueType.NOTSUPPORTED, detail);
  }

  /**
   * @return the HTTP status of the response: {@code 404}, {@code 403}, {@code 400} or {@code 501}
   */
  public HttpStatus getStatus() {
    return status;
  }

  /**
   * @return the {@code OperationOutcome.issue.code}: {@code not-found}, {@code forbidden}, {@code
   *     invalid} or {@code not-supported}
   */
  public OperationOutcome.IssueType getIssueType() {
    return issueType;
  }

  /**
   * @return the {@code OperationOutcome.issue.diagnostics} text
   */
  public String getDiagnostics() {
    return diagnostics;
  }
}
