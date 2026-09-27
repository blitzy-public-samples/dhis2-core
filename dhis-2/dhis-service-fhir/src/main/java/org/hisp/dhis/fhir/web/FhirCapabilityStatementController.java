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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.OpenApi;
import org.hisp.dhis.fhir.FhirResourceSerializer;
import org.hisp.dhis.fhir.service.FhirCapabilityStatementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Serves the FHIR R4 CapabilityStatement at {@code /api/fhir/metadata}. */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:data"})
@RestController
@RequestMapping("/api/fhir")
@RequiredArgsConstructor
public class FhirCapabilityStatementController {
  private final FhirCapabilityStatementService capabilityStatementService;
  private final FhirResourceSerializer serializer;

  @OpenApi.Response(value = ObjectNode.class, mediaTypes = FhirOpenApi.FHIR_JSON)
  @OpenApi.Params(FhirOpenApi.FhirFormatParameter.class)
  @OpenApi.Description(
      "A FHIR R4 `CapabilityStatement` resource, see"
          + " https://hl7.org/fhir/R4/capabilitystatement.html.")
  @GetMapping("/metadata")
  public ResponseEntity<String> readCapabilityStatement(HttpServletRequest request) {
    return serializer.ok(capabilityStatementService.capabilities(request));
  }

  static final class FhirOpenApi {
    static final String FHIR_JSON = "application/fhir+json";

    private FhirOpenApi() {}

    interface FhirFormatParameter {
      @JsonProperty("_format")
      @OpenApi.Description("`json`, `application/json` or `application/fhir+json`.")
      String getFormat();
    }

    interface FhirPagingParameters extends FhirFormatParameter {
      @JsonProperty("_id")
      @OpenApi.Description("Comma-separated logical ids.")
      String getId();

      @JsonProperty("_count")
      @OpenApi.Description(
          "Page size, a positive integer; default `50`. For `Patient`, at most the"
              + " `KeyTrackedEntityMaxLimit` system setting when that is positive.")
      Integer getCount();

      @JsonProperty("_page")
      @OpenApi.Description("Page number, a positive integer; default `1`.")
      Integer getPage();
    }

    interface FhirPatientSearchParameters extends FhirPagingParameters {
      @OpenApi.Description(
          "`[system|]value`; `system` may be omitted when one identifier is mapped.")
      String getIdentifier();

      @OpenApi.Description("Case-insensitive starts-with on the mapped family name.")
      String getFamily();

      @OpenApi.Description("Case-insensitive starts-with on the mapped given name.")
      String getGiven();

      @OpenApi.Description("`[eq|ge|le|gt|lt]yyyy-MM-dd` on the mapped birth date.")
      String getBirthdate();

      @OpenApi.Description("Comma-separated `male|female|other|unknown` on the mapped gender.")
      String getGender();
    }

    interface FhirImmunizationSearchParameters extends FhirPagingParameters {
      @OpenApi.Description("`{uid}` or `Patient/{uid}`: the tracked entity of the resources.")
      String getPatient();
    }

    interface FhirEncounterSearchParameters extends FhirImmunizationSearchParameters {
      @OpenApi.Description("Alias of `patient`; not combinable with it.")
      String getSubject();
    }

    interface FhirObservationSearchParameters extends FhirEncounterSearchParameters {
      @OpenApi.Description("Comma-separated `[system|]code`.")
      String getCode();
    }
  }
}
