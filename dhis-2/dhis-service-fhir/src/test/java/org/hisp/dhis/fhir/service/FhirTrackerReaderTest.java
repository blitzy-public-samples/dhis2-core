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
package org.hisp.dhis.fhir.service;

import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.hisp.dhis.fhir.service.FhirTrackerReader.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE;

import jakarta.persistence.PersistenceException;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.SocketTimeoutException;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.*;
import org.hisp.dhis.common.*;
import org.hisp.dhis.deadline.*;
import org.hisp.dhis.dxf2.webmessage.*;
import org.hisp.dhis.feedback.*;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.tracker.export.timeout.TrackerExportTimeout;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.*;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.*;
import org.hisp.dhis.webapi.controller.tracker.view.*;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.web.context.request.*;

@ExtendWith(MockitoExtension.class)
class FhirTrackerReaderTest {
  private static final String FAMILY_TEA = "fhirFamily1";
  private static final String GIVEN_TEA = "fhirGiven01";
  private static final String GENDER_TEA = "fhirGender1";
  private static final String SURNAME = "SensitiveSurname";
  private static final String MISSING = "Program is specified but does not exist: " + PROGRAM;
  @Mock private FhirTrackedEntityExportAdapter trackedEntityAdapter;
  @Mock private FhirEnrollmentExportAdapter enrollmentAdapter;
  @Mock private TrackerExportTimeout timeout;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private DataSource dataSource;
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final List<Deadline> seen = new ArrayList<>();
  private long nanos = TimeUnit.SECONDS.toNanos(1_000);
  private FhirTrackerReader reader;
  private FhirTrackerReader transactional;
  private FhirSearchOrigin origin;

  @BeforeEach
  void setUp() {
    reader = new FhirTrackerReader(trackedEntityAdapter, enrollmentAdapter, timeout);
    transactional =
        new FhirTrackerReader(
            trackedEntityAdapter, enrollmentAdapter, timeout, transactionManager, dataSource);
    Map<String, String> attributeToParameter = new LinkedHashMap<>();
    attributeToParameter.put(FAMILY_TEA, "family");
    attributeToParameter.put(GIVEN_TEA, "given");
    attributeToParameter.put("fhirIdent01", "identifier");
    attributeToParameter.put(GENDER_TEA, "gender");
    List<String> configured = List.of("identifier", "family", "given");
    origin = new FhirSearchOrigin(attributeToParameter, List.of("family", "given"), configured);
  }

