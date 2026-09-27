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

import jakarta.persistence.PersistenceException;
import java.lang.reflect.UndeclaredThrowableException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/**
 * Tests the Tracker export calls, exception translation, deadline and operation transaction of
 * {@link FhirTrackerReader}.
 */
@ExtendWith(MockitoExtension.class)
class FhirTrackerReaderTest {
  private static final String FAMILY_TEA = "fhirFamily1";
  private static final String GIVEN_TEA = "fhirGiven01";
  private static final String GENDER_TEA = "fhirGender1";
  private static final String IDENTIFIER_TEA = "fhirIdent01";
  private static final String UNMAPPED_TEA = "otherAttr01";
  private static final String TRACKED_ENTITY_TYPE = "ja8NY4PW7Xm";
  private static final String PROGRAM = "BFcipDERJnf";
  private static final String SURNAME = "SensitiveSurname";
  private static final String MISSING = "Program is specified but does not exist: " + PROGRAM;
  private static final String TOO_FEW =
      "At least 2 attributes should be mentioned in the search criteria.";
  @Mock private FhirTrackedEntityExportAdapter trackedEntityAdapter;
  @Mock private FhirEnrollmentExportAdapter enrollmentAdapter;
  @Mock private TrackerExportTimeout timeout;
  @Mock private PlatformTransactionManager transactionManager;
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final List<Deadline> seen = new ArrayList<>();
  private long nanos = TimeUnit.SECONDS.toNanos(1_000);
  private FhirTrackerReader reader, transactional;
  private FhirSearchOrigin origin;

  @BeforeEach
  void setUp() {
    reader = new FhirTrackerReader(trackedEntityAdapter, enrollmentAdapter, timeout);
    transactional =
        new FhirTrackerReader(trackedEntityAdapter, enrollmentAdapter, timeout, transactionManager);
    Map<String, String> attributeToParameter = new LinkedHashMap<>();
    attributeToParameter.put(FAMILY_TEA, "family");
    attributeToParameter.put(GIVEN_TEA, "given");
    attributeToParameter.put(IDENTIFIER_TEA, "identifier");
    attributeToParameter.put(GENDER_TEA, "gender");
    List<String> configured = List.of("identifier", "family", "given");
    origin = new FhirSearchOrigin(attributeToParameter, List.of("family", "given"), configured);
  }

  @AfterEach
  void clearDeadline() {
    DeadlineHolder.clear();
  }

  @Test
  void onlyExportAdaptersAreInvoked() throws Exception {
    TrackedEntityRequestParams teParams = new TrackedEntityRequestParams();
    String trackedEntityUid = uid();
    TrackedEntity trackedEntity = trackedEntity(trackedEntityUid, TRACKED_ENTITY_TYPE, UPDATED);
    when(trackedEntityAdapter.find(teParams, request)).thenReturn(page(trackedEntity));
    var found = reader.findTrackedEntities(teParams, request, FhirSearchOrigin.empty());
    assertEquals(List.of(trackedEntity), found.getItems());
    verify(trackedEntityAdapter).find(same(teParams), same(request));
    EnrollmentRequestParams enrollmentParams = new EnrollmentRequestParams();
    Enrollment enrollment = enrollment(uid(), trackedEntityUid, PROGRAM);
    when(enrollmentAdapter.find(enrollmentParams, request)).thenReturn(page(enrollment));
    EnrollmentResult result = reader.findEnrollments(enrollmentParams, request, ENCOUNTER);
    assertEquals(new EnrollmentResult(List.of(enrollment), false), result);
    verify(enrollmentAdapter).find(same(enrollmentParams), same(request));
    verifyNoMoreInteractions(trackedEntityAdapter, enrollmentAdapter, timeout);
    var notFound = teError(new NotFoundException("TrackedEntity not found"), origin);
    assertEquals(HttpStatus.NOT_FOUND, notFound.getStatus());
    assertEquals(IssueType.NOTFOUND, notFound.getIssueType());
    var rejected = new WebMessageException(WebMessageUtils.conflict("x"));
    DeadlineExceededException expired = new DeadlineExceededException(Duration.ofSeconds(5));
    for (Exception exception : List.of(rejected, expired, new IllegalArgumentException("x"))) {
      doThrow(exception).when(trackedEntityAdapter).find(teParams, request);
      assertRethrown(exception, () -> reader.findTrackedEntities(teParams, request, origin));
    }
    doThrow(expired).when(enrollmentAdapter).find(enrollmentParams, request);
    assertRethrown(expired, () -> reader.findEnrollments(enrollmentParams, request, ENCOUNTER));
  }

