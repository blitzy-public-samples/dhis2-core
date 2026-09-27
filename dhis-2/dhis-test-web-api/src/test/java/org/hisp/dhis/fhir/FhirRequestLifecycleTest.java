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
import static org.hisp.dhis.fhir.FhirResourceMappingStoreTest.FhirResponses.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.transaction.support.TransactionSynchronizationManager.*;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.Filter;
import java.io.*;
import java.net.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.hisp.dhis.deadline.*;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.fhir.FhirResourceMappingStoreTest.FhirPostgresControllerTestBase;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.test.config.PostgresDhisConfigurationProvider;
import org.hisp.dhis.test.webapi.json.domain.JsonWebMessage;
import org.hisp.dhis.tracker.export.timeout.TrackerExportTimeout;
import org.hisp.dhis.webapi.filter.*;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.ThrowingConsumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.security.core.context.*;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Tests FHIR requests with and without the open-EntityManager-in-view filter on a small pool whose
 * connections pass through a {@link FreezableProxy} to the database.
 */
@ContextConfiguration(classes = FhirRequestLifecycleTest.SmallPoolConfig.class)
class FhirRequestLifecycleTest extends FhirPostgresControllerTestBase {
  private static final int POOL_SIZE = 10;
  private static final String JDBC = "jdbc:";
  private static final String PATIENT_READ = "/api/fhir/Patient/" + FRANK;
  private static final String EVERYTHING = PATIENT_READ + "/$everything";
  private static final String OBSERVATION_SEARCH = "/api/fhir/Observation?patient=" + FRANK;
  private static final List<String> FHIR_REQUESTS =
      List.of(PATIENT_READ, "/api/fhir/Patient?family=rain", OBSERVATION_SEARCH, EVERYTHING);

  @MockitoSpyBean private FhirResourceSerializer serializer;
  @MockitoSpyBean private TrackerExportTimeout trackerExportTimeout;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private RequestIdFilter requestIdFilter;
  @Autowired private ApiVersionFilter apiVersionFilter;
  @Autowired private DhisConfigurationProvider config;
  @Autowired private FreezableProxy databaseProxy;
  private ConditionalOpenEntityManagerInViewFilter openInViewFilter;
  private MockMvc withFilter;
  private MockMvc withoutFilter;

  public static class SmallPoolConfig {
    @Bean
    public FreezableProxy databaseProxy() throws IOException {
      return new FreezableProxy(databaseUri(new PostgresDhisConfigurationProvider(null)));
    }

    @Bean
    public DhisConfigurationProvider dhisConfigurationProvider(FreezableProxy databaseProxy) {
      PostgresDhisConfigurationProvider provider = new PostgresDhisConfigurationProvider(null);
      String url = provider.getProperty(ConfigurationKey.CONNECTION_URL);
      String proxied = "//127.0.0.1:" + databaseProxy.port() + "/";
      Properties override = new Properties();
      override.put(ConfigurationKey.FHIR_API_ENABLED.getKey(), "true");
      override.put(ConfigurationKey.CONNECTION_POOL_MAX_SIZE.getKey(), String.valueOf(POOL_SIZE));
      override.put(ConfigurationKey.CONNECTION_POOL_TIMEOUT.getKey(), "20000");
      override.put(
          ConfigurationKey.CONNECTION_URL.getKey(),
          url.replace("//" + databaseUri(provider).getRawAuthority() + "/", proxied));
      provider.addProperties(override);
      return provider;
    }

    private static URI databaseUri(DhisConfigurationProvider provider) {
      String url = provider.getProperty(ConfigurationKey.CONNECTION_URL);
      return URI.create(url.substring(JDBC.length()));
    }
  }

  /**
   * Forwards every accepted TCP connection to one upstream address on daemon threads. While frozen
   * it still accepts and connects new sockets but forwards no byte in either direction.
   */
  static final class FreezableProxy implements AutoCloseable {
    private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    private final ExecutorService threads = Executors.newCachedThreadPool(FreezableProxy::daemon);
    private final URI upstream;
    private boolean frozen;

    FreezableProxy(URI upstream) throws IOException {
      this.upstream = upstream;
      threads.submit(this::accept);
    }

    int port() {
      return server.getLocalPort();
    }

    synchronized void setFrozen(boolean frozen) {
      this.frozen = frozen;
      notifyAll();
    }

    @Override
    public void close() throws IOException {
      threads.shutdownNow();
      server.close();
    }

