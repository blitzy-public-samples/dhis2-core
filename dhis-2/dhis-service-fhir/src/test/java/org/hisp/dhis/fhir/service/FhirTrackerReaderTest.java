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

import static org.hisp.dhis.fhir.FhirTestFixtures.UPDATED;
import static org.hisp.dhis.fhir.FhirTestFixtures.enrollment;
import static org.hisp.dhis.fhir.FhirTestFixtures.trackedEntity;
import static org.hisp.dhis.fhir.FhirTestFixtures.uid;
import static org.hisp.dhis.fhir.service.FhirTrackerReader.ATTRIBUTE_NOT_SEARCHABLE;
import static org.hisp.dhis.fhir.service.FhirTrackerReader.ATTRIBUTE_VALUE_REJECTED;
import static org.hisp.dhis.fhir.service.FhirTrackerReader.PARAMETER_SEPARATOR;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hisp.dhis.common.IllegalQueryException;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.deadline.Deadline;
import org.hisp.dhis.deadline.DeadlineExceededException;
import org.hisp.dhis.deadline.DeadlineHolder;
import org.hisp.dhis.dxf2.webmessage.WebMessageException;
import org.hisp.dhis.dxf2.webmessage.WebMessageUtils;
import org.hisp.dhis.feedback.BadRequestException;
import org.hisp.dhis.feedback.ForbiddenException;
import org.hisp.dhis.feedback.NotFoundException;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.service.FhirTrackerReader.EnrollmentResult;
import org.hisp.dhis.fhir.service.FhirTrackerReader.FhirSearchOrigin;
import org.hisp.dhis.tracker.export.fieldfiltering.Fields;
import org.hisp.dhis.tracker.export.timeout.TrackerExportTimeout;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.EnrollmentRequestParams;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.FhirEnrollmentExportAdapter;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.FhirTrackedEntityExportAdapter;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.TrackedEntityRequestParams;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.FilteredPage;
import org.hisp.dhis.webapi.controller.tracker.view.Page;
import org.hisp.dhis.webapi.controller.tracker.view.TrackedEntity;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests that {@link FhirTrackerReader} reads only through the two Tracker export adapters, runs
 * every call of one FHIR operation within a single deadline, and translates export-path exceptions
 * into the specified {@link FhirApiException}s.
 */
@ExtendWith(MockitoExtension.class)
class FhirTrackerReaderTest {

  private static final String FAMILY_TEA = "fhirFamily1";
  private static final String GIVEN_TEA = "fhirGiven01";
  private static final String IDENTIFIER_TEA = "fhirIdent01";
  private static final String UNMAPPED_TEA = "otherAttr01";
  private static final String TRACKED_ENTITY_TYPE = "ja8NY4PW7Xm";
  private static final String PROGRAM = "BFcipDERJnf";

  /** The diagnostics layout of {@link FhirApiException#invalidParameter(String, String)}. */
  private static final Pattern INVALID_DIAGNOSTICS =
      Pattern.compile("^Invalid parameter '([^']*)': (.*)$");

  @Mock private FhirTrackedEntityExportAdapter trackedEntityAdapter;
  @Mock private FhirEnrollmentExportAdapter enrollmentAdapter;
  @Mock private TrackerExportTimeout timeout;

  private final MockHttpServletRequest request = new MockHttpServletRequest();

  /** Test clock read by every deadline created through {@link #deadlineIn(Duration)}. */
  private long nanos = TimeUnit.SECONDS.toNanos(1_000);

  private FhirTrackerReader reader;
  private FhirSearchOrigin origin;

  @BeforeEach
  void setUp() {
    reader = new FhirTrackerReader(trackedEntityAdapter, enrollmentAdapter, timeout);

    // Origin attributes in filter order: family, given, identifier.
    Map<String, String> attributeToParameter = new LinkedHashMap<>();
    attributeToParameter.put(FAMILY_TEA, "family");
    attributeToParameter.put(GIVEN_TEA, "given");
    attributeToParameter.put(IDENTIFIER_TEA, "identifier");
    origin =
        new FhirSearchOrigin(
            attributeToParameter,
            List.of("family", "given"),
            List.of("identifier", "family", "given"));
  }

