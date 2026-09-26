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
import org.springframework.http.*;
import org.springframework.stereotype.Component;

/** Serialises FHIR R4 resources and {@link FhirApiException} outcomes as compact FHIR JSON. */
@Component
public class FhirResourceSerializer {
  public static final String FHIR_JSON_CONTENT_TYPE = "application/fhir+json;charset=UTF-8";
  public static final MediaType FHIR_JSON_MEDIA_TYPE =
      MediaType.parseMediaType(FHIR_JSON_CONTENT_TYPE);
  private final FhirContext context = FhirContext.forR4Cached();

  public FhirContext context() {
    return context;
  }

  public ResponseEntity<String> ok(IBaseResource resource) {
    Objects.requireNonNull(resource, "resource");
    return ResponseEntity.ok().contentType(FHIR_JSON_MEDIA_TYPE).body(encode(resource));
  }

  /** Creates the response with the exception's status and one-issue {@code OperationOutcome}. */
  public ResponseEntity<String> error(FhirApiException exception) {
    Objects.requireNonNull(exception, "exception");
    return ResponseEntity.status(exception.getStatus())
        .contentType(FHIR_JSON_MEDIA_TYPE)
        .body(encode(outcome(exception)));
  }

  /** Writes the {@link #error} response to the uncommitted servlet response and commits it. */
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

  private static OperationOutcome outcome(FhirApiException exception) {
    OperationOutcome outcome = new OperationOutcome();
    outcome
        .addIssue()
        .setSeverity(OperationOutcome.IssueSeverity.ERROR)
        .setCode(exception.getIssueType())
        .setDiagnostics(exception.getDiagnostics());
    return outcome;
  }

  private String encode(IBaseResource resource) {
    IParser parser = context.newJsonParser();
    parser.setPrettyPrint(false);
    return parser.encodeResourceToString(resource);
  }
}