  @AfterEach
  void clearDeadline() {
    DeadlineHolder.clear();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void onlyExportAdaptersAreInvoked() throws Exception {
    TrackedEntityRequestParams teParams = new TrackedEntityRequestParams();
    TrackedEntity trackedEntity = trackedEntity(TE, TE_TYPE, UPDATED);
    when(trackedEntityAdapter.find(eq(teParams), exported())).thenReturn(page(trackedEntity));
    var found = reader.findTrackedEntities(teParams, request, FhirSearchOrigin.empty());
    assertEquals(List.of(trackedEntity), found.getItems());
    verify(trackedEntityAdapter).find(same(teParams), exported());
    EnrollmentRequestParams enrollmentParams = new EnrollmentRequestParams();
    Enrollment enrollment = enrollment(uid(), TE, PROGRAM);
    when(enrollmentAdapter.find(eq(enrollmentParams), exported())).thenReturn(page(enrollment));
    EnrollmentResult result = reader.findEnrollments(enrollmentParams, request, ENCOUNTER);
    assertEquals(new EnrollmentResult(List.of(enrollment), false), result);
    verify(enrollmentAdapter).find(same(enrollmentParams), exported());
    verifyNoMoreInteractions(trackedEntityAdapter, enrollmentAdapter, timeout);
    var notFound = teError(new NotFoundException("TrackedEntity not found"), origin);
    assertEquals(HttpStatus.NOT_FOUND, notFound.getStatus());
    assertEquals(IssueType.NOTFOUND, notFound.getIssueType());
    DeadlineExceededException expired = new DeadlineExceededException(Duration.ofSeconds(5));
    for (Exception exception : List.of(expired, new IllegalArgumentException("x"))) {
      doThrow(exception).when(trackedEntityAdapter).find(eq(teParams), exported());
      assertRethrown(exception, () -> reader.findTrackedEntities(teParams, request, origin));
    }
    doThrow(expired).when(enrollmentAdapter).find(eq(enrollmentParams), exported());
    assertRethrown(expired, () -> reader.findEnrollments(enrollmentParams, request, ENCOUNTER));
    Map<String, String> forwarding = new LinkedHashMap<>();
    forwarding.put("X-Forwarded-Host", "evil\"example");
    forwarding.put("X-Forwarded-Proto", "https");
    forwarding.put("X-Forwarded-Port", "443");
    forwarding.put("X-Forwarded-For", "192.0.2.1");
    forwarding.put("Forwarded", "host=evil.example");
    forwarding.forEach(request::addHeader);
    request.addHeader("Accept", "application/fhir+json");
    request.setQueryString("_id=" + PROGRAM);
    List<HttpServletRequest> exported = new ArrayList<>();
    when(trackedEntityAdapter.find(any(), any()))
        .thenAnswer(i -> exported.add(i.getArgument(1)) ? page() : null);
    when(enrollmentAdapter.find(any(), any()))
        .thenAnswer(i -> exported.add(i.getArgument(1)) ? page() : null);
    reader.findTrackedEntities(new TrackedEntityRequestParams(), request, origin);
    reader.findEnrollments(new EnrollmentRequestParams(), request, ENCOUNTER);
    assertEquals(2, exported.size());
    java.util.Locale root = java.util.Locale.ROOT;
    for (HttpServletRequest each : exported) {
      assertNotSame(request, each);
      for (String name : forwarding.keySet()) {
        for (String lookup : List.of(name, name.toLowerCase(root), name.toUpperCase(root))) {
          assertNull(each.getHeader(lookup), lookup);
          assertFalse(each.getHeaders(lookup).hasMoreElements(), lookup);
          assertEquals(-1, each.getIntHeader(lookup), lookup);
          assertEquals(-1, each.getDateHeader(lookup), lookup);
        }
        assertEquals(forwarding.get(name), request.getHeader(name), name);
      }
      assertEquals(List.of("Accept"), Collections.list(each.getHeaderNames()));
      assertEquals("application/fhir+json", each.getHeader("accept"));
      assertEquals(request.getQueryString(), each.getQueryString());
    }
  }

  @Test
  void forbiddenIsTranslatedWithFixedDiagnostics() throws Exception {
    String denial = "User has no data read access to tracked entity type: " + TE_TYPE;
    assertForbidden(teError(new ForbiddenException(denial), origin));
    var noAccess = new ForbiddenException("User has no access to program: " + PROGRAM);
    assertTrue(eventResult(noAccess, new EnrollmentRequestParams()).forbidden());
    String noType = "Tracked entity type is specified but does not exist: " + TE_TYPE;
    TrackedEntityRequestParams byType = new TrackedEntityRequestParams();
    byType.setTrackedEntityType(UID.of(TE_TYPE));
    assertForbidden(teError(new BadRequestException(noType), byType, origin));
    var missing = new BadRequestException(MISSING);
    assertForbidden(teError(missing, search(null), origin));
    assertTrue(eventResult(missing, inProgram()).forbidden());
    assertNotSupported(teError(missing, origin), "Patient");
    assertNotSupported(eventError(missing, new EnrollmentRequestParams()), "Observation");
  }

  @Test
  void illegalQueryIsTranslatedToInvalidNamingOriginParameters() throws Exception {
    String nonSearchable = "Non-searchable attribute(s) can not be used during global search:  ";
    var one = new IllegalQueryException(nonSearchable + List.of(FAMILY_TEA));
    assertInvalid(teError(one, origin), "family", ATTRIBUTE_NOT_SEARCHABLE);
    var two = new IllegalQueryException(nonSearchable + List.of(GIVEN_TEA, FAMILY_TEA));
    assertInvalid(teError(two, origin), "family, given", ATTRIBUTE_NOT_SEARCHABLE);
    String min = "At least 2 attribute search parameters are required";
    String criteria = "At least 2 attributes should be mentioned in the search criteria.";
    var tooFew = new IllegalQueryException(criteria);
    assertInvalid(teError(tooFew, origin), "family, given", min);
    List<String> configured = origin.configuredAttributeParameters();
    var none = new FhirSearchOrigin(origin.attributeToParameter(), List.of(), configured);
    assertInvalid(teError(tooFew, none), "identifier, family, given", min);
    assertInvalid(teError(tooFew, FhirSearchOrigin.empty()), "_id", min + NO_ATTRIBUTE_PARAMETERS);
    var maxCount = new IllegalQueryException(MAX_COUNT_REACHED);
    TrackedEntityRequestParams byId = new TrackedEntityRequestParams();
    byId.setTrackedEntities(Set.of(UID.of(uid())));
    assertInvalid(teError(maxCount, byId, origin), "_id, family, given", ABOVE_MAX_COUNT);
    assertInvalid(teError(maxCount, byId, none), "_id", ABOVE_MAX_COUNT);
    assertInvalid(teError(maxCount, none), "identifier, family, given", ABOVE_MAX_COUNT);
    assertInvalid(teError(maxCount, FhirSearchOrigin.empty()), "_id", ABOVE_MAX_COUNT);
    var scope = new IllegalQueryException("Search scope is not valid");
    assertInvalid(teError(scope, byId, none), "_id", SCOPE_REJECTED);
  }

  @Test
  void badRequestCitingOriginAttributeNamesItsParameter() throws Exception {
    assertInvalid(teError(blocked(GIVEN_TEA), origin), "given", ATTRIBUTE_VALUE_REJECTED);
    String tooShort =
        "At least 3 character(s) should be present in the filter to start a search, but the filter"
            + (" for the tracked entity attribute " + FAMILY_TEA + " doesn't contain enough.");
    var shortValue = new BadRequestException(tooShort);
    assertInvalid(teError(shortValue, origin), "family", ATTRIBUTE_VALUE_REJECTED);
    TrackedEntityRequestParams search = search(SURNAME);
    assertNotSupported(teError(echoOf(search), search, origin), "Patient");
    assertInvalid(teError(blocked(FAMILY_TEA), search, origin), "family", ATTRIBUTE_VALUE_REJECTED);
    var gender = new IllegalQueryException("Non-searchable attribute(s): [" + GENDER_TEA + "]");
    assertInvalid(teError(gender, search, origin), "gender", ATTRIBUTE_NOT_SEARCHABLE);
    TrackedEntityRequestParams selector = search(PROGRAM + " " + SELECTOR_NOT_FOUND);
    assertNotSupported(teError(echoOf(selector), selector, origin), "Patient");
  }

  @Test
  void residualBadRequestIsNotSupported() throws Throwable {
    String notTracker = "Program specified is not a tracker program: " + PROGRAM;
    FhirSearchOrigin none = FhirSearchOrigin.empty();
    assertNotSupported(teError(new BadRequestException(notTracker), none), "Patient");
    assertNotSupported(teError(blocked("otherAttr01"), origin), "Patient");
    assertNotSupported(teError(blocked(FAMILY_TEA + "2"), origin), "Patient");
    var invalid = new IllegalQueryException("Query is not valid");
    assertInvalid(teError(invalid, origin), "family, given", SCOPE_REJECTED);
    assertNotSupported(eventError(invalid, new EnrollmentRequestParams()), "Observation");
    TrackedEntityRequestParams search = search(SURNAME);
    String hidden = MISSING + " " + SURNAME;
    for (Exception failure :
        List.of(
            new ForbiddenException(SURNAME),
            new NotFoundException(SURNAME),
            new BadRequestException(hidden),
            new BadRequestException(blocked(FAMILY_TEA).getMessage() + " " + SURNAME),
            new IllegalQueryException(
                "Non-searchable attribute(s): [" + FAMILY_TEA + "] " + SURNAME),
            new IllegalQueryException("At least 2 attributes should be mentioned. " + SURNAME),
            new IllegalQueryException(MAX_COUNT_REACHED + " " + SURNAME))) {
      loggedOnceWithoutSurname(() -> teError(failure, search, origin));
    }
    String unusable = loggedOnceWithoutSurname(() -> teError(echoOf(search), search, origin));
    assertTrue(unusable.startsWith("WARN") && unusable.contains("Patient"), unusable);
    assertTrue(unusable.contains(PROGRAM), unusable);
    for (var denial : List.of(new ForbiddenException(SURNAME), new BadRequestException(hidden))) {
      loggedOnceWithoutSurname(() -> assertTrue(eventResult(denial, inProgram()).forbidden()));
    }
    for (var e : List.of(new BadRequestException(SURNAME), new IllegalQueryException(SURNAME))) {
      String logged = loggedOnceWithoutSurname(() -> eventError(e, inProgram()));
      assertTrue(logged.startsWith("WARN") && logged.contains("Observation"), logged);
      assertTrue(logged.contains(PROGRAM), logged);
    }
  }

  @Test
  void oneDeadlineSpansEveryCallOfAnOperation() throws Exception {
    when(timeout.newDeadline()).thenAnswer(invocation -> deadlineIn(Duration.ofSeconds(10)));
    when(trackedEntityAdapter.find(any(), exported()))
        .thenAnswer(i -> recorded(page(trackedEntity(uid(), TE_TYPE, UPDATED))));
    when(enrollmentAdapter.find(any(), exported())).thenAnswer(i -> recorded(page()));
    TrackedEntityRequestParams patientRead = new TrackedEntityRequestParams();
    EnrollmentRequestParams firstProgram = new EnrollmentRequestParams();
    EnrollmentRequestParams secondProgram = new EnrollmentRequestParams();
    reader.withinDeadline(
        () ->
            List.of(
                reader.findTrackedEntities(patientRead, request, FhirSearchOrigin.empty()),
                reader.findEnrollments(firstProgram, request, ENCOUNTER),
                reader.findEnrollments(secondProgram, request, OBSERVATION)));
    verify(timeout, times(1)).newDeadline();
    verify(trackedEntityAdapter).find(same(patientRead), exported());
    verify(enrollmentAdapter).find(same(firstProgram), exported());
    verify(enrollmentAdapter).find(same(secondProgram), exported());
    assertNotNull(seen.get(0));
    assertEquals(Collections.nCopies(3, seen.get(0)), seen);
    assertNull(DeadlineHolder.get());
    TrackedEntityRequestParams late = new TrackedEntityRequestParams();
    DeadlineHolder.set(deadlineIn(Duration.ofSeconds(10)));
    nanos += Duration.ofSeconds(11).toNanos();
    assertThrows(
        DeadlineExceededException.class, () -> reader.findTrackedEntities(late, request, origin));
    verify(trackedEntityAdapter, never()).find(same(late), any());
  }

  @Test
  void existingDeadlineIsReusedAndNotCleared() throws Exception {
    Deadline existing = deadlineIn(Duration.ofSeconds(30));
    DeadlineHolder.set(existing);
    EnrollmentRequestParams params = new EnrollmentRequestParams();
    when(enrollmentAdapter.find(eq(params), exported())).thenAnswer(i -> recorded(page()));
    reader.withinDeadline(() -> reader.findEnrollments(params, request, ENCOUNTER));
    assertEquals(1, seen.size());
    assertSame(existing, seen.get(0));
    assertSame(existing, DeadlineHolder.get());
    verify(timeout, never()).newDeadline();
    when(timeout.newDeadline()).thenAnswer(invocation -> deadlineIn(Duration.ofSeconds(10)));
    for (Deadline held : Arrays.asList(null, deadlineIn(Duration.ofSeconds(30)))) {
      DeadlineHolder.set(held);
      for (RuntimeException timedOut : queryTimeouts()) {
        Executable timesOut = () -> reader.withinDeadline(throwing(timedOut));
        var exceeded = assertThrows(DeadlineExceededException.class, timesOut);
        String budget = held == null ? "10s" : "30s";
        assertEquals("Request exceeded its time budget of " + budget, exceeded.getMessage());
        assertSame(timedOut, exceeded.getCause());
        assertSame(held, DeadlineHolder.get(), "only an owned deadline is cleared");
      }
    }
    verify(timeout, times(queryTimeouts().size())).newDeadline();
    DeadlineHolder.clear();
    doReturn(null).when(timeout).newDeadline();
    for (RuntimeException timedOut : queryTimeouts()) {
      assertRethrown(timedOut, () -> reader.withinDeadline(throwing(timedOut)));
      assertNull(DeadlineHolder.get());
    }
    doAnswer(invocation -> deadlineIn(Duration.ofSeconds(10))).when(timeout).newDeadline();
    RuntimeException first = new IllegalStateException("first");
    RuntimeException second = new IllegalStateException("second", first);
    first.initCause(second);
    var expired = new DeadlineExceededException(Duration.ofSeconds(5), queryTimeouts().get(0));
    var failed = new IllegalStateException("operation failed");
    for (RuntimeException failure : List.of(FhirApiException.notFound(), failed, expired, first)) {
      assertRethrown(failure, () -> reader.withinDeadline(throwing(failure)));
      assertNull(DeadlineHolder.get());
    }
    seen.clear();
    clearInvocations(timeout);
    TransactionStatus status = new SimpleTransactionStatus();
    List<TransactionDefinition> definitions = new ArrayList<>();
    when(transactionManager.getTransaction(any()))
        .thenAnswer(i -> definitions.add(i.getArgument(0)) ? recorded(status) : null);
    assertEquals("result", transactional.withinDeadline(() -> recorded("result")));
    InOrder order = inOrder(timeout, transactionManager);
    order.verify(timeout).newDeadline();
    order.verify(transactionManager).getTransaction(any());
    order.verify(transactionManager).commit(status);
    assertTrue(definitions.get(0).isReadOnly());
    assertEquals(
        TransactionDefinition.PROPAGATION_REQUIRED, definitions.get(0).getPropagationBehavior());
    assertNotNull(seen.get(0), "the deadline is held when the transaction begins");
    assertEquals(List.of(seen.get(0), seen.get(0)), seen);
    RuntimeException timedOut = queryTimeouts().get(1);
    Executable timesOut = () -> transactional.withinDeadline(throwing(timedOut));
    assertSame(timedOut, assertThrows(DeadlineExceededException.class, timesOut).getCause());
    assertRethrown(failed, () -> transactional.withinDeadline(throwing(failed)));
    verify(transactionManager, times(3)).getTransaction(any());
    verify(transactionManager, times(2)).rollback(status);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      assertEquals("active", transactional.withinDeadline(() -> "active"));
      assertSame(timedOut, assertThrows(DeadlineExceededException.class, timesOut).getCause());
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
    }
    verifyNoMoreInteractions(transactionManager);
    assertNull(DeadlineHolder.get());
    clearInvocations(transactionManager);
    doReturn(null).when(timeout).newDeadline();
    TrackedEntityRequestParams teParams = new TrackedEntityRequestParams();
    WebMessageException rejected = new WebMessageException(WebMessageUtils.conflict("x"));
    doThrow(rejected).when(trackedEntityAdapter).find(eq(teParams), exported());
    for (FhirTrackerReader each : List.of(transactional, reader)) {
      assertRethrown(
          rejected,
          () -> each.withinDeadline(() -> each.findTrackedEntities(teParams, request, origin)));
    }
    UndeclaredThrowableException undeclared =
        new UndeclaredThrowableException(new WebMessageException(WebMessageUtils.conflict("y")));
    assertRethrown(undeclared, () -> transactional.withinDeadline(throwing(undeclared)));
    verify(transactionManager, times(2)).rollback(status);
    verify(transactionManager, never()).commit(any());
    Connection connection = mock(Connection.class);
    FhirTrackerReader bounded = bounded(connection);
    when(connection.getNetworkTimeout()).thenReturn(0, 1_500);
    when(timeout.newDeadline()).thenAnswer(invocation -> deadlineIn(Duration.ofSeconds(10)));
    Supplier<Object> checkpointLater =
        () -> {
          nanos += Duration.ofSeconds(3).toNanos();
          bounded.checkpoint();
          return "result";
        };
    assertEquals("result", bounded.withinDeadline(checkpointLater));
    order = inOrder(connection);
    order.verify(connection).setNetworkTimeout(any(), eq(12_000));
    order.verify(connection).setNetworkTimeout(any(), eq(9_000));
    order.verify(connection).commit();
    order.verify(connection).setNetworkTimeout(any(), eq(0));
    order.verify(connection).close();
    assertEquals("result", bounded.withinDeadline(checkpointLater));
    verify(connection, times(3)).setNetworkTimeout(any(), eq(1_500));
    doReturn(null).when(timeout).newDeadline();
    assertEquals("result", bounded.withinDeadline(() -> "result"));
    verify(connection, times(6)).setNetworkTimeout(any(), anyInt());
    doAnswer(invocation -> deadlineIn(Duration.ofSeconds(10))).when(timeout).newDeadline();
    doThrow(new SQLException("unsupported")).when(connection).setNetworkTimeout(any(), anyInt());
    assertEquals("result", bounded.withinDeadline(checkpointLater));
    verify(connection, times(4)).commit();
    verify(connection, times(4)).close();
    Connection closed = mock(Connection.class);
    FhirTrackerReader failing = bounded(closed);
    doThrow(new SQLException("This connection has been closed.", "08003")).when(closed).rollback();
    RuntimeException early = stalled();
    assertRethrown(early, () -> failing.withinDeadline(throwing(early)));
    RuntimeException late = stalled();
    Supplier<Object> stallsPastTheDeadline =
        () -> {
          nanos += Duration.ofSeconds(12).toNanos();
          throw late;
        };
    Executable stalls = () -> failing.withinDeadline(stallsPastTheDeadline);
    DeadlineExceededException exceeded = assertThrows(DeadlineExceededException.class, stalls);
    assertEquals("Request exceeded its time budget of 10s", exceeded.getMessage());
    assertSame(late, exceeded.getCause());
    for (RuntimeException failure : List.of(early, late)) {
      assertEquals(1, failure.getSuppressed().length);
      assertInstanceOf(TransactionSystemException.class, failure.getSuppressed()[0]);
    }
    verify(closed, times(2)).rollback();
    verify(closed, times(2)).setNetworkTimeout(any(), eq(0));
    verify(closed, times(2)).close();
  }