  @AfterEach
  void clearDeadline() {
    DeadlineHolder.clear();
  }

  @Test
  void onlyExportAdaptersAreInvoked() throws Exception {
    TrackedEntityRequestParams trackedEntityParams = new TrackedEntityRequestParams();
    String trackedEntityUid = uid();
    TrackedEntity trackedEntity = trackedEntity(trackedEntityUid, TRACKED_ENTITY_TYPE, UPDATED);
    when(trackedEntityAdapter.find(trackedEntityParams, request))
        .thenReturn(trackedEntityPage(trackedEntity));

    Page<TrackedEntity> page =
        reader.findTrackedEntities(trackedEntityParams, request, FhirSearchOrigin.empty());

    assertEquals(List.of(trackedEntity), page.getItems());
    verify(trackedEntityAdapter).find(same(trackedEntityParams), same(request));

    EnrollmentRequestParams enrollmentParams = new EnrollmentRequestParams();
    Enrollment enrollment = enrollment(uid(), trackedEntityUid, PROGRAM);
    when(enrollmentAdapter.find(enrollmentParams, request)).thenReturn(enrollmentPage(enrollment));

    EnrollmentResult result =
        reader.findEnrollments(enrollmentParams, request, FhirResourceType.ENCOUNTER);

    assertEquals(List.of(enrollment), result.enrollments());
    assertFalse(result.forbidden());
    verify(enrollmentAdapter).find(same(enrollmentParams), same(request));
    verifyNoMoreInteractions(trackedEntityAdapter, enrollmentAdapter, timeout);

    Constructor<?>[] constructors = FhirTrackerReader.class.getDeclaredConstructors();
    assertEquals(1, constructors.length, "FhirTrackerReader declares exactly one constructor");
    assertArrayEquals(
        new Class<?>[] {
          FhirTrackedEntityExportAdapter.class,
          FhirEnrollmentExportAdapter.class,
          TrackerExportTimeout.class
        },
        constructors[0].getParameterTypes());

    FhirApiException notFound =
        trackedEntityError(new NotFoundException("TrackedEntity not found"), origin);
    assertEquals(HttpStatus.NOT_FOUND, notFound.getStatus());
    assertEquals(IssueType.NOTFOUND, notFound.getIssueType());

    for (Exception exception :
        List.of(
            new WebMessageException(WebMessageUtils.conflict("x")),
            new DeadlineExceededException(Duration.ofSeconds(5)),
            new IllegalArgumentException("x"))) {
      doThrow(exception).when(trackedEntityAdapter).find(trackedEntityParams, request);
      assertSame(
          exception,
          assertThrows(
              Exception.class,
              () -> reader.findTrackedEntities(trackedEntityParams, request, origin)));
    }

    DeadlineExceededException expired = new DeadlineExceededException(Duration.ofSeconds(5));
    doThrow(expired).when(enrollmentAdapter).find(enrollmentParams, request);
    assertSame(
        expired,
        assertThrows(
            DeadlineExceededException.class,
            () -> reader.findEnrollments(enrollmentParams, request, FhirResourceType.ENCOUNTER)));
  }

  @Test
  void forbiddenIsTranslatedWithFixedDiagnostics() throws Exception {
    String trackerMessage =
        "User has no data read access to tracked entity type: " + TRACKED_ENTITY_TYPE;
    FhirApiException forbidden = trackedEntityError(new ForbiddenException(trackerMessage), origin);

    assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatus());
    assertEquals(IssueType.FORBIDDEN, forbidden.getIssueType());
    assertEquals("Access to the requested resource is not permitted", forbidden.getDiagnostics());
    assertFalse(forbidden.getDiagnostics().contains(TRACKED_ENTITY_TYPE));
    assertFalse(forbidden.getDiagnostics().contains(trackerMessage));
    assertEquals(
        forbidden.getDiagnostics(),
        trackedEntityError(new ForbiddenException("No access to: " + PROGRAM), origin)
            .getDiagnostics(),
        "every denial has the same diagnostics");

