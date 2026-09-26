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
package org.hisp.dhis.fhir.config;

import static org.hisp.dhis.external.conf.ConfigurationKey.FHIR_API_ENABLED;
import static org.hisp.dhis.fhir.FhirResourceSerializer.FHIR_JSON_CONTENT_TYPE;
import static org.hisp.dhis.fhir.config.FhirApiDisabledSecurityConfig.FHIR_PATH;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.fhir.*;
import org.hisp.dhis.user.*;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.mock.web.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.*;

class FhirApiDisabledSecurityConfigTest {
  private static final List<String> FHIR_PATHS =
      List.of("/api/fhir", "/api/fhir/", "/api/fhir/Patient/x", "/api/44/fhir/Patient/x");
  private static final List<String> NON_FHIR_PATHS =
      List.of("/api/fhirX", "/api/fhirResourceMappings", "/api/tracker/x", "/fhir/Patient");
  private static final List<String> ENCODED_FHIR_PATHS =
      List.of("/api/%66hir/Patient/x", "/api/fhir;v=1/Patient/x");
  private final DhisConfigurationProvider config = mock(DhisConfigurationProvider.class);
  private final FhirResourceSerializer serializer = new FhirResourceSerializer();
  private final String notFound = serializer.error(FhirApiException.notFound()).getBody();
  private SecurityFilterChain chain;
  private MappedInterceptor guard;

  @BeforeEach
  void setUp() {
    FhirApiDisabledSecurityConfig configuration = new FhirApiDisabledSecurityConfig();
    chain = configuration.fhirApiDisabledFilterChain(config, serializer);
    guard = configuration.fhirApiRequestGuard(config, serializer);
  }

  @AfterEach
  void clearSecurityContext() {
    CurrentUserUtil.clearSecurityContext();
  }

  @Test
  void defaultConfigurationValueIsFalse() {
    assertEquals("fhir.api.enabled", FHIR_API_ENABLED.getKey());
    assertEquals("false", FHIR_API_ENABLED.getDefaultValue());
    assertFalse(FHIR_API_ENABLED.isConfidential());
    assertEquals(Optional.of(FHIR_API_ENABLED), ConfigurationKey.getByKey("fhir.api.enabled"));
    assertFalse(DhisConfigurationProvider.isOn(FHIR_API_ENABLED.getDefaultValue()));
  }

  @Test
  void fhirPathMatchesVersionedAndUnversionedFhirPaths() {
    assertEach(FHIR_PATHS, path -> FHIR_PATH.matcher(path).matches(), true);
  }

