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

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Serialises FHIR R4 resources, Bundles, CapabilityStatements and error {@code OperationOutcome}s
 * as compact FHIR JSON. Every body it produces carries the content type {@value
 * #FHIR_JSON_CONTENT_TYPE}.
 *
 * <p>All serialisation uses one shared R4 {@link FhirContext} and creates a new JSON parser for
 * each serialisation.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * ResponseEntity<String> found = serializer.ok(patient);
 * ResponseEntity<String> missing = serializer.error(FhirApiException.notFound());
 * serializer.writeError(servletResponse, FhirApiException.notFound());
 * }</pre>
 *
 * <p>An error {@code OperationOutcome} has exactly one issue, holding the severity {@code error},
 * the {@link FhirApiException#getIssueType() issue type} as {@code code} and the {@link
 * FhirApiException#getDiagnostics() diagnostics}, and no other element.
 */
@Component
public class FhirResourceSerializer {

  /** The content type of every FHIR response: FHIR JSON encoded as UTF-8. */
  public static final String FHIR_JSON_CONTENT_TYPE = "application/fhir+json;charset=UTF-8";

  /** {@link #FHIR_JSON_CONTENT_TYPE} as a media type. */
  public static final MediaType FHIR_JSON_MEDIA_TYPE =
      MediaType.parseMediaType(FHIR_JSON_CONTENT_TYPE);

  private final FhirContext context = FhirContext.forR4Cached();

  /**
   * Returns the shared FHIR R4 context used for every serialisation, for example to list the R4
   * resource type names or to create a parser for reading FHIR JSON.
   *
   * @return the FHIR R4 context
   */
  public FhirContext context() {
    return context;
  }

  /**
   * Creates a {@code 200 OK} response whose body is the resource encoded as compact FHIR JSON, with
   * the content type {@value #FHIR_JSON_CONTENT_TYPE}.
   *
   * @param resource the resource, Bundle or CapabilityStatement to return
   * @return the response carrying the encoded resource
   * @throws NullPointerException if {@code resource} is {@code null}
   */
  public ResponseEntity<String> ok(IBaseResource resource) {
    Objects.requireNonNull(resource, "resource");
    return ResponseEntity.ok().contentType(FHIR_JSON_MEDIA_TYPE).body(encode(resource));
  }

  /**
   * Creates the error response for the exception: its HTTP status, the content type {@value
   * #FHIR_JSON_CONTENT_TYPE}, and a body holding the {@code OperationOutcome} with its single
   * {@code error} issue.
   *
   * @param exception the FHIR error to render
   * @return the response carrying the encoded {@code OperationOutcome}
   * @throws NullPointerException if {@code exception} is {@code null}
   */
  public ResponseEntity<String> error(FhirApiException exception) {
    Objects.requireNonNull(exception, "exception");
    return ResponseEntity.status(exception.getStatus())
        .contentType(FHIR_JSON_MEDIA_TYPE)
        .body(encode(outcome(exception)));
  }

  /**
   * Writes the error response for the exception directly to the servlet response: the same status,
   * content type and {@code OperationOutcome} body as {@link #error(FhirApiException)}. The body is
   * encoded before the response is touched, then written as UTF-8 bytes and flushed, which commits
   * the response.
   *
   * @param response the servlet response to write to; it must not be committed
   * @param exception the FHIR error to render
   * @throws IOException if writing the body fails
   * @throws NullPointerException if {@code response} or {@code exception} is {@code null}
   */
  public void writeError(HttpServletResponse response, FhirApiException exception)
      throws IOException {
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(exception, "exception");
    byte[] body = encode(outcome(exception)).getBytes(StandardCharsets.UTF_8);
    response.setStatus(exception.getStatus().value());
    response.setContentType(FHIR_JSON_CONTENT_TYPE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    ServletOutputStream output = response.getOutputStream();
    output.write(body);
    output.flush();
  }

  /**
   * Builds the {@code OperationOutcome} of the exception: one issue with severity {@code error},
   * the exception's issue type as {@code code} and its diagnostics.
   */
  private static OperationOutcome outcome(FhirApiException exception) {
    OperationOutcome outcome = new OperationOutcome();
    outcome
        .addIssue()
        .setSeverity(OperationOutcome.IssueSeverity.ERROR)
        .setCode(exception.getIssueType())
        .setDiagnostics(exception.getDiagnostics());
    return outcome;
  }

  /** Encodes the resource as compact FHIR JSON with a new JSON parser. */
  private String encode(IBaseResource resource) {
    IParser parser = context.newJsonParser();
    parser.setPrettyPrint(false);
    return parser.encodeResourceToString(resource);
  }
}
