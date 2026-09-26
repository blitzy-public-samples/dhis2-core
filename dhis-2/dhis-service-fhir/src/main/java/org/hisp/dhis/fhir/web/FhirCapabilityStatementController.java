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
import org.hisp.dhis.fhir.service.FhirCapabilityStatementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FHIR R4 capabilities interaction: serves the server's CapabilityStatement at {@code
 * /api/fhir/metadata}.
 *
 * <p>The handler passes the whole request to {@link FhirCapabilityStatementService}, which accepts
 * only the {@code _format} parameter and derives the statement from the usable resource mappings,
 * and returns the statement as FHIR JSON through {@link FhirResourceSerializer}.
 */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:data"})
@RestController
@RequestMapping("/api/fhir")
public class FhirCapabilityStatementController {
  private final FhirCapabilityStatementService capabilityStatementService;

  private final FhirResourceSerializer serializer;

  /**
   * Creates the controller.
   *
   * @param capabilityStatementService builds the CapabilityStatement from the resource mappings
   * @param serializer encodes the CapabilityStatement as FHIR JSON
   */
  public FhirCapabilityStatementController(
      FhirCapabilityStatementService capabilityStatementService,
      FhirResourceSerializer serializer) {
    this.capabilityStatementService = capabilityStatementService;
    this.serializer = serializer;
  }

  /** Returns the CapabilityStatement of the FHIR API, built from the usable resource mappings. */
  @GetMapping("/metadata")
  public ResponseEntity<String> readCapabilityStatement(HttpServletRequest request) {
    return serializer.ok(capabilityStatementService.capabilities(request));
  }
}