  @Test
  void deadlineExpiryIsLoggedOnceWithFhirContext() throws Throwable {
    request.setRequestURI("/api/fhir/Patient/" + TE + "/$everything");
    request.setQueryString("family=" + SURNAME);
    var expired = new DeadlineExceededException(Duration.ofSeconds(1), queryTimeouts().get(0));
    RuntimeException absent = FhirApiException.notFound();
    Executable fails = () -> reader.withinDeadline(throwing(absent));
    Executable nested = () -> reader.withinDeadline(() -> reader.withinDeadline(throwing(expired)));
    Executable failures =
        () -> assertAll(() -> assertRethrown(absent, fails), () -> assertRethrown(expired, nested));
    String timedOut = " timed out: Request exceeded its time budget of 1s";
    assertEquals("WARN FHIR operation" + timedOut, loggedOnceWithoutSurname(failures));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/fhir/Patient/{id}");
    assertEquals("WARN FHIR Patient read" + timedOut, loggedOnceWithoutSurname(failures));
    request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/fhir/Encounter");
    assertEquals("WARN FHIR Encounter search-type" + timedOut, loggedOnceWithoutSurname(failures));
    request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/fhir/Patient/{id}/$everything");
    DeadlineHolder.set(deadlineIn(Duration.ofSeconds(1)));
    Executable converted = () -> reader.withinDeadline(throwing(queryTimeouts().get(1)));
    Executable convertedOnce = () -> assertThrows(DeadlineExceededException.class, converted);
    String everything = loggedOnceWithoutSurname(convertedOnce, Level.WARN);
    assertEquals("WARN FHIR Patient $everything" + timedOut, everything);
  }

