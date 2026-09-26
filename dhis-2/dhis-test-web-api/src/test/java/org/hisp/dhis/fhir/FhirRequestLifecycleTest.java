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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.sql.DataSource;
import org.hisp.dhis.deadline.Deadline;
import org.hisp.dhis.deadline.DeadlineHolder;
import org.hisp.dhis.external.conf.ConfigurationKey;
import org.hisp.dhis.external.conf.DhisConfigurationProvider;
import org.hisp.dhis.fhir.mapping.FhirResourceMapping;
import org.hisp.dhis.jsontree.JsonMixed;
import org.hisp.dhis.test.config.PostgresDhisConfigurationProvider;
import org.hisp.dhis.test.webapi.PostgresControllerIntegrationTestBase;
import org.hisp.dhis.test.webapi.json.domain.JsonWebMessage;
import org.hisp.dhis.user.User;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.hisp.dhis.webapi.filter.ApiVersionFilter;
import org.hisp.dhis.webapi.filter.ConditionalOpenEntityManagerInViewFilter;
import org.hisp.dhis.webapi.filter.RequestIdFilter;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.OperationOutcome.OperationOutcomeIssueComponent;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tests FHIR R4 requests behind the production {@link ConditionalOpenEntityManagerInViewFilter} on
 * PostgreSQL, comparing each request with the same request sent without that filter.
 *
 * <ul>
 *   <li>Response bodies are identical with and without the filter.
 *   <li>Serialisation runs with the {@code EntityManager} bound only behind the filter, and never
 *       inside a transaction.
 *   <li>The pool's active connection count returns to its value before every request.
 *   <li>An expired deadline answers the platform {@code 504} behind the filter.
 *   <li>With {@code fhir.api.enabled} off, the security chain behind the filter answers {@code 404}
 *       with a not-found {@code OperationOutcome}.
 * </ul>
 *
 * <p>Every request runs on the test thread with the test's own thread-bound {@code EntityManager}
 * unbound for the duration of the request, so the persistence context of the request is the one the
 * chain and the services open and close themselves.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ContextConfiguration(classes = FhirRequestLifecycleTest.FhirApiEnabledConfig.class)
class FhirRequestLifecycleTest extends PostgresControllerIntegrationTestBase {

  private static final String IMPORT_USER_UID = "tTgjgobT1oS";

  private static final String MAPPINGS_FILE = "fhir/fhir_resource_mappings.json";

  private static final String PATIENT_UID = "dUE514NMOlo";

  private static final String PATIENT_READ = "/api/fhir/Patient/" + PATIENT_UID;

  private static final String PATIENT_EVERYTHING = PATIENT_READ + "/$everything";

  private static final List<String> FHIR_REQUESTS =
      List.of(
          PATIENT_READ,
          "/api/fhir/Patient?family=rain",
          "/api/fhir/Observation?patient=" + PATIENT_UID,
          PATIENT_EVERYTHING);

  private static final Duration CONNECTION_RELEASE_TIMEOUT = Duration.ofSeconds(10);

  @MockitoSpyBean private FhirResourceSerializer serializer;

  @Autowired private EntityManagerFactory entityManagerFactory;

  @Autowired private RequestIdFilter requestIdFilter;

  @Autowired private ApiVersionFilter apiVersionFilter;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private FilterChainProxy springSecurityFilterChain;

  @Autowired
  @Qualifier("actualDataSource")
  private DataSource dataSource;

  @Autowired private DhisConfigurationProvider config;

  @Autowired private TestSetup testSetup;

  private User importUser;

  private ConditionalOpenEntityManagerInViewFilter openEntityManagerInViewFilter;

  private MockMvc withFilter;

  private MockMvc withoutFilter;

  /** Provides the PostgreSQL test configuration with {@code fhir.api.enabled=true}. */
  public static class FhirApiEnabledConfig {
    @Bean
    public DhisConfigurationProvider dhisConfigurationProvider() {
      Properties override = new Properties();
      override.put(ConfigurationKey.FHIR_API_ENABLED.getKey(), "true");

      PostgresDhisConfigurationProvider provider = new PostgresDhisConfigurationProvider(null);
      provider.addProperties(override);
      return provider;
    }
  }

  @BeforeAll
  void importFixtures() throws IOException {
    deleteAllMappings();
    testSetup.importMetadata();

    importUser = userService.getUser(IMPORT_USER_UID);
    injectSecurityContextUser(importUser);
    testSetup.importTrackerData();

    manager.flush();
    manager.clear();
    testSetup.importMetadata(MAPPINGS_FILE);
    manager.flush();
    manager.clear();
  }

  @AfterAll
  void deleteFixtureMappings() {
    injectSecurityContextUser(importUser);
    deleteAllMappings();
  }

