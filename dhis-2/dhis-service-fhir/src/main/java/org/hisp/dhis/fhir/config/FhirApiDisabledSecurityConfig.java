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

import static org.hisp.dhis.webapi.filter.CspFilter.*;

import jakarta.servlet.Filter;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.regex.Pattern;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.fhir.*;
import org.hisp.dhis.user.CurrentUserUtil;
import org.hisp.dhis.webapi.security.Http401LoginUrlAuthenticationEntryPoint;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.*;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.*;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.UrlPathHelper;

/**
 * Answers FHIR requests with 404, the platform security headers and a same-origin frame policy
 * while {@code fhir.api.enabled} is off, before authentication. At the handler, answers 404 while
 * off, and with the platform authentication entry point's response while on without a user.
 */
@Configuration
public class FhirApiDisabledSecurityConfig {
  static final Pattern FHIR_PATH = Pattern.compile("^/api/(?:\\d+/)?fhir(?:/.*)?$");

  /** Creates the highest-precedence chain matching {@link #FHIR_PATH}, as sent or URL-decoded. */
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public SecurityFilterChain fhirApiDisabledFilterChain(
      DhisConfigurationProvider config, FhirResourceSerializer serializer) {
    RequestMatcher matcher =
        request -> !config.isEnabled(ConfigurationKey.FHIR_API_ENABLED) && isFhirPath(request);
    StaticHeadersWriter frame =
        config.isEnabled(ConfigurationKey.CSP_ENABLED)
            ? new StaticHeadersWriter(
                CONTENT_SECURITY_POLICY_HEADER_NAME, FRAME_ANCESTORS_DEFAULT_CSP + ";")
            : new StaticHeadersWriter("X-Frame-Options", "SAMEORIGIN");
    HeaderWriterFilter headers =
        new HeaderWriterFilter(
            List.of(
                frame,
                new XContentTypeOptionsHeaderWriter(),
                new XXssProtectionHeaderWriter(),
                new HstsHeaderWriter()));
    Filter notFound =
        (request, response, chain) ->
            serializer.writeError((HttpServletResponse) response, FhirApiException.notFound());
    return new DefaultSecurityFilterChain(matcher, headers, notFound);
  }

  /** Guards FHIR handlers: 404 while off, the entry point's response while on without a user. */
  @Bean
  public MappedInterceptor fhirApiRequestGuard(
      DhisConfigurationProvider config,
      FhirResourceSerializer serializer,
      Http401LoginUrlAuthenticationEntryPoint entryPoint) {
    HandlerInterceptor guard =
        new HandlerInterceptor() {
          @Override
          public boolean preHandle(
              HttpServletRequest request, HttpServletResponse response, Object handler)
              throws Exception {
            if (!config.isEnabled(ConfigurationKey.FHIR_API_ENABLED)) {
              serializer.writeError(response, FhirApiException.notFound());
              return false;
            }
            if (CurrentUserUtil.hasCurrentUser()) {
              return true;
            }
            entryPoint.commence(
                request,
                response,
                new InsufficientAuthenticationException(
                    "Full authentication is required to access this resource"));
            return false;
          }
        };
    return new MappedInterceptor(new String[] {"/api/fhir", "/api/fhir/**"}, null, guard);
  }

  private static boolean isFhirPath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri == null) {
      return false;
    }
    String context = Objects.toString(request.getContextPath(), "");
    if (uri.startsWith(context) && FHIR_PATH.matcher(uri.substring(context.length())).matches()) {
      return true;
    }
    try {
      return FHIR_PATH
          .matcher(UrlPathHelper.defaultInstance.getPathWithinApplication(request))
          .matches();
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }
}
