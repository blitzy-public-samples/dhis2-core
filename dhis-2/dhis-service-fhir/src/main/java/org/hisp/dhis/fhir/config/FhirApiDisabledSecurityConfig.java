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
package org.hisp.dhis.fhir.config;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.FhirResourceSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.UrlPathHelper;

/**
 * Security configuration of the FHIR R4 API while it is disabled: every request under {@code
 * /api/fhir/**} or {@code /api/{version}/fhir/**}, with any HTTP method and from any caller, is
 * answered with {@code 404} and a not-found {@code OperationOutcome} before authentication, as long
 * as {@link ConfigurationKey#FHIR_API_ENABLED} is off. While the flag is on, the chain matches no
 * request.
 */
@Configuration
public class FhirApiDisabledSecurityConfig {

  /** The FHIR R4 API paths within the application: {@code /api/fhir} and everything below it. */
  static final Pattern FHIR_PATH = Pattern.compile("^/api/(?:\\d+/)?fhir(?:/.*)?$");

  /**
   * Creates the highest-precedence security filter chain that answers FHIR R4 API requests with
   * {@code 404} and a not-found {@code OperationOutcome} while {@link
   * ConfigurationKey#FHIR_API_ENABLED} is off.
   *
   * <p>The chain reads the flag on every request, then matches the request when its path within the
   * application, as sent or URL-decoded, matches {@link #FHIR_PATH}. Its single filter writes the
   * error response and never continues the filter chain.
   *
   * @param config the DHIS2 configuration holding {@code fhir.api.enabled}
   * @param serializer the serializer writing the {@code OperationOutcome} response
   * @return the security filter chain of the disabled FHIR R4 API
   */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain fhirApiDisabledFilterChain(
      DhisConfigurationProvider config, FhirResourceSerializer serializer) {
    RequestMatcher matcher =
        request -> !config.isEnabled(ConfigurationKey.FHIR_API_ENABLED) && isFhirPath(request);
    Filter filter =
        (request, response, chain) ->
            serializer.writeError((HttpServletResponse) response, FhirApiException.notFound());
    return new DefaultSecurityFilterChain(matcher, filter);
  }

  /**
   * Tells whether the path of the request within the application, either as sent or URL-decoded
   * with {@code ;} content removed, matches {@link #FHIR_PATH}.
   */
  private static boolean isFhirPath(HttpServletRequest request) {
    String requestUri = request.getRequestURI();
    if (requestUri == null) {
      return false;
    }
    String rawPath = rawPathWithinApplication(requestUri, request.getContextPath());
    if (rawPath != null && FHIR_PATH.matcher(rawPath).matches()) {
      return true;
    }
    String decodedPath = decodedPathWithinApplication(request);
    return decodedPath != null && FHIR_PATH.matcher(decodedPath).matches();
  }

  /**
   * Returns the request URI without the context path, or {@code null} when the URI does not start
   * with the context path.
   */
  private static String rawPathWithinApplication(String requestUri, String contextPath) {
    if (contextPath == null || contextPath.isEmpty()) {
      return requestUri;
    }
    return requestUri.startsWith(contextPath) ? requestUri.substring(contextPath.length()) : null;
  }

  /**
   * Returns the URL-decoded path within the application with {@code ;} content removed, or {@code
   * null} when the request URI holds an invalid percent-encoding.
   */
  private static String decodedPathWithinApplication(HttpServletRequest request) {
    try {
      return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