  @BeforeEach
  void setUpChains() throws ServletException {
    switchContextToUser(importUser);

    MockFilterConfig filterConfig =
        new MockFilterConfig(webApplicationContext.getServletContext(), "openSessionInViewFilter");
    filterConfig.addInitParameter("entityManagerFactoryBeanName", "entityManagerFactory");
    openEntityManagerInViewFilter = new ConditionalOpenEntityManagerInViewFilter();
    openEntityManagerInViewFilter.init(filterConfig);

    withFilter =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(openEntityManagerInViewFilter, requestIdFilter, apiVersionFilter)
            .build();
    withoutFilter =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(requestIdFilter, apiVersionFilter)
            .build();
  }

  @Test
  void responsesAreIdenticalWithAndWithoutOpenEntityManagerInView() throws Exception {
    for (String path : FHIR_REQUESTS) {
      MockHttpServletResponse with = perform(withFilter, get(path));
      MockHttpServletResponse without = perform(withoutFilter, get(path));

      String withBody = assertFhirOk(with, path + " with filter");
      String withoutBody = assertFhirOk(without, path + " without filter");
      assertEquals(withoutBody, withBody, path);
      assertExpectedContent(path, withBody);
    }
  }

  @Test
  void serialisationRunsWithBoundEntityManagerAndNoTransaction() throws Exception {
    List<SerialisationState> states = new ArrayList<>();
    doAnswer(
            invocation -> {
              states.add(
                  new SerialisationState(
                      TransactionSynchronizationManager.hasResource(entityManagerFactory),
                      TransactionSynchronizationManager.isActualTransactionActive()));
              return invocation.callRealMethod();
            })
        .when(serializer)
        .ok(any(IBaseResource.class));
    try {
      for (String path : FHIR_REQUESTS) {
        assertEquals(
            new SerialisationState(true, false),
            serialisationStateOf(withFilter, path, states),
            path + " with filter");
        assertEquals(
            new SerialisationState(false, false),
            serialisationStateOf(withoutFilter, path, states),
            path + " without filter");
      }
    } finally {
      Mockito.reset(serializer);
    }
  }

  @Test
  void connectionsReturnToBaselineAfterEachRequest() throws Exception {
    for (String path : FHIR_REQUESTS) {
      int baselineWith = activeConnections();
      assertFhirOk(perform(withFilter, get(path)), path + " with filter");
      assertConnectionsReturnTo(baselineWith, path + " with filter");

      int baselineWithout = activeConnections();
      assertFhirOk(perform(withoutFilter, get(path)), path + " without filter");
      assertConnectionsReturnTo(baselineWithout, path + " without filter");
    }
  }

  @Test
  void expiredDeadlineAnswersPlatformTimeoutBehindOpenEntityManagerInView() throws Exception {
    int baseline = activeConnections();

    MockHttpServletResponse response;
    DeadlineHolder.set(Deadline.in(Duration.ZERO));
    try {
      response = perform(withFilter, get(PATIENT_EVERYTHING));
    } finally {
      DeadlineHolder.clear();
    }

    assertEquals(504, response.getStatus(), PATIENT_EVERYTHING);
    assertNotNull(response.getContentType(), PATIENT_EVERYTHING);
    MediaType contentType = MediaType.parseMediaType(response.getContentType());
    assertFalse(
        contentType.equalsTypeAndSubtype(FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE),
        () -> "Content type " + contentType);
    assertTrue(contentType.isCompatibleWith(MediaType.APPLICATION_JSON), contentType::toString);
    JsonWebMessage message =
        JsonMixed.of(response.getContentAsString(StandardCharsets.UTF_8)).as(JsonWebMessage.class);
    assertEquals(504, message.getHttpStatusCode());
    assertEquals("ERROR", message.getStatus());
    assertConnectionsReturnTo(baseline, PATIENT_EVERYTHING + " with expired deadline");
  }

  @Test
  void disabledRouteReturnsNotFoundBehindOpenEntityManagerInView() throws Exception {
    MockMvc disabledChain =
        MockMvcBuilders.webAppContextSetup(webApplicationContext)
            .addFilters(
                openEntityManagerInViewFilter,
                springSecurityFilterChain,
                requestIdFilter,
                apiVersionFilter)
            .build();
    int baseline = activeConnections();

    MockHttpServletResponse response;
    String flag = ConfigurationKey.FHIR_API_ENABLED.getKey();
    config.getProperties().setProperty(flag, "false");
    try {
      response = perform(disabledChain, get(PATIENT_READ));
    } finally {
      config.getProperties().setProperty(flag, "true");
    }

    assertEquals(404, response.getStatus(), PATIENT_READ);
    assertFhirContentType(response, PATIENT_READ);
    OperationOutcome outcome =
        parse(response.getContentAsString(StandardCharsets.UTF_8), OperationOutcome.class);
    assertEquals(1, outcome.getIssue().size());
    OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
    assertEquals(OperationOutcome.IssueSeverity.ERROR, issue.getSeverity());
    assertEquals(OperationOutcome.IssueType.NOTFOUND, issue.getCode());
    assertEquals(FhirApiException.notFound().getDiagnostics(), issue.getDiagnostics());
    assertConnectionsReturnTo(baseline, PATIENT_READ + " while disabled");
  }

