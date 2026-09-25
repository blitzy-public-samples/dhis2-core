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

import static org.junit.jupiter.api.Assertions.fail;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationResult;
import java.util.List;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;

/**
 * Validates HAPI R4 resources against the FHIR R4 base specification, and parses and encodes FHIR
 * JSON with the shared R4 context.
 */
public final class FhirR4Validation {

  private static final FhirContext CONTEXT = FhirContext.forR4Cached();

  private static final FhirValidator VALIDATOR = createValidator();

  private FhirR4Validation() {}

  /**
   * Validates the resource and fails the calling test when the validator reports an ERROR or FATAL
   * message. The failure message names the resource type on its first line, then lists one line per
   * such message as {@code severity location: message}. WARNING and INFORMATION messages are
   * accepted.
   */
  public static void assertValid(IBaseResource resource) {
    ValidationResult result = VALIDATOR.validateWithResult(resource);
    List<String> errors =
        result.getMessages().stream()
            .filter(FhirR4Validation::isErrorOrFatal)
            .map(FhirR4Validation::describe)
            .toList();
    if (!errors.isEmpty()) {
      fail(
          "FHIR R4 validation of "
              + resource.fhirType()
              + " reported errors:\n"
              + String.join("\n", errors));
    }
  }

  /**
   * Parses FHIR JSON into the given resource type with a new parser that rejects unknown elements
   * and invalid values.
   */
  public static <T extends IBaseResource> T parseStrict(String json, Class<T> type) {
    return CONTEXT
        .newJsonParser()
        .setParserErrorHandler(new StrictErrorHandler())
        .parseResource(type, json);
  }

  /** Encodes the resource as compact FHIR JSON with a new parser. */
  public static String encode(IBaseResource resource) {
    return CONTEXT.newJsonParser().encodeResourceToString(resource);
  }

  private static boolean isErrorOrFatal(SingleValidationMessage message) {
    ResultSeverityEnum severity = message.getSeverity();
    return severity == ResultSeverityEnum.ERROR || severity == ResultSeverityEnum.FATAL;
  }

  private static String describe(SingleValidationMessage message) {
    return message.getSeverity() + " " + message.getLocationString() + ": " + message.getMessage();
  }

  private static FhirValidator createValidator() {
    ValidationSupportChain chain =
        new ValidationSupportChain(
            new DefaultProfileValidationSupport(CONTEXT),
            new InMemoryTerminologyServerValidationSupport(CONTEXT),
            new CommonCodeSystemsTerminologyService(CONTEXT));
    FhirValidator validator = CONTEXT.newValidator();
    validator.registerValidatorModule(new FhirInstanceValidator(chain));
    return validator;
  }
}
