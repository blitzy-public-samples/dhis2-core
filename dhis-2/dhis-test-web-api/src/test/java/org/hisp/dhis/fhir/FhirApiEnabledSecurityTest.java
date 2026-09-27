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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hisp.dhis.fhir.FhirApiDisabledTest.*;
import static org.hisp.dhis.fhir.FhirResourceMappingStoreTest.FhirResponses.parseOk;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.test.config.H2DhisConfigurationProvider;
import org.hisp.dhis.test.webapi.AuthenticationApiTestBase;
import org.hisp.dhis.webapi.filter.ApiVersionFilter;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Enumerations;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.request.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests with {@code fhir.api.enabled} on, through the security and API version filters, that FHIR
 * requests without a user get the response of {@code /api/me}: {@code 401} JSON for {@code
 * XMLHttpRequest}, also on paths security ignores or permits, and for unknown Basic credentials
 * elsewhere, else {@code 302} to {@code /login/}; and that authenticated ones reach the FHIR API.
 */
@ContextConfiguration(classes = FhirApiEnabledSecurityTest.FhirApiEnabledConfig.class)
class FhirApiEnabledSecurityTest extends AuthenticationApiTestBase {
  /** Supplies the H2 test configuration with {@code fhir.api.enabled} set to {@code true}. */
  public static class FhirApiEnabledConfig {
    @Bean
    public DhisConfigurationProvider dhisConfigurationProvider() {
      H2DhisConfigurationProvider provider = new H2DhisConfigurationProvider();
      provider.getProperties().put(ConfigurationKey.FHIR_API_ENABLED.getKey(), "true");
      return provider;
    }
  }

  private static final String METADATA_PATH = "/api/fhir/metadata";
  private static final String VERSIONED_METADATA_PATH = "/api/44/fhir/metadata";
  private static final List<String> FHIR_PATHS =
      List.of(
          METADATA_PATH,
          "/api/fhir/Patient/dUE514NMOlo",
          VERSIONED_METADATA_PATH,
          "/api/fhir/Patient/loginConfig",
          "/api/44/fhir/Patient/loginConfig",
          "/api/fhir/Patient/account");

  @Autowired private DhisConfigurationProvider config;
  @Autowired private FilterChainProxy springSecurityFilterChain;
  @Autowired private ApiVersionFilter apiVersionFilter;

  @BeforeEach
  void assertFhirApiIsEnabledAndAddApiVersionFilterAfterSecurity() {
    assertTrue(config.isEnabled(ConfigurationKey.FHIR_API_ENABLED));
    mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity(springSecurityFilterChain))
            .addFilter(apiVersionFilter)
            .build();
  }

  @Test
  void anonymousRequestGetsExistingUnauthorizedResponse() throws Exception {
    String unknownUser = HttpHeaders.encodeBasicAuth("unknownuser", DEFAULT_ADMIN_PASSWORD, UTF_8);
    Map<String, String> failedBasic = Map.of(HttpHeaders.AUTHORIZATION, "Basic " + unknownUser);
    Map<String, String> xmlHttpRequest = Map.of(X_REQUESTED_WITH, XML_HTTP_REQUEST);
    List<String> authenticatedPaths =
        FHIR_PATHS.stream().filter(path -> !path.endsWith("/loginConfig")).toList();
    for (Map<String, String> headers :
        List.<Map<String, String>>of(xmlHttpRequest, Map.of(), failedBasic)) {
      boolean json = !headers.isEmpty();
      MockHttpServletResponse expected = perform("/api/me", headers);
      for (String path : headers.equals(failedBasic) ? authenticatedPaths : FHIR_PATHS) {
        String description = "GET " + path + " with " + headers.keySet();
        MockHttpServletResponse response = perform(path, headers);
        assertEquals(json ? 401 : 302, response.getStatus(), description);
        assertEquals(json ? null : "/login/", response.getRedirectedUrl(), description);
        assertEquals(json ? "application/json" : null, response.getContentType(), description);
        assertEquals(expected.getStatus(), response.getStatus(), description);
        assertEquals(expected.getRedirectedUrl(), response.getRedirectedUrl(), description);
        assertEquals(expected.getContentType(), response.getContentType(), description);
        assertArrayEquals(
            expected.getContentAsByteArray(), response.getContentAsByteArray(), description);
        assertNotFhirJson(response, description);
      }
    }
  }

  @Test
  void authenticatedRequestReachesFhirApi() throws Exception {
    createUserWithAuth(BASIC_AUTH_USER_NAME, "ALL");
    for (String path : List.of(METADATA_PATH, VERSIONED_METADATA_PATH)) {
      CapabilityStatement statement =
          parseOk(new HttpResponse(toResponse(performBasic(path))), CapabilityStatement.class);
      assertEquals(Enumerations.FHIRVersion._4_0_1, statement.getFhirVersion(), path);
    }
  }

  private MockHttpServletResponse perform(String path, Map<String, String> headers)
      throws Exception {
    clearSecurityContext();
    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path);
    headers.forEach(request::header);
    return mvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse performBasic(String path) throws Exception {
    return perform(path, Map.of(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER));
  }
}
