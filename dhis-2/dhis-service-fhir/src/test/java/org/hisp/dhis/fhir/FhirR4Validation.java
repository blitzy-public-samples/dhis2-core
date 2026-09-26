/*
 * Copyright (c) 2004-2022, University of Oslo
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

import static ca.uhn.fhir.validation.ResultSeverityEnum.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import java.util.List;
import org.hl7.fhir.common.hapi.validation.support.*;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;

/** Validates R4 resources against the base specification and parses and encodes FHIR JSON. */
public final class FhirR4Validation {
  private static final FhirContext CONTEXT = FhirContext.forR4Cached();
  private static final ValidationSupportChain SUPPORT =
      new ValidationSupportChain(
          new DefaultProfileValidationSupport(CONTEXT),
          new InMemoryTerminologyServerValidationSupport(CONTEXT),
          new CommonCodeSystemsTerminologyService(CONTEXT));
  private static final FhirValidator VALIDATOR =
      CONTEXT.newValidator().registerValidatorModule(new FhirInstanceValidator(SUPPORT));

  private FhirR4Validation() {}

  /** Fails the test, listing each message, when validation reports an ERROR or FATAL message. */
  public static void assertValid(IBaseResource resource) {
    List<String> errors =
        VALIDATOR.validateWithResult(resource).getMessages().stream()
            .filter(m -> m.getSeverity() == ERROR || m.getSeverity() == FATAL)
            .map(m -> m.getSeverity() + " " + m.getLocationString() + ": " + m.getMessage())
            .toList();
    assertEquals(List.of(), errors, "FHIR R4 validation errors in " + resource.fhirType());
  }

  /** Parses FHIR JSON with a new parser that rejects unknown elements and invalid values. */
  public static <T extends IBaseResource> T parseStrict(String json, Class<T> type) {
    var parser = CONTEXT.newJsonParser().setParserErrorHandler(new StrictErrorHandler());
    return parser.parseResource(type, json);
  }

  /** Encodes the resource as compact FHIR JSON with a new parser. */
  public static String encode(IBaseResource resource) {
    return CONTEXT.newJsonParser().encodeResourceToString(resource);
  }
}
