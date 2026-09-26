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

import static org.hisp.dhis.fhir.FhirApiDisabledTest.*;
import static org.hisp.dhis.fhir.FhirResourceMappingStoreTest.FhirPostgresControllerTestBase.parseOk;
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
 * Tests, through the security filter chain and then the API version filter with {@code
 * fhir.api.enabled} on, that anonymous FHIR requests, also on paths security ignores or permits,
 * get the platform's existing unauthenticated response, and authenticated ones reach the FHIR API.
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
  private static final String NON_FHIR_PATH = "/api/me";

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
    for (boolean xmlHttpRequest : new boolean[] {true, false}) {
      MockHttpServletResponse expected = performAnonymous(NON_FHIR_PATH, xmlHttpRequest);
      assertEquals(xmlHttpRequest ? 401 : 302, expected.getStatus(), NON_FHIR_PATH);
      for (String path : FHIR_PATHS) {
        String description = "GET " + path + (xmlHttpRequest ? " as XMLHttpRequest" : "");
        MockHttpServletResponse response = performAnonymous(path, xmlHttpRequest);
        assertEquals(expected.getStatus(), response.getStatus(), description);
        assertEquals(expected.getRedirectedUrl(), response.getRedirectedUrl(), description);
        assertEquals(expected.getContentType(), response.getContentType(), description);
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

  private MockHttpServletResponse performAnonymous(String path, boolean xmlHttpRequest)
      throws Exception {
    clearSecurityContext();
    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path);
    if (xmlHttpRequest) {
      request.header(X_REQUESTED_WITH, XML_HTTP_REQUEST);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private MockHttpServletResponse performBasic(String path) throws Exception {
    clearSecurityContext();
    MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path);
    request.header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER);
    return mvc.perform(request).andReturn().getResponse();
  }
}