  private FhirTrackerReader bounded(Connection connection) throws SQLException {
    DataSource dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    var manager = new DataSourceTransactionManager(dataSource);
    return new FhirTrackerReader(
        trackedEntityAdapter, enrollmentAdapter, timeout, manager, dataSource);
  }

  private static RuntimeException stalled() {
    var readTimedOut = new SocketTimeoutException("Read timed out");
    var ioError = new SQLException("An I/O error occurred", "08006", readTimedOut);
    return new org.springframework.dao.DataAccessResourceFailureException("stalled", ioError);
  }

  private static Supplier<Object> throwing(RuntimeException exception) {
    return () -> {
      throw exception;
    };
  }

  private static List<RuntimeException> queryTimeouts() {
    String canceled = "ERROR: canceling statement due to user request";
    var sql = new SQLException(canceled, "57014");
    var hibernate = new org.hibernate.QueryTimeoutException(canceled, sql, "select 1");
    var jpa = new jakarta.persistence.QueryTimeoutException(canceled, hibernate);
    var spring = new org.springframework.dao.QueryTimeoutException(canceled);
    var wrapped = new IllegalStateException("wrapped", new PersistenceException("wrapped", jpa));
    return List.of(jpa, hibernate, spring, wrapped);
  }

  private Deadline deadlineIn(Duration budget) {
    return Deadline.in(budget, () -> nanos);
  }