  @Test
  void forbiddenIsTranslatedWithFixedDiagnostics() throws Exception {
    String denial = "User has no data read access to tracked entity type: " + TRACKED_ENTITY_TYPE;
    assertForbidden(teError(new ForbiddenException(denial), origin));
    var noAccess = new ForbiddenException("User has no access to program: " + PROGRAM);
    assertTrue(enrollmentResult(noAccess, new EnrollmentRequestParams()).forbidden());
    String noType = "Tracked entity type is specified but does not exist: " + TRACKED_ENTITY_TYPE;
    TrackedEntityRequestParams byType = new TrackedEntityRequestParams();
    byType.setTrackedEntityType(UID.of(TRACKED_ENTITY_TYPE));
    assertForbidden(teError(new BadRequestException(noType), byType, origin));
    var missing = new BadRequestException(MISSING);
    assertForbidden(teError(missing, search(null), origin));
    assertTrue(enrollmentResult(missing, inProgram()).forbidden());
    assertNotSupported(teError(missing, origin), "Patient");
    assertNotSupported(enrollmentError(missing, new EnrollmentRequestParams()), "Observation");
  }

  @Test
  void illegalQueryIsTranslatedToInvalidNamingOriginParameters() throws Exception {
    String nonSearchable = "Non-searchable attribute(s) can not be used during global search:  ";
    var one = new IllegalQueryException(nonSearchable + List.of(FAMILY_TEA));
    assertInvalid(teError(one, origin), "family", ATTRIBUTE_NOT_SEARCHABLE);
    var two = new IllegalQueryException(nonSearchable + List.of(GIVEN_TEA, FAMILY_TEA));
    assertInvalid(teError(two, origin), "family, given", ATTRIBUTE_NOT_SEARCHABLE);
    String min = "At least 2 attribute search parameters are required";
    var tooFew = new IllegalQueryException(TOO_FEW);
    assertInvalid(teError(tooFew, origin), "family, given", min);
    List<String> configured = origin.configuredAttributeParameters();
    var none = new FhirSearchOrigin(origin.attributeToParameter(), List.of(), configured);
    assertInvalid(teError(tooFew, none), "identifier, family, given", min);
    assertInvalid(teError(tooFew, FhirSearchOrigin.empty()), "_id", min + NO_ATTRIBUTE_PARAMETERS);
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
    assertNotSupported(teError(blocked(UNMAPPED_TEA), origin), "Patient");
    assertNotSupported(teError(blocked(FAMILY_TEA + "2"), origin), "Patient");
    var invalid = new IllegalQueryException("Query is not valid");
    assertNotSupported(teError(invalid, origin), "Patient");
    assertNotSupported(enrollmentError(invalid, new EnrollmentRequestParams()), "Observation");
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
            new IllegalQueryException("At least 2 attributes should be mentioned. " + SURNAME))) {
      loggedOnceWithoutSurname(() -> teError(failure, search, origin));
    }
    String unusable = loggedOnceWithoutSurname(() -> teError(echoOf(search), search, origin));
    assertTrue(unusable.startsWith("WARN") && unusable.contains("Patient"), unusable);
    assertTrue(unusable.contains(PROGRAM), unusable);
    for (var denial : List.of(new ForbiddenException(SURNAME), new BadRequestException(hidden))) {
      loggedOnceWithoutSurname(() -> assertTrue(enrollmentResult(denial, inProgram()).forbidden()));
    }
    for (var e : List.of(new BadRequestException(SURNAME), new IllegalQueryException(SURNAME))) {
      String logged = loggedOnceWithoutSurname(() -> enrollmentError(e, inProgram()));
      assertTrue(logged.startsWith("WARN") && logged.contains("Observation"), logged);
      assertTrue(logged.contains(PROGRAM), logged);
    }
  }

  @Test
  void oneDeadlineSpansEveryCallOfAnOperation() throws Exception {
    when(timeout.newDeadline()).thenAnswer(invocation -> deadlineIn(Duration.ofSeconds(10)));
    when(trackedEntityAdapter.find(any(), same(request)))
        .thenAnswer(i -> recorded(page(trackedEntity(uid(), TRACKED_ENTITY_TYPE, UPDATED))));
    when(enrollmentAdapter.find(any(), same(request))).thenAnswer(i -> recorded(page()));
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
    verify(trackedEntityAdapter).find(same(patientRead), same(request));
    verify(enrollmentAdapter).find(same(firstProgram), same(request));
    verify(enrollmentAdapter).find(same(secondProgram), same(request));
    assertNotNull(seen.get(0));
    assertEquals(Collections.nCopies(3, seen.get(0)), seen);
    assertNull(DeadlineHolder.get());
    doReturn(null).when(timeout).newDeadline();
    assertNull(reader.withinDeadline(DeadlineHolder::get), "a disabled timeout sets no deadline");
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
    when(enrollmentAdapter.find(params, request)).thenAnswer(i -> recorded(page()));
    reader.withinDeadline(() -> reader.findEnrollments(params, request, ENCOUNTER));
    assertEquals(1, seen.size());
    assertSame(existing, seen.get(0));
    assertSame(existing, DeadlineHolder.get());
    Supplier<Object> failing = throwing(new IllegalStateException("operation failed"));
    assertThrows(IllegalStateException.class, () -> reader.withinDeadline(failing));
    assertSame(existing, DeadlineHolder.get(), "a failed operation leaves the deadline in place");
    verify(timeout, never()).newDeadline();
  }

  @Test
  void queryTimeoutUnderADeadlineBecomesDeadlineExceeded() {
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
  }

  @Test
  void queryTimeoutWithoutADeadlineAndOtherFailuresAreRethrownUnchanged() {
    when(timeout.newDeadline()).thenReturn(null);
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
  }

  @Test
  void operationRunsInOneReadOnlyTransactionBegunUnderItsDeadlineUnlessOneIsActive() {
    when(timeout.newDeadline()).thenAnswer(invocation -> deadlineIn(Duration.ofSeconds(10)));
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
    RuntimeException failed = new IllegalStateException("operation failed");
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
  }

  @Test
  void checkedAndUndeclaredExceptionsReachTheCallerUnchangedAndRollBack() throws Exception {
    TransactionStatus status = new SimpleTransactionStatus();
    when(transactionManager.getTransaction(any())).thenReturn(status);
    TrackedEntityRequestParams params = new TrackedEntityRequestParams();
    WebMessageException rejected = new WebMessageException(WebMessageUtils.conflict("x"));
    doThrow(rejected).when(trackedEntityAdapter).find(params, request);
    for (FhirTrackerReader each : List.of(transactional, reader)) {
      assertRethrown(
          rejected,
          () -> each.withinDeadline(() -> each.findTrackedEntities(params, request, origin)));
    }
    UndeclaredThrowableException undeclared =
        new UndeclaredThrowableException(new WebMessageException(WebMessageUtils.conflict("y")));
    assertRethrown(undeclared, () -> transactional.withinDeadline(throwing(undeclared)));
    verify(transactionManager, times(2)).rollback(status);
    verify(transactionManager, never()).commit(any());
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
    doThrow(e).when(trackedEntityAdapter).find(p, request);
    return assertThrows(FhirApiException.class, () -> reader.findTrackedEntities(p, request, o));
  }

  private FhirApiException enrollmentError(Exception e, EnrollmentRequestParams p)
      throws Exception {
    doThrow(e).when(enrollmentAdapter).find(p, request);
    return assertThrows(
        FhirApiException.class, () -> reader.findEnrollments(p, request, OBSERVATION));
  }

  private EnrollmentResult enrollmentResult(Exception e, EnrollmentRequestParams p)
      throws Exception {
    doThrow(e).when(enrollmentAdapter).find(p, request);
    return reader.findEnrollments(p, request, ENCOUNTER);
  }

  private static TrackedEntityRequestParams search(String familyValue) {
    TrackedEntityRequestParams params = new TrackedEntityRequestParams();
    params.setProgram(UID.of(PROGRAM));
    if (familyValue != null) {
      params.setFilter(FAMILY_TEA + ":sw:" + familyValue + "," + GENDER_TEA + ":eq:");
    }
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

  private static String loggedOnceWithoutSurname(Executable call) throws Throwable {
    List<LogEvent> events = new CopyOnWriteArrayList<>();
    var appender =
        new AbstractAppender("readerLog", null, null, true, Property.EMPTY_ARRAY) {
          @Override
          public void append(LogEvent event) {
            events.add(event.toImmutable());
          }
        };
    String name = FhirTrackerReader.class.getName();
    LoggerConfig capture = new LoggerConfig(name, Level.ALL, false);
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
    return logged;
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
