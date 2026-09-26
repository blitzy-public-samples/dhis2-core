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
import org.hisp.dhis.common.OpenApi;
import org.hisp.dhis.fhir.FhirResourceSerializer;
import org.hisp.dhis.fhir.service.FhirPatientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** FHIR R4 read, search-type and {@code $everything} endpoints for {@code Patient}. */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:data"})
@RestController
@RequestMapping("/api/fhir/Patient")
public class FhirPatientController {
  private final FhirPatientService patientService;
  private final FhirResourceSerializer serializer;

  public FhirPatientController(
      FhirPatientService patientService, FhirResourceSerializer serializer) {
    this.patientService = patientService;
    this.serializer = serializer;
  }

  @OpenApi.Response(value = FhirOpenApi.FhirPatientResource.class, mediaTypes = FHIR_JSON)
  @OpenApi.Params(FhirOpenApi.FhirFormatParameter.class)
  @GetMapping("/{id}")
  public ResponseEntity<String> readPatient(
      @OpenApi.Description("The tracked entity UID") @PathVariable String id,
      HttpServletRequest request) {
    return serializer.ok(patientService.read(id, request));
  }

  @OpenApi.Response(value = FhirOpenApi.FhirSearchsetBundle.class, mediaTypes = FHIR_JSON)
  @OpenApi.Params(FhirOpenApi.FhirPatientSearchParameters.class)
  @GetMapping
  public ResponseEntity<String> searchPatients(HttpServletRequest request) {
    return serializer.ok(patientService.search(request));
  }

  /** Returns a searchset Bundle of Patient {@code id} followed by its event-derived resources. */
  @OpenApi.Response(value = FhirOpenApi.FhirSearchsetBundle.class, mediaTypes = FHIR_JSON)
  @OpenApi.Params(FhirOpenApi.FhirFormatParameter.class)
  @GetMapping("/{id}/$everything")
  public ResponseEntity<String> patientEverything(
      @OpenApi.Description("The tracked entity UID") @PathVariable String id,
      HttpServletRequest request) {
    return serializer.ok(patientService.everything(id, request));
  }
}