  private <T> T recorded(T result) {
    seen.add(DeadlineHolder.get());
    return result;
  }

  private FhirApiException teError(Exception exception, FhirSearchOrigin o) throws Exception {
    return teError(exception, new TrackedEntityRequestParams(), o);
  }

  private FhirApiException teError(Exception e, TrackedEntityRequestParams p, FhirSearchOrigin o)
      throws Exception {
    doThrow(e).when(trackedEntityAdapter).find(eq(p), exported());
    return assertThrows(FhirApiException.class, () -> reader.findTrackedEntities(p, request, o));
  }

  private FhirApiException eventError(Exception e, EnrollmentRequestParams p) throws Exception {
    doThrow(e).when(enrollmentAdapter).find(eq(p), exported());
    return assertThrows(
        FhirApiException.class, () -> reader.findEnrollments(p, request, OBSERVATION));
  }

  private EnrollmentResult eventResult(Exception e, EnrollmentRequestParams p) throws Exception {
    doThrow(e).when(enrollmentAdapter).find(eq(p), exported());
    return reader.findEnrollments(p, request, ENCOUNTER);
  }

  private HttpServletRequest exported() {
    return argThat(r -> r instanceof ServletRequestWrapper w && w.getRequest() == request);
  }

  private static TrackedEntityRequestParams search(String name) {
    TrackedEntityRequestParams params = new TrackedEntityRequestParams();
    params.setProgram(UID.of(PROGRAM));
    params.setFilter(name == null ? null : FAMILY_TEA + ":sw:" + name + "," + GENDER_TEA + ":eq:");
    return params;
  }

