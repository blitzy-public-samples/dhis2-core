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

import static org.awaitility.Awaitility.await;
import static org.hisp.dhis.fhir.FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.transaction.support.TransactionSynchronizationManager.*;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.*;
import javax.sql.DataSource;
import org.hisp.dhis.deadline.*;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.test.webapi.json.domain.JsonWebMessage;
import org.hisp.dhis.webapi.filter.*;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests FHIR R4 requests on the test thread behind the production {@link
 * ConditionalOpenEntityManagerInViewFilter}, each compared with the same request sent without that
 * filter. Each request unbinds the test thread's {@code EntityManager} and binds it again
 * afterwards.
 */
class FhirRequestLifecycleTest extends FhirPostgresControllerTestBase {
  private static final String PATIENT_READ = "/api/fhir/Patient/" + FRANK;
  private static final String PATIENT_EVERYTHING = PATIENT_READ + "/$everything";
  private static final List<String> FHIR_REQUESTS =
      List.of(
          PATIENT_READ,
          "/api/fhir/Patient?family=rain",
          "/api/fhir/Observation?patient=" + FRANK,
          PATIENT_EVERYTHING);

  @MockitoSpyBean private FhirResourceSerializer serializer;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private RequestIdFilter requestIdFilter;
  @Autowired private ApiVersionFilter apiVersionFilter;
  @Autowired private DhisConfigurationProvider config;
  private ConditionalOpenEntityManagerInViewFilter openInViewFilter;
  private MockMvc withFilter;
  private MockMvc withoutFilter;

  @BeforeEach
  void setUpChains() throws Exception {
    MockFilterConfig filterConfig =
        new MockFilterConfig(webApplicationContext.getServletContext(), "openSessionInViewFilter");
    filterConfig.addInitParameter("entityManagerFactoryBeanName", "entityManagerFactory");
    openInViewFilter = new ConditionalOpenEntityManagerInViewFilter();
    openInViewFilter.init(filterConfig);
    withFilter = chain(openInViewFilter, requestIdFilter, apiVersionFilter);
    withoutFilter = chain(requestIdFilter, apiVersionFilter);
  }

  @Test
  void responsesAreIdenticalWithAndWithoutOpenEntityManagerInView() throws Exception {
    for (String path : FHIR_REQUESTS) {
      String withBody = fhirBody(perform(withFilter, path), HttpStatus.OK);
      assertEquals(fhirBody(perform(withoutFilter, path), HttpStatus.OK), withBody, path);
      assertTrue(withBody.contains(FRANK), path);
      assertTrue(path.equals(PATIENT_READ) || parse(withBody, Bundle.class).hasEntry(), path);
    }
  }

  @Test
  void serialisationRunsWithBoundEntityManagerAndNoTransaction() throws Exception {
    List<List<Boolean>> states = new ArrayList<>();
    doAnswer(
            invocation -> {
              states.add(List.of(hasResource(entityManagerFactory), isActualTransactionActive()));
              return invocation.callRealMethod();
            })
        .when(serializer)
        .ok(any());
    try {
      for (String path : FHIR_REQUESTS) {
        for (MockMvc chain : List.of(withFilter, withoutFilter)) {
          states.clear();
          fhirBody(perform(chain, path), HttpStatus.OK);
          List<Boolean> boundAndInTransaction = List.of(chain == withFilter, false);
          assertEquals(List.of(boundAndInTransaction), states, path);
        }
      }
    } finally {
      reset(serializer);
    }
  }

  @Test
  void connectionsReturnToBaselineAfterEachRequest() throws Exception {
    for (String path : FHIR_REQUESTS) {
      for (MockMvc chain : List.of(withFilter, withoutFilter)) {
        int baseline = activeConnections();
        fhirBody(perform(chain, path), HttpStatus.OK);
        assertConnectionsReturnTo(baseline, path);
      }
    }
  }

  @Test
  void expiredDeadlineAnswersPlatformTimeoutBehindOpenEntityManagerInView() throws Exception {
    int baseline = activeConnections();
    HttpResponse response;
    DeadlineHolder.set(Deadline.in(Duration.ZERO));
    try {
      response = perform(withFilter, PATIENT_EVERYTHING);
    } finally {
      DeadlineHolder.clear();
    }
    JsonWebMessage message = response.content(HttpStatus.GATEWAY_TIMEOUT).as(JsonWebMessage.class);
    MediaType contentType = MediaType.parseMediaType(response.getContentType());
    assertFalse(contentType.equalsTypeAndSubtype(FHIR_JSON_MEDIA_TYPE), contentType::toString);
    assertTrue(contentType.isCompatibleWith(MediaType.APPLICATION_JSON), contentType::toString);
    assertEquals(504, message.getHttpStatusCode());
    assertEquals("ERROR", message.getStatus());
    assertConnectionsReturnTo(baseline, PATIENT_EVERYTHING);
  }

  @Test
  void disabledRouteReturnsNotFoundBehindOpenEntityManagerInView() throws Exception {
    Filter security = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
    MockMvc disabledChain = chain(openInViewFilter, security, requestIdFilter, apiVersionFilter);
    int baseline = activeConnections();
    HttpResponse response;
    String flag = ConfigurationKey.FHIR_API_ENABLED.getKey();
    config.getProperties().setProperty(flag, "false");
    try {
      response = perform(disabledChain, PATIENT_READ);
    } finally {
      config.getProperties().setProperty(flag, "true");
    }
    assertNotFound(response);
    assertConnectionsReturnTo(baseline, PATIENT_READ);
  }

  private MockMvc chain(Filter... filters) {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(filters).build();
  }

  /** Sends {@code GET path} and asserts that the request leaves no {@code EntityManager} bound. */
  private HttpResponse perform(MockMvc chain, String path) throws Exception {
    Object testEntityManager = unbindResourceIfPossible(entityManagerFactory);
    try {
      HttpResponse response =
          new HttpResponse(
              toResponse(chain.perform(get(path).session(session)).andReturn().getResponse()));
      assertFalse(hasResource(entityManagerFactory), "EntityManager left bound after " + path);
      return response;
    } finally {
      unbindResourceIfPossible(entityManagerFactory);
      if (testEntityManager != null) {
        bindResource(entityManagerFactory, testEntityManager);
      }
    }
  }

  /** Waits, bounded, until the pool's active connection count equals {@code baseline}. */
  private void assertConnectionsReturnTo(int baseline, String description) {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertEquals(baseline, activeConnections(), description));
  }

  private int activeConnections() throws Exception {
    DataSource dataSource = webApplicationContext.getBean("actualDataSource", DataSource.class);
    Class<?> dataSourceType = Class.forName("com.zaxxer.hikari.HikariDataSource");
    Class<?> poolType = Class.forName("com.zaxxer.hikari.HikariPoolMXBean");
    Object pool =
        dataSourceType.getMethod("getHikariPoolMXBean").invoke(dataSource.unwrap(dataSourceType));
    return (int) poolType.getMethod("getActiveConnections").invoke(pool);
  }
}
