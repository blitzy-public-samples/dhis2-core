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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.test.webapi.AuthenticationApiTestBase;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Tests the FHIR R4 API with {@code fhir.api.enabled} at its default value, through the real Spring
 * Security filter chain: every route under {@code /api/fhir/**} and {@code /api/{version}/fhir/**}
 * answers {@code 404} with a not-found {@code OperationOutcome} for anonymous and authenticated
 * callers and for every HTTP method, while routes outside the FHIR API keep their existing
 * responses.
 */
class FhirApiDisabledTest extends AuthenticationApiTestBase {

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

  private static final String PATIENT_ID = "dUE514NMOlo";

  private static final List<FhirRoute> FHIR_ROUTES =
      List.of(
          new FhirRoute(HttpMethod.GET, "/api/fhir/Patient/" + PATIENT_ID, null),
          new FhirRoute(HttpMethod.GET, "/api/fhir/metadata", null),
          new FhirRoute(HttpMethod.GET, "/api/fhir", null),
          new FhirRoute(HttpMethod.GET, "/api/44/fhir/Patient/" + PATIENT_ID, null),
          new FhirRoute(HttpMethod.POST, "/api/fhir/Patient", "{\"resourceType\":\"Patient\"}"));

  private static final List<String> NON_FHIR_PATHS =
      List.of("/api/fhirResourceMappings", "/api/44/fhirResourceMappings");

  @Autowired private DhisConfigurationProvider config;

  @BeforeEach
  void createBasicAuthUser() {
    createUserWithAuth(BASIC_AUTH_USER_NAME, "ALL");
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
    MockHttpServletResponse response = perform(route, caller);

    assertNotFoundOutcome(response, route + " as " + caller);
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
        perform(new FhirRoute(HttpMethod.GET, "/api/me", null), Caller.BASIC_AUTHENTICATED);

    assertEquals(200, me.getStatus(), "GET /api/me as BASIC_AUTHENTICATED");
  }

  /** Returns every FHIR route combined with every caller. */
  static Stream<Arguments> fhirRouteCalls() {
    return FHIR_ROUTES.stream()
        .flatMap(
            route -> Arrays.stream(Caller.values()).map(caller -> Arguments.of(route, caller)));
  }

  /**
   * Sends the request of the route as the caller: without session and credentials, with the {@code
   * Authorization} header of {@code usera}, or with the admin session.
   */
  private MockHttpServletResponse perform(FhirRoute route, Caller caller) throws Exception {
    MockHttpServletRequestBuilder request =
        MockMvcRequestBuilders.request(route.method(), route.path());
    if (route.body() != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(route.body());
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

  /**
   * Asserts that the response is {@code 404} with the FHIR JSON content type and a body that parses
   * strictly as an {@code OperationOutcome} holding exactly the not-found issue.
   */
  private static void assertNotFoundOutcome(MockHttpServletResponse response, String description)
      throws Exception {
    assertEquals(404, response.getStatus(), description);
    assertNotNull(response.getContentType(), description);
    assertEquals(
        FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE,
        MediaType.parseMediaType(response.getContentType()),
        description);

    IParser parser =
        FhirContext.forR4Cached().newJsonParser().setParserErrorHandler(new StrictErrorHandler());
    OperationOutcome outcome =
        parser.parseResource(
            OperationOutcome.class, response.getContentAsString(StandardCharsets.UTF_8));

    assertEquals(1, outcome.getIssue().size(), description);
    OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
    assertEquals(OperationOutcome.IssueSeverity.ERROR, issue.getSeverity(), description);
    assertEquals(OperationOutcome.IssueType.NOTFOUND, issue.getCode(), description);
    assertEquals(FhirApiException.notFound().getDiagnostics(), issue.getDiagnostics(), description);
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

  /** The caller of a request: anonymous, authenticated by basic credentials, or by session. */
  private enum Caller {
    ANONYMOUS,
    BASIC_AUTHENTICATED,
    SESSION_AUTHENTICATED
  }

  /** A request to the API: its HTTP method, path and optional JSON body. */
  private record FhirRoute(HttpMethod method, String path, String body) {
    @Override
    public String toString() {
      return method + " " + path;
    }
  }
}