  /**
   * Sends the request as the import user on the test thread through the chain. The test's own
   * thread-bound {@code EntityManager} is unbound while the request runs and bound again
   * afterwards; the request itself must leave no {@code EntityManager} bound.
   */
  private MockHttpServletResponse perform(MockMvc chain, MockHttpServletRequestBuilder request)
      throws Exception {
    Object testEntityManager =
        TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory);
    try {
      MockHttpServletResponse response =
          chain.perform(request.session(session)).andReturn().getResponse();
      assertFalse(
          TransactionSynchronizationManager.hasResource(entityManagerFactory),
          "The request left an EntityManager bound to the thread");
      return response;
    } finally {
      TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory);
      if (testEntityManager != null) {
        TransactionSynchronizationManager.bindResource(entityManagerFactory, testEntityManager);
      }
    }
  }

  /**
   * Sends {@code GET path} through the chain and returns the state recorded by the single
   * serialisation of its response.
   */
  private SerialisationState serialisationStateOf(
      MockMvc chain, String path, List<SerialisationState> states) throws Exception {
    states.clear();
    assertFhirOk(perform(chain, get(path)), path);
    assertEquals(1, states.size(), () -> "Serialisations of " + path);
    return states.get(0);
  }

  /** Asserts a {@code 200} FHIR JSON response and returns its body. */
  private static String assertFhirOk(MockHttpServletResponse response, String description)
      throws IOException {
    String body = response.getContentAsString(StandardCharsets.UTF_8);
    assertEquals(200, response.getStatus(), () -> description + ": " + body);
    assertFhirContentType(response, description);
    return body;
  }

  private static void assertFhirContentType(MockHttpServletResponse response, String description) {
    assertNotNull(response.getContentType(), description);
    assertEquals(
        FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE,
        MediaType.parseMediaType(response.getContentType()),
        description);
  }

  /**
   * Asserts that the body holds the fixture data the request selects: the Patient for the read, a
   * non-empty {@code searchset} Bundle for the searches and a Bundle starting with the Patient for
   * {@code $everything}.
   */
  private static void assertExpectedContent(String path, String body) {
    if (PATIENT_READ.equals(path)) {
      assertEquals(PATIENT_UID, parse(body, Patient.class).getIdPart(), path);
      return;
    }
    Bundle bundle = parse(body, Bundle.class);
    assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType(), path);
    assertFalse(bundle.getEntry().isEmpty(), path);
    if (PATIENT_EVERYTHING.equals(path)) {
      Resource first = bundle.getEntryFirstRep().getResource();
      assertEquals(ResourceType.Patient, first.getResourceType(), path);
      assertEquals(PATIENT_UID, first.getIdElement().getIdPart(), path);
      assertTrue(bundle.getEntry().size() > 1, path);
    }
  }

  /** Parses the FHIR JSON strictly: unknown elements and invalid values fail the parse. */
  private static <T extends IBaseResource> T parse(String body, Class<T> type) {
    return FhirContext.forR4Cached()
        .newJsonParser()
        .setParserErrorHandler(new StrictErrorHandler())
        .parseResource(type, body);
  }

  /** Waits, bounded, until the pool's active connection count equals {@code baseline}. */
  private void assertConnectionsReturnTo(int baseline, String description) {
    await()
        .atMost(CONNECTION_RELEASE_TIMEOUT)
        .pollInterval(Duration.ofMillis(50))
        .untilAsserted(
            () ->
                assertEquals(baseline, activeConnections(), "Active connections: " + description));
  }

  /** Returns the number of connections the Hikari pool behind the data source has handed out. */
  private int activeConnections() throws ReflectiveOperationException, SQLException {
    Class<?> dataSourceType = Class.forName("com.zaxxer.hikari.HikariDataSource");
    Class<?> poolType = Class.forName("com.zaxxer.hikari.HikariPoolMXBean");
    Object pool =
        dataSourceType.getMethod("getHikariPoolMXBean").invoke(dataSource.unwrap(dataSourceType));
    return (int) poolType.getMethod("getActiveConnections").invoke(pool);
  }

  private void deleteAllMappings() {
    doInTransaction(() -> manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete));
  }

  /** Whether an {@code EntityManager} was bound and a transaction active during serialisation. */
  private record SerialisationState(boolean entityManagerBound, boolean transactionActive) {}
}