    EnrollmentRequestParams enrollmentParams = new EnrollmentRequestParams();
    when(enrollmentAdapter.find(enrollmentParams, request))
        .thenThrow(new ForbiddenException("User has no access to program: " + PROGRAM));

    EnrollmentResult result =
        reader.findEnrollments(enrollmentParams, request, FhirResourceType.ENCOUNTER);

    assertTrue(result.forbidden());
    assertEquals(List.of(), result.enrollments());
  }

  @Test
  void illegalQueryIsTranslatedToInvalidNamingOriginParameters() throws Exception {
    String nonSearchable = "Non-searchable attribute(s) can not be used during global search:  ";

    FhirApiException oneAttribute =
        trackedEntityError(
            new IllegalQueryException(nonSearchable + "[" + FAMILY_TEA + "]"), origin);
    assertEquals(List.of("family"), namedParameters(oneAttribute));
    assertEquals(ATTRIBUTE_NOT_SEARCHABLE, detailOf(oneAttribute));
    assertNotNamed(oneAttribute, "given", "identifier");

    FhirApiException twoAttributes =
        trackedEntityError(
            new IllegalQueryException(nonSearchable + "[" + FAMILY_TEA + ", " + GIVEN_TEA + "]"),
            origin);
    assertEquals(List.of("family", "given"), namedParameters(twoAttributes));
    assertEquals(ATTRIBUTE_NOT_SEARCHABLE, detailOf(twoAttributes));
    assertNotNamed(twoAttributes, "identifier");

    String tooFew = "At least 2 attributes should be mentioned in the search criteria.";

    FhirApiException supplied = trackedEntityError(new IllegalQueryException(tooFew), origin);
    assertEquals(List.of("family", "given"), namedParameters(supplied));
    assertTrue(detailOf(supplied).matches(".*\\b2\\b.*"), "diagnostics name the minimum 2");
    assertNotNamed(supplied, "identifier");

    FhirSearchOrigin noneSupplied =
        new FhirSearchOrigin(
            origin.attributeToParameter(), List.of(), origin.configuredAttributeParameters());
    FhirApiException configured =
        trackedEntityError(new IllegalQueryException(tooFew), noneSupplied);
    assertEquals(List.of("identifier", "family", "given"), namedParameters(configured));
    assertTrue(detailOf(configured).matches(".*\\b2\\b.*"), "diagnostics name the minimum 2");
  }

  @Test
  void badRequestCitingOriginAttributeNamesItsParameter() throws Exception {
    FhirApiException blockedOperator =
        trackedEntityError(
            new BadRequestException(
                "Operators [SW] are blocked for attribute '" + GIVEN_TEA + "'."),
            origin);
    assertEquals(List.of("given"), namedParameters(blockedOperator));
    assertEquals(ATTRIBUTE_VALUE_REJECTED, detailOf(blockedOperator));
    assertNotNamed(blockedOperator, "family", "identifier");

    FhirApiException tooShort =
        trackedEntityError(
            new BadRequestException(
                "At least 3 character(s) should be present in the filter to start a search, but"
                    + " the filter for the tracked entity attribute "
                    + FAMILY_TEA
                    + " doesn't contain enough."),
            origin);
    assertEquals(List.of("family"), namedParameters(tooShort));
    assertEquals(ATTRIBUTE_VALUE_REJECTED, detailOf(tooShort));
    assertNotNamed(tooShort, "given", "identifier");
  }

  @Test
  void residualBadRequestIsNotSupported() throws Exception {
    FhirApiException notTrackerProgram =
        trackedEntityError(
            new BadRequestException("Program specified is not a tracker program: " + PROGRAM),
            FhirSearchOrigin.empty());
    assertNotSupported(notTrackerProgram, "Patient");
    assertFalse(notTrackerProgram.getDiagnostics().contains(PROGRAM));

    assertNotSupported(
        trackedEntityError(
            new BadRequestException(
                "Operators [SW] are blocked for attribute '" + UNMAPPED_TEA + "'."),
            origin),
        "Patient");
    assertNotSupported(
        trackedEntityError(
            new BadRequestException(
                "Operators [SW] are blocked for attribute '" + FAMILY_TEA + "2'."),
            origin),
        "Patient");
    assertNotSupported(
        trackedEntityError(new IllegalQueryException("Query is not valid"), origin), "Patient");
    assertNotSupported(
        trackedEntityError(
            new IllegalQueryException(
                "At least 2 attributes should be mentioned in the search criteria."),
            FhirSearchOrigin.empty()),
        "Patient");

    assertNotSupported(
        enrollmentError(
            new BadRequestException("Program specified is not a tracker program: " + PROGRAM),
            FhirResourceType.ENCOUNTER),
        "Encounter");
    assertNotSupported(
        enrollmentError(
            new IllegalQueryException("Query is not valid"), FhirResourceType.OBSERVATION),
        "Observation");
  }

  @Test
  void badRequestHidingTheSelectedProgramOrTypeIsForbidden() throws Exception {
    TrackedEntityRequestParams byType = new TrackedEntityRequestParams();
    byType.setTrackedEntityType(UID.of(TRACKED_ENTITY_TYPE));
    doThrow(
            new BadRequestException(
                "Tracked entity type is specified but does not exist: " + TRACKED_ENTITY_TYPE))
        .when(trackedEntityAdapter)
        .find(byType, request);
    FhirApiException typeHidden =
        fhirError(() -> reader.findTrackedEntities(byType, request, origin));
    assertEquals(HttpStatus.FORBIDDEN, typeHidden.getStatus());
    assertEquals(IssueType.FORBIDDEN, typeHidden.getIssueType());
    assertEquals(FhirApiException.forbidden().getDiagnostics(), typeHidden.getDiagnostics());

    TrackedEntityRequestParams byProgram = new TrackedEntityRequestParams();
    byProgram.setProgram(UID.of(PROGRAM));
    doThrow(new BadRequestException("Program is specified but does not exist: " + PROGRAM))
        .when(trackedEntityAdapter)
        .find(byProgram, request);
    FhirApiException programHidden =
        fhirError(() -> reader.findTrackedEntities(byProgram, request, origin));
    assertEquals(typeHidden.getDiagnostics(), programHidden.getDiagnostics());
    assertEquals(HttpStatus.FORBIDDEN, programHidden.getStatus());

    EnrollmentRequestParams enrollmentParams = new EnrollmentRequestParams();
    enrollmentParams.setProgram(UID.of(PROGRAM));
    doThrow(new BadRequestException("Program is specified but does not exist: " + PROGRAM))
        .when(enrollmentAdapter)
        .find(enrollmentParams, request);
    EnrollmentResult result =
        reader.findEnrollments(enrollmentParams, request, FhirResourceType.ENCOUNTER);
    assertTrue(result.forbidden());
    assertEquals(List.of(), result.enrollments());

    assertNotSupported(
        trackedEntityError(
            new BadRequestException("Program is specified but does not exist: " + PROGRAM), origin),
        "Patient");
    assertNotSupported(
        enrollmentError(
            new BadRequestException("Program is specified but does not exist: " + PROGRAM),
            FhirResourceType.OBSERVATION),
        "Observation");
  }

  @Test
  void oneDeadlineSpansEveryCallOfAnOperation() throws Exception {
    when(timeout.newDeadline()).thenAnswer(invocation -> deadlineIn(Duration.ofSeconds(10)));
    List<Deadline> seen = new ArrayList<>();
    when(trackedEntityAdapter.find(any(), same(request)))
        .thenAnswer(
            invocation -> {
              seen.add(DeadlineHolder.get());
              return trackedEntityPage(trackedEntity(uid(), TRACKED_ENTITY_TYPE, UPDATED));
            });
    when(enrollmentAdapter.find(any(), same(request)))
        .thenAnswer(
            invocation -> {
              seen.add(DeadlineHolder.get());
              return enrollmentPage();
            });

    TrackedEntityRequestParams patientRead = new TrackedEntityRequestParams();
    EnrollmentRequestParams firstProgram = new EnrollmentRequestParams();
    EnrollmentRequestParams secondProgram = new EnrollmentRequestParams();

    int calls =
        reader.withinDeadline(
            () -> {
              reader.findTrackedEntities(patientRead, request, FhirSearchOrigin.empty());
              reader.findEnrollments(firstProgram, request, FhirResourceType.ENCOUNTER);
              reader.findEnrollments(secondProgram, request, FhirResourceType.OBSERVATION);
              return seen.size();
            });

    assertEquals(3, calls);
    verify(timeout, times(1)).newDeadline();
    verify(trackedEntityAdapter).find(same(patientRead), same(request));
    verify(enrollmentAdapter).find(same(firstProgram), same(request));
    verify(enrollmentAdapter).find(same(secondProgram), same(request));
    assertNotNull(seen.get(0), "the Patient read runs within a deadline");
    assertSame(seen.get(0), seen.get(1), "the first program call shares the Patient deadline");
    assertSame(seen.get(0), seen.get(2), "the second program call shares the Patient deadline");
    assertNull(DeadlineHolder.get(), "the deadline is cleared after the operation");

    IllegalStateException failure = new IllegalStateException("operation failed");
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () ->
                reader.withinDeadline(
                    () -> {
                      assertNotNull(DeadlineHolder.get());
                      throw failure;
                    })));
    assertNull(DeadlineHolder.get(), "the deadline is cleared after a failed operation");

    EnrollmentRequestParams beforeExpiry = new EnrollmentRequestParams();
    EnrollmentRequestParams afterExpiry = new EnrollmentRequestParams();
    TrackedEntityRequestParams readAfterExpiry = new TrackedEntityRequestParams();
    assertThrows(
        DeadlineExceededException.class,
        () ->
            reader.withinDeadline(
                () -> {
                  reader.findEnrollments(beforeExpiry, request, FhirResourceType.IMMUNIZATION);
                  elapse(Duration.ofSeconds(11));
                  assertThrows(DeadlineExceededException.class, reader::checkpoint);
                  assertThrows(
                      DeadlineExceededException.class,
                      () -> reader.findTrackedEntities(readAfterExpiry, request, origin));
                  return reader.findEnrollments(
                      afterExpiry, request, FhirResourceType.IMMUNIZATION);
                }));
    verify(enrollmentAdapter).find(same(beforeExpiry), same(request));
    verify(enrollmentAdapter, never()).find(same(afterExpiry), any());
    verify(trackedEntityAdapter, never()).find(same(readAfterExpiry), any());
    assertNull(DeadlineHolder.get(), "the deadline is cleared after it expired");

    doReturn(null).when(timeout).newDeadline();
    assertNull(
        reader.withinDeadline(DeadlineHolder::get),
        "a disabled timeout runs the operation without a deadline");
  }

  @Test
  void existingDeadlineIsReusedAndNotCleared() throws Exception {
    Deadline existing = deadlineIn(Duration.ofSeconds(30));
    DeadlineHolder.set(existing);
    List<Deadline> seen = new ArrayList<>();
    EnrollmentRequestParams params = new EnrollmentRequestParams();
    when(enrollmentAdapter.find(params, request))
        .thenAnswer(
            invocation -> {
              seen.add(DeadlineHolder.get());
              return enrollmentPage();
            });

    EnrollmentResult result =
        reader.withinDeadline(
            () -> reader.findEnrollments(params, request, FhirResourceType.ENCOUNTER));

    assertFalse(result.forbidden());
    assertEquals(1, seen.size());
    assertSame(existing, seen.get(0), "the call runs within the existing deadline");
    assertSame(existing, DeadlineHolder.get(), "the existing deadline stays in place");

    assertThrows(
        IllegalStateException.class,
        () ->
            reader.withinDeadline(
                () -> {
                  throw new IllegalStateException("operation failed");
                }));
    assertSame(existing, DeadlineHolder.get(), "a failed operation leaves the deadline in place");
    verify(timeout, never()).newDeadline();
  }

  private Deadline deadlineIn(Duration budget) {
    return Deadline.in(budget, () -> nanos);
  }

  private void elapse(Duration elapsed) {
    nanos += elapsed.toNanos();
  }

  /** Stubs the tracked entity adapter to throw {@code exception} and returns the FHIR error. */
  private FhirApiException trackedEntityError(Exception exception, FhirSearchOrigin searchOrigin)
      throws Exception {
    TrackedEntityRequestParams params = new TrackedEntityRequestParams();
    doThrow(exception).when(trackedEntityAdapter).find(params, request);
    return fhirError(() -> reader.findTrackedEntities(params, request, searchOrigin));
  }

  /** Stubs the enrollment adapter to throw {@code exception} and returns the FHIR error. */
  private FhirApiException enrollmentError(Exception exception, FhirResourceType type)
      throws Exception {
    EnrollmentRequestParams params = new EnrollmentRequestParams();
    doThrow(exception).when(enrollmentAdapter).find(params, request);
    return fhirError(() -> reader.findEnrollments(params, request, type));
  }

  private static FhirApiException fhirError(Executable executable) {
    return assertThrows(FhirApiException.class, executable);
  }

  /** Asserts a {@code 400 invalid} error and returns the parameter names its diagnostics name. */
  private static List<String> namedParameters(FhirApiException exception) {
    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    assertEquals(IssueType.INVALID, exception.getIssueType());
    return List.of(
        invalidDiagnostics(exception).group(1).split(Pattern.quote(PARAMETER_SEPARATOR)));
  }

  private static String detailOf(FhirApiException exception) {
    return invalidDiagnostics(exception).group(2);
  }

  private static Matcher invalidDiagnostics(FhirApiException exception) {
    Matcher matcher = INVALID_DIAGNOSTICS.matcher(exception.getDiagnostics());
    assertTrue(
        matcher.matches(), () -> "diagnostics name the parameter: " + exception.getDiagnostics());
    return matcher;
  }

  /** Asserts that the diagnostics mention none of {@code parameters} and no attribute UID. */
  private static void assertNotNamed(FhirApiException exception, String... parameters) {
    List<String> absent = new ArrayList<>(List.of(parameters));
    absent.addAll(List.of(FAMILY_TEA, GIVEN_TEA, IDENTIFIER_TEA));
    absent.forEach(token -> assertFalse(exception.getDiagnostics().contains(token), token));
  }

  private static void assertNotSupported(FhirApiException exception, String fhirType) {
    assertEquals(HttpStatus.NOT_IMPLEMENTED, exception.getStatus());
    assertEquals(IssueType.NOTSUPPORTED, exception.getIssueType());
    assertEquals(
        "The configured mapping for " + fhirType + " cannot be used", exception.getDiagnostics());
  }

  private static FilteredPage<TrackedEntity> trackedEntityPage(TrackedEntity... trackedEntities) {
    return new FilteredPage<>(
        Page.withoutPager("trackedEntities", List.of(trackedEntities)), Fields.all());
  }

  private static FilteredPage<Enrollment> enrollmentPage(Enrollment... enrollments) {
    return new FilteredPage<>(Page.withoutPager("enrollments", List.of(enrollments)), Fields.all());
  }
}