  private static EnrollmentRequestParams inProgram() {
    EnrollmentRequestParams params = new EnrollmentRequestParams();
    params.setProgram(UID.of(PROGRAM));
    return params;
  }

  private static BadRequestException echoOf(TrackedEntityRequestParams params) {
    return new BadRequestException(
        "filter=" + params.getFilter() + " is invalid. Binary operator 'eq' must have a value.");
  }

  private static BadRequestException blocked(String attribute) {
    return new BadRequestException("Operators [SW] are blocked for attribute '" + attribute + "'.");
  }

  private static void assertRethrown(Exception expected, Executable executable) {
    assertSame(expected, assertThrows(expected.getClass(), executable));
  }

  private static String loggedOnceWithoutSurname(Executable call, Level... level) throws Throwable {
    List<LogEvent> events = new CopyOnWriteArrayList<>();
    var appender =
        new AbstractAppender("readerLog", null, null, true, Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent event) {
            events.add(event.toImmutable());
          }
        };
    String name = FhirTrackerReader.class.getName();
    LoggerConfig capture = new LoggerConfig(name, level.length == 0 ? Level.ALL : level[0], false);
    capture.addAppender(appender, Level.ALL, null);
    var configuration = LoggerContext.getContext(false).getConfiguration();
    synchronized (configuration) {
      LoggerConfig previous = configuration.getLoggers().get(name);
      appender.start();
      configuration.removeLogger(name);
      configuration.addLogger(name, capture);
      LoggerContext.getContext(false).updateLoggers();
      try {
        call.execute();
      } finally {
        configuration.removeLogger(name);
        Optional.ofNullable(previous).ifPresent(config -> configuration.addLogger(name, config));
        LoggerContext.getContext(false).updateLoggers();
        appender.stop();
      }
    }
    assertEquals(1, events.size());
    LogEvent event = events.get(0);
    String logged = event.getLevel() + " " + event.getMessage().getFormattedMessage();
    assertFalse(logged.contains(SURNAME), logged);
    assertFalse(String.valueOf(event.getThrown()).contains(SURNAME), logged);
    return event.getThrown() == null ? logged : logged + " " + event.getThrown();
  }

  private static void assertInvalid(FhirApiException exception, String names, String detail) {
    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    assertEquals(IssueType.INVALID, exception.getIssueType());
    assertEquals("Invalid parameter '" + names + "': " + detail, exception.getDiagnostics());
  }

  private static void assertForbidden(FhirApiException exception) {
    assertEquals(HttpStatus.FORBIDDEN, exception.getStatus());
    assertEquals(IssueType.FORBIDDEN, exception.getIssueType());
    assertEquals("Access to the requested resource is not permitted", exception.getDiagnostics());
  }

  private static void assertNotSupported(FhirApiException exception, String fhirType) {
    assertEquals(HttpStatus.NOT_IMPLEMENTED, exception.getStatus());
    assertEquals(IssueType.NOTSUPPORTED, exception.getIssueType());
    String diagnostics = "The configured mapping for " + fhirType + " cannot be used";
    assertEquals(diagnostics, exception.getDiagnostics());
  }
}
