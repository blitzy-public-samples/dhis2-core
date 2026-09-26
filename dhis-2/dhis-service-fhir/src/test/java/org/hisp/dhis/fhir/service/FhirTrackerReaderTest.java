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
import org.hisp.dhis.tracker.export.fieldfiltering.Fields;
import org.hisp.dhis.tracker.export.timeout.TrackerExportTimeout;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.*;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.*;
import org.hisp.dhis.webapi.controller.tracker.view.*;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/** Tests the Tracker export calls and exception translation of {@link FhirTrackerReader}. */
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
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final List<Deadline> seen = new ArrayList<>();
  private long nanos = TimeUnit.SECONDS.toNanos(1_000);
  private FhirTrackerReader reader;
  private FhirSearchOrigin origin;

  @BeforeEach
  void setUp() {
    reader = new FhirTrackerReader(trackedEntityAdapter, enrollmentAdapter, timeout);
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
    FhirApiException notFound =
        trackedEntityError(new NotFoundException("TrackedEntity not found"), origin);
    assertEquals(HttpStatus.NOT_FOUND, notFound.getStatus());
    assertEquals(IssueType.NOTFOUND, notFound.getIssueType());
    for (Exception exception :
        List.of(
            new WebMessageException(WebMessageUtils.conflict("x")),
            new DeadlineExceededException(Duration.ofSeconds(5)),
            new IllegalArgumentException("x"))) {
      doThrow(exception).when(trackedEntityAdapter).find(teParams, request);
      assertRethrown(exception, () -> reader.findTrackedEntities(teParams, request, origin));
    }
    DeadlineExceededException expired = new DeadlineExceededException(Duration.ofSeconds(5));
    doThrow(expired).when(enrollmentAdapter).find(enrollmentParams, request);
    assertRethrown(expired, () -> reader.findEnrollments(enrollmentParams, request, ENCOUNTER));
  }

  @Test
  void forbiddenIsTranslatedWithFixedDiagnostics() throws Exception {
    String denial = "User has no data read access to tracked entity type: " + TRACKED_ENTITY_TYPE;
    assertForbidden(trackedEntityError(new ForbiddenException(denial), origin));
    var noAccess = new ForbiddenException("User has no access to program: " + PROGRAM);
    assertTrue(enrollmentResult(noAccess, new EnrollmentRequestParams()).forbidden());
  }

  @Test
  void illegalQueryIsTranslatedToInvalidNamingOriginParameters() throws Exception {
    String nonSearchable = "Non-searchable attribute(s) can not be used during global search:  ";
    var one = new IllegalQueryException(nonSearchable + List.of(FAMILY_TEA));
    assertInvalid(trackedEntityError(one, origin), "family", ATTRIBUTE_NOT_SEARCHABLE);
    var two = new IllegalQueryException(nonSearchable + List.of(GIVEN_TEA, FAMILY_TEA));
    assertInvalid(trackedEntityError(two, origin), "family, given", ATTRIBUTE_NOT_SEARCHABLE);
    String minimum = "At least 2 attribute search parameters are required";
    var tooFew = new IllegalQueryException(TOO_FEW);
    assertInvalid(trackedEntityError(tooFew, origin), "family, given", minimum);
    List<String> configured = origin.configuredAttributeParameters();
    var none = new FhirSearchOrigin(origin.attributeToParameter(), List.of(), configured);
    assertInvalid(trackedEntityError(tooFew, none), "identifier, family, given", minimum);
  }

  @Test
  void badRequestCitingOriginAttributeNamesItsParameter() throws Exception {
    assertInvalid(
        trackedEntityError(blocked(GIVEN_TEA), origin), "given", ATTRIBUTE_VALUE_REJECTED);
    String tooShort =
        "At least 3 character(s) should be present in the filter to start a search, but the filter"
            + " for the tracked entity attribute "
            + FAMILY_TEA
            + " doesn't contain enough.";
    var shortValue = new BadRequestException(tooShort);
    assertInvalid(trackedEntityError(shortValue, origin), "family", ATTRIBUTE_VALUE_REJECTED);
  }

  @Test
  void filterEchoedByTheExportPathIsNotACitation() throws Exception {
    TrackedEntityRequestParams search = search(SURNAME);
    assertNotSupported(trackedEntityError(echoOf(search), search, origin), "Patient");
    var family = trackedEntityError(blocked(FAMILY_TEA), search, origin);
    assertInvalid(family, "family", ATTRIBUTE_VALUE_REJECTED);
    var gender = new IllegalQueryException("Non-searchable attribute(s): [" + GENDER_TEA + "]");
    assertInvalid(trackedEntityError(gender, search, origin), "gender", ATTRIBUTE_NOT_SEARCHABLE);
    TrackedEntityRequestParams selector = search(PROGRAM + " " + SELECTOR_NOT_FOUND);
    assertNotSupported(trackedEntityError(echoOf(selector), selector, origin), "Patient");
  }

  @Test
  void residualBadRequestIsNotSupported() throws Exception {
    String notTracker = "Program specified is not a tracker program: " + PROGRAM;
    FhirSearchOrigin none = FhirSearchOrigin.empty();
    assertNotSupported(trackedEntityError(new BadRequestException(notTracker), none), "Patient");
    assertNotSupported(trackedEntityError(blocked(UNMAPPED_TEA), origin), "Patient");
    assertNotSupported(trackedEntityError(blocked(FAMILY_TEA + "2"), origin), "Patient");
    var invalid = new IllegalQueryException("Query is not valid");
    assertNotSupported(trackedEntityError(invalid, origin), "Patient");
    assertNotSupported(trackedEntityError(new IllegalQueryException(TOO_FEW), none), "Patient");
    assertNotSupported(enrollmentError(invalid, new EnrollmentRequestParams()), "Observation");
  }

  @Test
  void badRequestHidingTheSelectedProgramOrTypeIsForbidden() throws Exception {
    String typeMissing =
        "Tracked entity type is specified but does not exist: " + TRACKED_ENTITY_TYPE;
    TrackedEntityRequestParams byType = new TrackedEntityRequestParams();
    byType.setTrackedEntityType(UID.of(TRACKED_ENTITY_TYPE));
    assertForbidden(trackedEntityError(new BadRequestException(typeMissing), byType, origin));
    var missing = new BadRequestException(MISSING);
    assertForbidden(trackedEntityError(missing, search(null), origin));
    assertTrue(enrollmentResult(missing, inProgram()).forbidden());
    assertNotSupported(trackedEntityError(missing, origin), "Patient");
    assertNotSupported(enrollmentError(missing, new EnrollmentRequestParams()), "Observation");
  }

  @Test
  void exportMessagesAndSearchValuesAreNeverLogged() throws Throwable {
    TrackedEntityRequestParams search = search(SURNAME);
    String hidden = MISSING + " " + SURNAME;
    for (Exception failure :
        List.of(
            new ForbiddenException(SURNAME),
            new NotFoundException(SURNAME),
            new BadRequestException(hidden),
            new BadRequestException(
                "Operators [SW] are blocked for attribute '" + FAMILY_TEA + "'. " + SURNAME),
            new IllegalQueryException(
                "Non-searchable attribute(s): [" + FAMILY_TEA + "] " + SURNAME),
            new IllegalQueryException("At least 2 attributes should be mentioned. " + SURNAME))) {
      loggedOnceWithoutSurname(() -> trackedEntityError(failure, search, origin));
    }
    String unusable =
        loggedOnceWithoutSurname(() -> trackedEntityError(echoOf(search), search, origin));
    assertTrue(unusable.startsWith("WARN") && unusable.contains("Patient"), unusable);
    assertTrue(unusable.contains(PROGRAM), unusable);
    for (Exception denial :
        List.of(new ForbiddenException(SURNAME), new BadRequestException(hidden))) {
      loggedOnceWithoutSurname(() -> assertTrue(enrollmentResult(denial, inProgram()).forbidden()));
    }
    for (Exception rejection :
        List.of(new BadRequestException(SURNAME), new IllegalQueryException(SURNAME))) {
      String logged = loggedOnceWithoutSurname(() -> enrollmentError(rejection, inProgram()));
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
    Supplier<Object> failing =
        () -> {
          throw new IllegalStateException("operation failed");
        };
    assertThrows(IllegalStateException.class, () -> reader.withinDeadline(failing));
    assertSame(existing, DeadlineHolder.get(), "a failed operation leaves the deadline in place");
    verify(timeout, never()).newDeadline();
  }

  private Deadline deadlineIn(Duration budget) {
    return Deadline.in(budget, () -> nanos);
  }

  private <T> T recorded(T result) {
    seen.add(DeadlineHolder.get());
    return result;
  }

  private FhirApiException trackedEntityError(Exception exception, FhirSearchOrigin searchOrigin)
      throws Exception {
    return trackedEntityError(exception, new TrackedEntityRequestParams(), searchOrigin);
  }

  private FhirApiException trackedEntityError(
      Exception exception, TrackedEntityRequestParams params, FhirSearchOrigin searchOrigin)
      throws Exception {
    doThrow(exception).when(trackedEntityAdapter).find(params, request);
    return assertThrows(
        FhirApiException.class, () -> reader.findTrackedEntities(params, request, searchOrigin));
  }

  private FhirApiException enrollmentError(Exception exception, EnrollmentRequestParams params)
      throws Exception {
    doThrow(exception).when(enrollmentAdapter).find(params, request);
    return assertThrows(
        FhirApiException.class, () -> reader.findEnrollments(params, request, OBSERVATION));
  }

  private EnrollmentResult enrollmentResult(Exception exception, EnrollmentRequestParams params)
      throws Exception {
    doThrow(exception).when(enrollmentAdapter).find(params, request);
    return reader.findEnrollments(params, request, ENCOUNTER);
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
    LoggerContext context = LoggerContext.getContext(false);
    appender.start();
    context.getConfiguration().addLogger(name, capture);
    context.updateLoggers();
    try {
      call.execute();
    } finally {
      context.getConfiguration().removeLogger(name);
      context.updateLoggers();
      appender.stop();
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

  @SafeVarargs
  private static <T> FilteredPage<T> page(T... items) {
    return new FilteredPage<>(Page.withoutPager("items", List.of(items)), Fields.all());
  }
}
