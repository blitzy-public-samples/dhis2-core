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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.fhir.service.FhirCapabilityStatementService;
import org.hisp.dhis.test.config.H2DhisConfigurationProvider;
import org.hisp.dhis.test.webapi.AuthenticationApiTestBase;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementKind;
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode;
import org.hl7.fhir.r4.model.Enumerations;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Tests the FHIR R4 API with {@code fhir.api.enabled} on, through the real Spring Security filter
 * chain: FHIR requests are not answered by the disabled-API chain but by the main security chain,
 * which gives anonymous callers the same platform response as every other API path ({@code 401} for
 * {@code XMLHttpRequest} callers, a redirect to the login page otherwise) and lets authenticated
 * callers reach the FHIR controllers.
 */
@ContextConfiguration(classes = FhirApiEnabledSecurityTest.FhirApiEnabledConfig.class)
class FhirApiEnabledSecurityTest extends AuthenticationApiTestBase {

  /** Supplies the H2 test configuration with {@code fhir.api.enabled} set to {@code true}. */
  public static class FhirApiEnabledConfig {
    @Bean
    public DhisConfigurationProvider dhisConfigurationProvider() {
      Properties properties = new Properties();
      properties.put(ConfigurationKey.FHIR_API_ENABLED.getKey(), "true");

      H2DhisConfigurationProvider provider = new H2DhisConfigurationProvider();
      provider.addProperties(properties);
      return provider;
    }
  }

  private static final String BASIC_AUTH_USER_NAME = "usera";

  /** {@code Authorization} header value holding the basic credentials of {@code usera}. */
  private static final String BASIC_AUTH_HEADER =
      "Basic "
          + Base64.getEncoder()
              .encodeToString(
                  (BASIC_AUTH_USER_NAME + ":" + DEFAULT_ADMIN_PASSWORD)
                      .getBytes(StandardCharsets.UTF_8));

  private static final String X_REQUESTED_WITH = "X-Requested-With";

  private static final String XML_HTTP_REQUEST = "XMLHttpRequest";

  private static final String METADATA_PATH = "/api/fhir/metadata";

  private static final List<String> FHIR_PATHS =
      List.of(METADATA_PATH, "/api/fhir/Patient/dUE514NMOlo", "/api/44/fhir/metadata");

  /** A non-FHIR API path whose anonymous response every FHIR path must reproduce. */
  private static final String NON_FHIR_PATH = "/api/me";

  @Autowired private DhisConfigurationProvider config;

  @BeforeEach
  void fhirApiIsEnabled() {
    assertTrue(config.isEnabled(ConfigurationKey.FHIR_API_ENABLED));
  }

  @Test
  void anonymousRequestGetsExistingUnauthorizedResponse() throws Exception {
    for (boolean xmlHttpRequest : new boolean[] {true, false}) {
      MockHttpServletResponse expected = performAnonymous(NON_FHIR_PATH, xmlHttpRequest);

      for (String path : FHIR_PATHS) {
        String description = "GET " + path + (xmlHttpRequest ? " as XMLHttpRequest" : "");
        MockHttpServletResponse response = performAnonymous(path, xmlHttpRequest);

        if (xmlHttpRequest) {
          assertEquals(401, response.getStatus(), description);
        }
        assertEquals(expected.getStatus(), response.getStatus(), description);
        assertEquals(
            expected.getHeader(HttpHeaders.LOCATION),
            response.getHeader(HttpHeaders.LOCATION),
            description);
        assertEquals(expected.getContentType(), response.getContentType(), description);
        assertNotFhirJson(response, description);
      }
    }
  }

  @Test
  void authenticatedRequestReachesFhirApi() throws Exception {
    createUserWithAuth(BASIC_AUTH_USER_NAME, "ALL");
    clearSecurityContext();

    MockHttpServletResponse response =
        mvc.perform(
                MockMvcRequestBuilders.get(METADATA_PATH)
                    .header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER))
            .andReturn()
            .getResponse();

    assertEquals(200, response.getStatus());
    String contentType = response.getContentType();
    assertNotNull(contentType);
    assertTrue(
        contentType.startsWith(FhirResourceSerializer.FHIR_JSON_CONTENT_TYPE),
        "Content-Type " + contentType);
    assertEquals(
        FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE, MediaType.parseMediaType(contentType));

    IParser parser =
        FhirContext.forR4Cached().newJsonParser().setParserErrorHandler(new StrictErrorHandler());
    CapabilityStatement statement =
        parser.parseResource(
            CapabilityStatement.class, response.getContentAsString(StandardCharsets.UTF_8));

    assertEquals(Enumerations.FHIRVersion._4_0_1, statement.getFhirVersion());
    assertEquals("4.0.1", statement.getFhirVersion().toCode());
    assertEquals(CapabilityStatementKind.INSTANCE, statement.getKind());
    assertEquals(
        FhirCapabilityStatementService.IMPLEMENTATION_DESCRIPTION,
        statement.getImplementation().getDescription());
    assertEquals(1, statement.getRest().size());
    assertEquals(RestfulCapabilityMode.SERVER, statement.getRestFirstRep().getMode());
  }

  /**
   * Sends {@code GET path} without session and credentials, with the {@code X-Requested-With:
   * XMLHttpRequest} header when {@code xmlHttpRequest} is set.
   */
  private MockHttpServletResponse performAnonymous(String path, boolean xmlHttpRequest)
      throws Exception {
    clearSecurityContext();
    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path);
    if (xmlHttpRequest) {
      request.header(X_REQUESTED_WITH, XML_HTTP_REQUEST);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  /** Asserts that the response does not carry the FHIR JSON content type. */
  private static void assertNotFhirJson(MockHttpServletResponse response, String description) {
    String contentType = response.getContentType();
    if (contentType != null) {
      assertNotEquals(
          FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE.getSubtype(),
          MediaType.parseMediaType(contentType).getSubtype(),
          description);
    }
  }
}