  @Test
  void fhirPathRejectsNonFhirPaths() {
    assertEach(NON_FHIR_PATHS, path -> FHIR_PATH.matcher(path).matches(), false);
    assertEach(ENCODED_FHIR_PATHS, path -> FHIR_PATH.matcher(path).matches(), false);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void chainMatchesFhirPathsOnlyWhileDisabled(boolean enabled) {
    when(config.isEnabled(FHIR_API_ENABLED)).thenReturn(enabled);
    for (String context : List.of("", "/dhis")) {
      assertEach(FHIR_PATHS, path -> chain.matches(request("GET", context, path)), !enabled);
      assertEach(
          ENCODED_FHIR_PATHS, path -> chain.matches(request("GET", context, path)), !enabled);
      assertEach(NON_FHIR_PATHS, path -> chain.matches(request("GET", context, path)), false);
    }
    assertEquals(!enabled, chain.matches(new MockHttpServletRequest("GET", "/api/fhir/%zz")));
    assertFalse(chain.matches(request("GET", "", "/api/%66hirResourceMappings")));
    assertFalse(chain.matches(new MockHttpServletRequest("GET", "/api/%zzhir/Patient")));
  }

  @Test
  void flagIsCheckedBeforeThePath() {
    when(config.isEnabled(FHIR_API_ENABLED)).thenReturn(true);
    HttpServletRequest request = mock(HttpServletRequest.class);
    assertFalse(chain.matches(request));
    verifyNoInteractions(request);
  }

  @Test
  void flagIsReadPerRequest() {
    MockHttpServletRequest request = request("GET", "", "/api/fhir/Patient/x");
    assertTrue(chain.matches(request));
    when(config.isEnabled(FHIR_API_ENABLED)).thenReturn(true);
    assertFalse(chain.matches(request));
    when(config.isEnabled(FHIR_API_ENABLED)).thenReturn(false);
    assertTrue(chain.matches(request));
  }

  @Test
  void disabledFilterAnswersNotFoundOperationOutcomeWithoutContinuingChain() throws Exception {
    assertEquals(1, chain.getFilters().size());
    for (String method : List.of("GET", "HEAD", "OPTIONS", "POST", "PUT", "PATCH", "DELETE")) {
      for (String path : List.of("/api/fhir/Patient", "/api/44/fhir/metadata", "/api/fhir")) {
        assertTrue(chain.matches(request(method, "", path)));
        assertNotFound(notFound, filter(request(method, "", path)));
      }
    }
    MockHttpServletRequest preflight = request("OPTIONS", "", "/api/fhir/Patient/x");
    preflight.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");
    preflight.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
    assertTrue(chain.matches(preflight));
    assertNotFound(notFound, filter(preflight));
    OperationOutcome outcome = FhirR4Validation.parseStrict(notFound, OperationOutcome.class);
    assertEquals(OperationOutcome.IssueSeverity.ERROR, outcome.getIssueFirstRep().getSeverity());
    assertEquals(OperationOutcome.IssueType.NOTFOUND, outcome.getIssueFirstRep().getCode());
    FhirR4Validation.assertValid(outcome);
  }

  @Test
  void beanIsDeclaredAtHighestPrecedence() throws NoSuchMethodException {
    Class<?>[] parameters = {DhisConfigurationProvider.class, FhirResourceSerializer.class};
    Class<FhirApiDisabledSecurityConfig> type = FhirApiDisabledSecurityConfig.class;
    Method chainFactory = type.getDeclaredMethod("fhirApiDisabledFilterChain", parameters);
    Method guardFactory = type.getDeclaredMethod("fhirApiRequestGuard", parameters);
    assertTrue(type.isAnnotationPresent(Configuration.class));
    assertTrue(chainFactory.isAnnotationPresent(Bean.class));
    assertEquals(Ordered.HIGHEST_PRECEDENCE, chainFactory.getAnnotation(Order.class).value());
    assertTrue(guardFactory.isAnnotationPresent(Bean.class));
    assertEquals(MappedInterceptor.class, guardFactory.getReturnType());
  }

  @Test
  void guardAnswersNotFoundUnlessEnabledWithAuthenticatedUser() throws Exception {
    for (String path : List.of("/api/fhir", "/api/fhir/Patient/x/$everything", "/api/fhirX")) {
      MockHttpServletRequest request = request("GET", "", path);
      ServletRequestPathUtils.parseAndCache(request);
      assertEquals(!path.equals("/api/fhirX"), guard.matches(request), path);
    }
    CurrentUserUtil.injectUserInSecurityContext(mock(UserDetails.class));
    for (boolean enabled : new boolean[] {false, true}) {
      when(config.isEnabled(FHIR_API_ENABLED)).thenReturn(enabled);
      if (enabled) {
        CurrentUserUtil.clearSecurityContext();
      }
      for (String method : List.of("GET", "HEAD", "OPTIONS", "POST")) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(guard.preHandle(request(method, "", "/api/fhir/x"), response, this));
        assertNotFound(notFound, response);
      }
    }
    CurrentUserUtil.injectUserInSecurityContext(mock(UserDetails.class));
    MockHttpServletResponse response = new MockHttpServletResponse();
    assertTrue(guard.preHandle(request("GET", "", "/api/fhir/Patient/x"), response, this));
    assertFalse(response.isCommitted());
  }

  private MockHttpServletResponse filter(MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain filterChain = new MockFilterChain();
    chain.getFilters().get(0).doFilter(request, response, filterChain);
    assertNull(filterChain.getRequest(), request.getMethod() + " " + request.getRequestURI());
    return response;
  }

  private static void assertNotFound(String body, MockHttpServletResponse actual) throws Exception {
    assertEquals(404, actual.getStatus());
    assertEquals(FHIR_JSON_CONTENT_TYPE, actual.getContentType());
    assertTrue(actual.isCommitted());
    assertEquals(body, actual.getContentAsString(StandardCharsets.UTF_8));
  }

  private static void assertEach(List<String> paths, Predicate<String> matches, boolean expected) {
    assertAll(paths.stream().map(path -> () -> assertEquals(expected, matches.test(path), path)));
  }

  private static MockHttpServletRequest request(String method, String contextPath, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, contextPath + path);
    request.setContextPath(contextPath);
    request.setServletPath(UriUtils.decode(path.replaceAll(";[^/]*", ""), StandardCharsets.UTF_8));
    return request;
  }
}