    private Void accept() throws IOException {
      while (!server.isClosed()) {
        Socket client = server.accept();
        threads.submit(() -> connect(client));
      }
      return null;
    }

    private Void connect(Socket client) throws IOException {
      Socket target;
      try {
        target = new Socket(upstream.getHost(), upstream.getPort());
      } catch (IOException e) {
        client.close();
        throw e;
      }
      threads.submit(() -> pump(client, target));
      threads.submit(() -> pump(target, client));
      return null;
    }

    private Void pump(Socket from, Socket to) throws IOException, InterruptedException {
      try (from;
          to) {
        InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
          awaitThaw();
          out.write(buffer, 0, read);
          out.flush();
        }
      }
      return null;
    }

    private synchronized void awaitThaw() throws InterruptedException {
      while (frozen) wait();
    }

    private static Thread daemon(Runnable runnable) {
      Thread thread = new Thread(runnable, "fhir-database-proxy");
      thread.setDaemon(true);
      return thread;
    }
  }

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
          assertEquals(List.of(List.of(chain == withFilter, false)), states, path);
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
    List<String> paths = new ArrayList<>(FHIR_REQUESTS);
    paths.addAll(FHIR_REQUESTS);
    int baseline = activeConnections();
    assertEquals(POOL_SIZE, hikari("getMaximumPoolSize"));
    List<Connection> held = new ArrayList<>();
    ExecutorService executor = Executors.newFixedThreadPool(paths.size());
    try {
      await().atMost(Duration.ofSeconds(10)).until(() -> holdAllButOne(held));
      CountDownLatch ready = new CountDownLatch(paths.size());
      CountDownLatch release = new CountDownLatch(1);
      List<Future<HttpResponse>> responses = new ArrayList<>();
      for (String path : paths)
        responses.add(
            submit(
                executor,
                () -> {
                  ready.countDown();
                  assertTrue(release.await(30, TimeUnit.SECONDS), "requests released");
                  return perform(withFilter, path);
                }));
      assertTrue(ready.await(30, TimeUnit.SECONDS), "every request thread is ready");
      release.countDown();
      for (int i = 0; i < paths.size(); i++) {
        HttpResponse response = responses.get(i).get(2, TimeUnit.MINUTES);
        assertTrue(fhirBody(response, HttpStatus.OK).contains(FRANK), paths.get(i));
      }
    } finally {
      executor.shutdownNow();
      for (Connection connection : held) connection.close();
      assertTrue(executor.awaitTermination(1, TimeUnit.MINUTES), "request threads end");
    }
    assertConnectionsReturnTo(baseline, "concurrent requests");
  }

  @Test
  void expiredDeadlineAnswersPlatformTimeoutBehindOpenEntityManagerInView() throws Exception {
    int baseline = activeConnections();
    DeadlineHolder.set(Deadline.in(Duration.ZERO));
    try {
      platformTimeout(perform(withFilter, EVERYTHING), EVERYTHING);
    } finally {
      DeadlineHolder.clear();
    }
    assertConnectionsReturnTo(baseline, EVERYTHING);
  }

  @Test
  void disabledRouteReturnsNotFoundBehindOpenEntityManagerInView() throws Exception {
    Filter security = webApplicationContext.getBean("springSecurityFilterChain", Filter.class);
    MockMvc disabledChain = chain(openInViewFilter, security, requestIdFilter, apiVersionFilter);
    int baseline = activeConnections();
    String flag = ConfigurationKey.FHIR_API_ENABLED.getKey();
    config.getProperties().setProperty(flag, "false");
    try {
      assertNotFound(perform(disabledChain, PATIENT_READ));
    } finally {
      config.getProperties().setProperty(flag, "true");
    }
    assertConnectionsReturnTo(baseline, PATIENT_READ);
  }

  @Test
  void unresponsiveDatabaseAnswersPlatformTimeoutInBoundedTime() throws Throwable {
    int baseline = activeConnections();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      doAnswer(i -> Deadline.in(Duration.ofSeconds(1))).when(trackerExportTimeout).newDeadline();
      withMappingTableLocked(
          statement -> {
            for (String path : List.of(PATIENT_READ, OBSERVATION_SEARCH, EVERYTHING)) {
              JsonWebMessage message = platformTimeout(perform(withFilter, path), path);
              assertEquals("Request exceeded its time budget of 1s", message.getMessage(), path);
            }
          });
      assertConnectionsReturnTo(baseline, "mapping resolution timed out");
      doAnswer(i -> Deadline.in(Duration.ofSeconds(2))).when(trackerExportTimeout).newDeadline();
      withMappingTableLocked(
          statement -> {
            var response = submit(executor, () -> perform(withFilter, PATIENT_READ));
            await().atMost(Duration.ofSeconds(10)).until(() -> waitsOnMappingLock(statement));
            databaseProxy.setFrozen(true);
            try {
              JsonWebMessage message =
                  platformTimeout(response.get(20, TimeUnit.SECONDS), "unresponsive database");
              assertEquals("Request exceeded its time budget of 2s", message.getMessage());
            } finally {
              databaseProxy.setFrozen(false);
            }
          });
    } finally {
      reset(trackerExportTimeout);
      executor.shutdown();
    }
    assertTrue(executor.awaitTermination(1, TimeUnit.MINUTES), "request thread ends");
    assertConnectionsReturnTo(baseline, "unresponsive database");
    assertTrue(fhirBody(perform(withFilter, PATIENT_READ), HttpStatus.OK).contains(FRANK));
  }

  private void withMappingTableLocked(ThrowingConsumer<Statement> test) throws Throwable {
    try (Connection lock = dataSource().getConnection()) {
      lock.setAutoCommit(false);
      try (Statement statement = lock.createStatement()) {
        statement.execute("set local lock_timeout = '10s'");
        statement.execute("lock table fhirresourcemapping in access exclusive mode");
        test.accept(statement);
      } finally {
        lock.rollback();
      }
    }
  }

  private Future<HttpResponse> submit(ExecutorService executor, Callable<HttpResponse> call) {
    SecurityContext context = SecurityContextHolder.getContext();
    return executor.submit(
        () -> {
          SecurityContextHolder.setContext(context);
          try {
            return call.call();
          } finally {
            SecurityContextHolder.clearContext();
          }
        });
  }

  private static boolean waitsOnMappingLock(Statement statement) throws SQLException {
    String waiting =
        "select count(*) from pg_locks"
            + " where relation = 'fhirresourcemapping'::regclass and not granted";
    try (ResultSet rows = statement.executeQuery(waiting)) {
      return rows.next() && rows.getInt(1) > 0;
    }
  }

  private static JsonWebMessage platformTimeout(HttpResponse response, String path) {
    JsonWebMessage message = response.content(HttpStatus.GATEWAY_TIMEOUT).as(JsonWebMessage.class);
    MediaType contentType = MediaType.parseMediaType(response.getContentType());
    assertTrue(contentType.isCompatibleWith(MediaType.APPLICATION_JSON), path);
    assertEquals(504, message.getHttpStatusCode(), path);
    assertEquals("ERROR", message.getStatus(), path);
    return message;
  }

  private boolean holdAllButOne(List<Connection> held) throws Exception {
    while (activeConnections() < POOL_SIZE - 1) held.add(dataSource().getConnection());
    return activeConnections() == POOL_SIZE - 1;
  }

  private MockMvc chain(Filter... filters) {
    return MockMvcBuilders.webAppContextSetup(webApplicationContext).addFilters(filters).build();
  }

  private HttpResponse perform(MockMvc chain, String path) throws Exception {
    Object testEntityManager = unbindResourceIfPossible(entityManagerFactory);
    try {
      var result = chain.perform(get(path).session(session)).andReturn().getResponse();
      HttpResponse response = new HttpResponse(toResponse(result));
      assertFalse(hasResource(entityManagerFactory), "EntityManager left bound after " + path);
      return response;
    } finally {
      unbindResourceIfPossible(entityManagerFactory);
      if (testEntityManager != null) bindResource(entityManagerFactory, testEntityManager);
    }
  }

  private void assertConnectionsReturnTo(int baseline, String description) {
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertEquals(baseline, activeConnections(), description));
  }

  private int activeConnections() throws Exception {
    return (int) hikari("getHikariPoolMXBean", "getActiveConnections");
  }

  private Object hikari(String... getters) throws Exception {
    Object target = dataSource().unwrap(Class.forName("com.zaxxer.hikari.HikariDataSource"));
    for (String getter : getters) target = target.getClass().getMethod(getter).invoke(target);
    return target;
  }

  private DataSource dataSource() {
    return webApplicationContext.getBean("actualDataSource", DataSource.class);
  }
}
