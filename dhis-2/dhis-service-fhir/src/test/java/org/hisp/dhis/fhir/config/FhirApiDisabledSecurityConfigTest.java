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
import static org.hisp.dhis.fhir.config.FhirApiDisabledSecurityConfig.FHIR_PATH;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.FhirResourceSerializer;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.util.UriUtils;

/** Tests the security filter chain that answers FHIR R4 API requests while the API is disabled. */
@ExtendWith(MockitoExtension.class)
class FhirApiDisabledSecurityConfigTest {

  /** Paths within the application that belong to the FHIR R4 API. */
  private static final List<String> FHIR_PATHS =
      List.of(
          "/api/fhir",
          "/api/fhir/",
          "/api/fhir/Patient/x",
          "/api/fhir/metadata",
          "/api/44/fhir/Patient/x",
          "/api/28/fhir");

  /** Paths within the application outside the FHIR R4 API. */
  private static final List<String> NON_FHIR_PATHS =
      List.of(
          "/api/fhirX",
          "/api/fhirResourceMappings",
          "/api/fhirResourceMappings/settings",
          "/api/tracker/trackedEntities",
          "/api/44/tracker/x",
          "/fhir/Patient");

  /** FHIR R4 API paths as sent with percent-encoding or {@code ;} path parameters. */
  private static final List<String> ENCODED_FHIR_PATHS =
      List.of(
          "/api/%66hir/Patient/x",
          "/api/44/%66hir/metadata", "/api/fhir/Patient/a%20b", "/api/fhir;v=1/Patient/x");

  /** FHIR R4 API paths sent below the context path {@code /dhis}. */
  private static final List<String> CONTEXT_FHIR_PATHS =
      List.of("/api/fhir/Patient/x", "/api/44/fhir/metadata", "/api/%66hir/Patient/x");

  @Mock private DhisConfigurationProvider config;

  private SecurityFilterChain chain;

  @BeforeEach
  void setUp() {
    lenient().when(config.isEnabled(FHIR_API_ENABLED)).thenReturn(false);
    chain =
        new FhirApiDisabledSecurityConfig()
            .fhirApiDisabledFilterChain(config, new FhirResourceSerializer());
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
  }

  @Test
  void chainMatchesFhirPathsWhenDisabled() {
    assertEach(FHIR_PATHS, path -> chain.matches(request("GET", "", path)), true);
    assertEach(CONTEXT_FHIR_PATHS, path -> chain.matches(request("GET", "/dhis", path)), true);
    assertFalse(FHIR_PATH.matcher("/api/%66hir/Patient/x").matches());
    assertEach(ENCODED_FHIR_PATHS, path -> chain.matches(request("GET", "", path)), true);
    assertTrue(chain.matches(new MockHttpServletRequest("GET", "/api/fhir/%zz")));

    assertEach(NON_FHIR_PATHS, path -> chain.matches(request("GET", "", path)), false);
    assertFalse(chain.matches(request("GET", "/dhis", "/api/tracker/trackedEntities")));
    assertFalse(chain.matches(request("GET", "", "/api/%66hirResourceMappings")));
    assertFalse(chain.matches(new MockHttpServletRequest("GET", "/api/%zzhir/Patient")));
  }

  @Test
  void chainNeverMatchesWhenEnabled() {
    when(config.isEnabled(FHIR_API_ENABLED)).thenReturn(true);

    assertEach(FHIR_PATHS, path -> chain.matches(request("GET", "", path)), false);
    assertEach(CONTEXT_FHIR_PATHS, path -> chain.matches(request("GET", "/dhis", path)), false);
    assertEach(ENCODED_FHIR_PATHS, path -> chain.matches(request("GET", "", path)), false);
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
    List<Filter> filters = chain.getFilters();
    assertEquals(1, filters.size());
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain filterChain = new MockFilterChain();

    filters.get(0).doFilter(request("POST", "", "/api/fhir/Patient"), response, filterChain);

    assertEquals(404, response.getStatus());
    assertEquals(
        MediaType.parseMediaType(FhirResourceSerializer.FHIR_JSON_CONTENT_TYPE),
        MediaType.parseMediaType(response.getContentType()));
    assertTrue(response.isCommitted());
    OperationOutcome outcome =
        FhirR4Validation.parseStrict(
            response.getContentAsString(StandardCharsets.UTF_8), OperationOutcome.class);
    assertEquals(1, outcome.getIssue().size());
    OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
    assertEquals(IssueSeverity.ERROR, issue.getSeverity());
    assertEquals(IssueType.NOTFOUND, issue.getCode());
    assertEquals(FhirApiException.notFound().getDiagnostics(), issue.getDiagnostics());
    assertFalse(issue.getDiagnostics().isBlank());
    FhirR4Validation.assertValid(outcome);
    assertNull(filterChain.getRequest(), "flag-off filter does not continue the filter chain");
  }

  @Test
  void everyMethodAndPathGetsIdenticalNotFoundBody() throws Exception {
    Filter filter = chain.getFilters().get(0);
    String firstBody = null;
    for (String method : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
      for (String path : List.of("/api/fhir/Patient/x", "/api/44/fhir/metadata", "/api/fhir")) {
        String call = method + " " + path;
        MockHttpServletRequest request = request(method, "", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();
        assertTrue(chain.matches(request), call);

        filter.doFilter(request, response, filterChain);

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        firstBody = firstBody == null ? body : firstBody;
        assertEquals(404, response.getStatus(), call);
        assertEquals(firstBody, body, call);
        assertNull(filterChain.getRequest(), call);
      }
    }
  }

  @Test
  void beanIsDeclaredAtHighestPrecedence() throws NoSuchMethodException {
    Method factory =
        FhirApiDisabledSecurityConfig.class.getDeclaredMethod(
            "fhirApiDisabledFilterChain",
            DhisConfigurationProvider.class,
            FhirResourceSerializer.class);

    assertTrue(FhirApiDisabledSecurityConfig.class.isAnnotationPresent(Configuration.class));
    assertTrue(factory.isAnnotationPresent(Bean.class));
    assertEquals(SecurityFilterChain.class, factory.getReturnType());
    Order order = factory.getAnnotation(Order.class);
    assertNotNull(order);
    assertEquals(Ordered.HIGHEST_PRECEDENCE, order.value());
  }

  /** Asserts that the predicate yields {@code expected} for every value, naming failing values. */
  private static void assertEach(
      List<String> values, Predicate<String> predicate, boolean expected) {
    assertAll(
        values.stream()
            .map(value -> (Executable) () -> assertEquals(expected, predicate.test(value), value)));
  }

  /**
   * Creates a request to a servlet mapped at {@code /}: the request URI is the context path
   * followed by the path as sent, and the servlet path is that path without {@code ;} parameters,
   * URL-decoded.
   */
  private static MockHttpServletRequest request(String method, String contextPath, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, contextPath + path);
    request.setContextPath(contextPath);
    request.setServletPath(UriUtils.decode(path.replaceAll(";[^/]*", ""), StandardCharsets.UTF_8));
    request.setPathInfo(null);
    return request;
  }
}
