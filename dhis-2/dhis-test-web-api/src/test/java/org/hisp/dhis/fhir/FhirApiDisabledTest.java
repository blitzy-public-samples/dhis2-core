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
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.Stream;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.test.webapi.AuthenticationApiTestBase;
import org.hisp.dhis.webapi.filter.ApiVersionFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.request.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests, through the security filter chain and then the API version filter with {@code
 * fhir.api.enabled} at its default, that each of {@link #FHIR_ROUTES} answers the FHIR not-found
 * {@code 404} to anonymous, Basic and session callers: {@code GET}, {@code HEAD}, {@code OPTIONS},
 * CORS preflight and {@code POST} on unversioned and versioned paths, also on {@code /loginConfig}
 * paths that security ignores, while the sampled non-FHIR routes keep their responses.
 */
class FhirApiDisabledTest extends AuthenticationApiTestBase {
  static final String BASIC_AUTH_USER_NAME = "usera";
  static final String BASIC_AUTH_HEADER =
      "Basic " + HttpHeaders.encodeBasicAuth(BASIC_AUTH_USER_NAME, DEFAULT_ADMIN_PASSWORD, UTF_8);
  static final String X_REQUESTED_WITH = "X-Requested-With";
  static final String XML_HTTP_REQUEST = "XMLHttpRequest";
  private static final String PATIENT_ID = "dUE514NMOlo";
  private static final String PATIENT_BODY = "{\"resourceType\":\"Patient\"}";
  private static final String LOGIN_CONFIG = "/loginConfig";
  private static final List<FhirRoute> FHIR_ROUTES =
      List.of(
          new FhirRoute(HttpMethod.GET, "/api/fhir/Patient/" + PATIENT_ID, null, false),
          new FhirRoute(HttpMethod.HEAD, "/api/fhir/Patient/" + PATIENT_ID, null, false),
          new FhirRoute(HttpMethod.OPTIONS, "/api/fhir/Patient/" + PATIENT_ID, null, true),
          new FhirRoute(HttpMethod.GET, "/api/fhir/metadata", null, false),
          new FhirRoute(HttpMethod.OPTIONS, "/api/fhir/metadata", null, false),
          new FhirRoute(HttpMethod.GET, "/api/fhir", null, false),
          new FhirRoute(HttpMethod.GET, "/api/44/fhir/Patient/" + PATIENT_ID, null, false),
          new FhirRoute(HttpMethod.POST, "/api/fhir/Patient", PATIENT_BODY, false),
          new FhirRoute(HttpMethod.GET, "/api/fhir/Patient" + LOGIN_CONFIG, null, false),
          new FhirRoute(HttpMethod.GET, "/api/fhir" + LOGIN_CONFIG, null, false),
          new FhirRoute(HttpMethod.GET, "/api/44/fhir/Patient" + LOGIN_CONFIG, null, false),
          new FhirRoute(HttpMethod.POST, "/api/fhir/Patient" + LOGIN_CONFIG, PATIENT_BODY, false),
          new FhirRoute(HttpMethod.OPTIONS, "/api/fhir/Patient" + LOGIN_CONFIG, null, false));
  private static final List<String> NON_FHIR_PATHS =
      List.of("/api/fhirResourceMappings", "/api/44/fhirResourceMappings");

  @Autowired private DhisConfigurationProvider config;
  @Autowired private FilterChainProxy springSecurityFilterChain;
  @Autowired private ApiVersionFilter apiVersionFilter;

  @BeforeEach
  void createBasicAuthUser() {
    createUserWithAuth(BASIC_AUTH_USER_NAME, "ALL");
  }

  @BeforeEach
  void addApiVersionFilterAfterSecurity() {
    mvc =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .apply(SecurityMockMvcConfigurers.springSecurity(springSecurityFilterChain))
            .addFilter(apiVersionFilter)
            .build();
  }

  @Test
  void fhirApiIsDisabledByDefault() {
    assertEquals("fhir.api.enabled", ConfigurationKey.FHIR_API_ENABLED.getKey());
    assertEquals("false", ConfigurationKey.FHIR_API_ENABLED.getDefaultValue());
    assertEquals("false", config.getProperty(ConfigurationKey.FHIR_API_ENABLED));
    assertFalse(config.isEnabled(ConfigurationKey.FHIR_API_ENABLED));
  }

  @ParameterizedTest(name = "{0} as {1}")
  @MethodSource("fhirRouteCalls")
  void everyFhirRouteReturnsNotFoundWhenDisabled(FhirRoute route, Caller caller) throws Exception {
    assertNotFoundOutcome(perform(route, caller), route.method() == HttpMethod.HEAD);
  }

  @Test
  void routesOutsideFhirApiKeepTheirResponsesWhenDisabled() throws Exception {
    for (String path : NON_FHIR_PATHS) {
      clearSecurityContext();
      MockHttpServletResponse anonymous =
          mvc.perform(MockMvcRequestBuilders.get(path).header(X_REQUESTED_WITH, XML_HTTP_REQUEST))
              .andReturn()
              .getResponse();
      assertEquals(401, anonymous.getStatus(), "GET " + path + " as ANONYMOUS");
      assertNotFhirJson(anonymous, "GET " + path + " as ANONYMOUS");
    }
    MockHttpServletResponse me =
        perform(new FhirRoute(HttpMethod.GET, "/api/me", null, false), Caller.BASIC_AUTHENTICATED);
    assertEquals(200, me.getStatus(), "GET /api/me as BASIC_AUTHENTICATED");
    MockHttpServletResponse loginConfig =
        perform(
            new FhirRoute(HttpMethod.GET, "/api" + LOGIN_CONFIG, null, false), Caller.ANONYMOUS);
    assertEquals(200, loginConfig.getStatus(), "GET /api/loginConfig as ANONYMOUS");
    assertNotFhirJson(loginConfig, "GET /api/loginConfig as ANONYMOUS");
  }

  static Stream<Arguments> fhirRouteCalls() {
    return FHIR_ROUTES.stream()
        .flatMap(route -> Stream.of(Caller.values()).map(caller -> Arguments.of(route, caller)));
  }

  private MockHttpServletResponse perform(FhirRoute route, Caller caller) throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.request(route.method(), route.path());
    if (route.body() != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(route.body());
    }
    if (route.preflight()) {
      request.header(HttpHeaders.ORIGIN, "http://localhost:3000");
      request.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.GET.name());
    }
    switch (caller) {
      case ANONYMOUS -> clearSecurityContext();
      case BASIC_AUTHENTICATED -> {
        clearSecurityContext();
        request.header(HttpHeaders.AUTHORIZATION, BASIC_AUTH_HEADER);
      }
      case SESSION_AUTHENTICATED -> request.session(session);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  /** Asserts the FHIR {@code 404 not-found} outcome; a {@code HEAD} response may lack the body. */
  static void assertNotFoundOutcome(MockHttpServletResponse response, boolean head) {
    HttpResponse fhir = new HttpResponse(toResponse(response));
    if (head && response.getContentAsByteArray().length == 0) {
      FhirPostgresControllerTestBase.fhirBody(fhir, HttpStatus.NOT_FOUND);
    } else {
      FhirPostgresControllerTestBase.assertNotFound(fhir);
    }
  }

  static void assertNotFhirJson(MockHttpServletResponse response, String description) {
    String contentType = response.getContentType();
    if (contentType != null) {
      assertNotEquals(
          FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE.getSubtype(),
          MediaType.parseMediaType(contentType).getSubtype(),
          description);
    }
  }

  private enum Caller {
    ANONYMOUS,
    BASIC_AUTHENTICATED,
    SESSION_AUTHENTICATED
  }

  private record FhirRoute(HttpMethod method, String path, String body, boolean preflight) {
    @Override
    public String toString() {
      return method + " " + path + (preflight ? " (CORS preflight)" : "");
    }
  }
}
