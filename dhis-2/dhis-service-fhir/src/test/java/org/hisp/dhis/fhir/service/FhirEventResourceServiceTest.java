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

import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_CODE;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_DISPLAY;
import static org.hisp.dhis.fhir.FhirTestFixtures.ENCOUNTER_CLASS_SYSTEM;
import static org.hisp.dhis.fhir.FhirTestFixtures.OCCURRED;
import static org.hisp.dhis.fhir.FhirTestFixtures.UPDATED;
import static org.hisp.dhis.fhir.FhirTestFixtures.enrollment;
import static org.hisp.dhis.fhir.FhirTestFixtures.entries;
import static org.hisp.dhis.fhir.FhirTestFixtures.event;
import static org.hisp.dhis.fhir.FhirTestFixtures.resolved;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.ENCOUNTER;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.IMMUNIZATION;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.ENCOUNTER_CLASS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.deadline.Deadline;
import org.hisp.dhis.deadline.DeadlineExceededException;
import org.hisp.dhis.deadline.DeadlineHolder;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.feedback.ForbiddenException;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.FhirTestFixtures.Entry;
import org.hisp.dhis.fhir.mapper.FhirEncounterMapper;
import org.hisp.dhis.fhir.mapper.FhirImmunizationMapper;
import org.hisp.dhis.fhir.mapper.FhirLogicalId;
import org.hisp.dhis.fhir.mapper.FhirObservationMapper;
import org.hisp.dhis.fhir.mapper.FhirValueConverter;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.search.FhirSearchParameters;
import org.hisp.dhis.fhir.search.FhirSearchTranslator;
import org.hisp.dhis.setting.SystemSettings;
import org.hisp.dhis.setting.SystemSettingsProvider;
import org.hisp.dhis.tracker.export.fieldfiltering.Fields;
import org.hisp.dhis.tracker.export.timeout.TrackerExportTimeout;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.EnrollmentRequestParams;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.FhirEnrollmentExportAdapter;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.FhirTrackedEntityExportAdapter;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.FilteredPage;
import org.hisp.dhis.webapi.controller.tracker.view.Page;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests that {@link FhirEventResourceService} aggregates event-derived reads and searches over
 * several candidate programs, checks a read in the order usable mapping, parameters, id syntax,
 * export call, and runs each read and search within one deadline that starts before mapping
 * resolution and stops the operation once it is spent.
 */
@ExtendWith(MockitoExtension.class)
class FhirEventResourceServiceTest {
  private static final Duration BUDGET = Duration.ofSeconds(10);

  private static final Duration OVER_BUDGET = Duration.ofSeconds(11);

  private static final String TET = "fhirTeType1";

  private static final String TE = "fhirPerson1";

  private static final String P1 = "fhirProgrm1";

  private static final String P2 = "fhirProgrm2";

  private static final String P3 = "fhirProgrm3";

  private static final String S1 = "fhirStage01";

  private static final String S2 = "fhirStage02";

  private static final String S3 = "fhirStage03";

  private static final String ENR = "fhirEnroll1";

  private static final String EVT = "fhirEvent01";

  private static final String ENCOUNTER_ID = FhirLogicalId.encounter(ENR, EVT).compose();

  private static final String MALFORMED_ID = "not-a-valid-id";

  @Mock private FhirTrackedEntityExportAdapter teAdapter;

  @Mock private FhirEnrollmentExportAdapter enrollmentAdapter;

  @Mock private TrackerExportTimeout timeout;

  @Mock private SystemSettingsProvider settingsProvider;

  @Mock private FhirResourceMappingService mappingService;

  /** Test clock read by every deadline that {@link TrackerExportTimeout#newDeadline()} returns. */
  private long nanos = TimeUnit.SECONDS.toNanos(1_000);

  private ResolvedMapping m1;

  private ResolvedMapping m2;

  private ResolvedMapping m3;

  /** An enrollment of {@link #TE} in {@link #P2} holding one completed event on {@link #S2}. */
  private Enrollment enrollment;

  private FhirEventResourceService service;

  @BeforeEach
  void setUp() {
    lenient().when(settingsProvider.getCurrentSettings()).thenReturn(SystemSettings.of(Map.of()));
    lenient()
        .when(timeout.newDeadline())
        .thenAnswer(invocation -> Deadline.in(BUDGET, () -> nanos));

    FhirTrackerReader reader = new FhirTrackerReader(teAdapter, enrollmentAdapter, timeout);
    FhirSearchParameters parameters = new FhirSearchParameters(settingsProvider);
    FhirSearchTranslator translator = new FhirSearchTranslator(parameters);
    FhirValueConverter converter = new FhirValueConverter();
    service =
        new FhirEventResourceService(
            mappingService,
            parameters,
            translator,
            reader,
            new FhirEncounterMapper(converter),
            new FhirImmunizationMapper(converter),
            new FhirObservationMapper(converter));

    m1 = encounterMapping(P1, S1);
    m2 = encounterMapping(P2, S2);
    m3 = encounterMapping(P3, S3);
    enrollment =
        enrollment(ENR, TE, P2, event(EVT, S2, EventStatus.COMPLETED, OCCURRED, null, UPDATED));
  }

  @AfterEach
  void clearDeadline() {
    DeadlineHolder.clear();
  }

  @Test
  void readAndSearchAggregationRules() throws Exception {
    when(mappingService.resolve(ENCOUNTER)).thenReturn(List.of(m1, m2));
    when(mappingService.resolve(IMMUNIZATION)).thenReturn(List.of());

    readWithoutUsableMappingIsNotSupported();
    readWithUnsupportedParameterIsInvalidBeforeIdSyntax();
    readWithMalformedIdIsNotFoundWithoutExportCall();
    readForbiddenInEveryProgramIsForbidden();
    readFromReadableProgramReturnsResource();
    readAbsentFromReadableProgramIsNotFound();
    searchForbiddenInEveryProgramIsForbidden();
    searchWithMixedAccessReturnsResourcesOfReadablePrograms();
    searchOfReadableProgramsWithoutDataReturnsEmptyBundle();

    assertNull(DeadlineHolder.get(), "every operation clears its deadline");
  }

  @Test
  void deadlineStartsBeforeMappingResolution() {
    List<Deadline> heldDuringResolution = new ArrayList<>();
    when(mappingService.resolve(ENCOUNTER))
        .thenAnswer(
            invocation -> {
              heldDuringResolution.add(DeadlineHolder.get());
              elapse(OVER_BUDGET);
              return List.of(m1, m2);
            });

    assertThrows(
        DeadlineExceededException.class,
        () -> service.read(ENCOUNTER, ENCOUNTER_ID, readRequest()));
    assertNull(DeadlineHolder.get(), "the read clears its deadline");
    assertThrows(DeadlineExceededException.class, () -> service.search(ENCOUNTER, searchRequest()));
    assertNull(DeadlineHolder.get(), "the search clears its deadline");

    assertEquals(2, heldDuringResolution.size());
    heldDuringResolution.forEach(
        deadline -> assertNotNull(deadline, "a deadline is held while mappings are resolved"));
    InOrder inOrder = inOrder(timeout, mappingService);
    inOrder.verify(timeout).newDeadline();
    inOrder.verify(mappingService).resolve(ENCOUNTER);
    inOrder.verify(timeout).newDeadline();
    inOrder.verify(mappingService).resolve(ENCOUNTER);
    verifyNoInteractions(enrollmentAdapter, teAdapter);
  }

  @Test
  void multiProgramOperationStopsWhenBudgetIsSpent() throws Exception {
    when(mappingService.resolve(ENCOUNTER)).thenReturn(List.of(m1, m2, m3));
    AtomicInteger calls = new AtomicInteger();
    when(enrollmentAdapter.find(any(), any()))
        .thenAnswer(
            invocation -> {
              if (calls.incrementAndGet() == 2) {
                elapse(OVER_BUDGET);
              }
              return page();
            });

    assertThrows(DeadlineExceededException.class, () -> service.search(ENCOUNTER, searchRequest()));

    assertEquals(2, calls.get(), "no program is requested after the budget is spent");
    verify(enrollmentAdapter, times(2)).find(any(), any());
    verify(timeout, times(1)).newDeadline();
    assertNull(DeadlineHolder.get(), "the search clears its deadline");
    verifyNoInteractions(teAdapter);
  }

  /** A type without a usable mapping answers 501 whatever its parameters and id. */
  private void readWithoutUsableMappingIsNotSupported() {
    FhirApiException exception =
        assertThrows(
            FhirApiException.class,
            () -> service.read(IMMUNIZATION, MALFORMED_ID, request("foo", "bar")));

    assertError(HttpStatus.NOT_IMPLEMENTED, IssueType.NOTSUPPORTED, exception);
    verifyNoInteractions(enrollmentAdapter, teAdapter);
  }

  /** A mapped type answers 400 naming an unsupported parameter, even for a malformed id. */
  private void readWithUnsupportedParameterIsInvalidBeforeIdSyntax() {
    FhirApiException exception =
        assertThrows(
            FhirApiException.class,
            () -> service.read(ENCOUNTER, MALFORMED_ID, request("foo", "bar")));

    assertError(HttpStatus.BAD_REQUEST, IssueType.INVALID, exception);
    assertTrue(
        exception.getDiagnostics().startsWith("Invalid parameter 'foo'"),
        exception.getDiagnostics());
    verifyNoInteractions(enrollmentAdapter, teAdapter);
  }

  /** A malformed id answers 404 without any export call. */
  private void readWithMalformedIdIsNotFoundWithoutExportCall() {
    for (String id : List.of(MALFORMED_ID, ENR, ENCOUNTER_ID + "-" + S2)) {
      FhirApiException exception =
          assertThrows(FhirApiException.class, () -> service.read(ENCOUNTER, id, readRequest()));

      assertError(HttpStatus.NOT_FOUND, IssueType.NOTFOUND, exception);
    }
    verifyNoInteractions(enrollmentAdapter, teAdapter);
  }

  /** A read forbidden in every candidate program answers 403 with fixed diagnostics. */
  private void readForbiddenInEveryProgramIsForbidden() throws Exception {
    reset(enrollmentAdapter);
    forbid(P1);
    forbid(P2);

    FhirApiException exception =
        assertThrows(
            FhirApiException.class, () -> service.read(ENCOUNTER, ENCOUNTER_ID, readRequest()));

    assertError(HttpStatus.FORBIDDEN, IssueType.FORBIDDEN, exception);
    assertEquals(FhirApiException.forbidden().getDiagnostics(), exception.getDiagnostics());
    verify(enrollmentAdapter).find(forProgram(P1), any());
    verify(enrollmentAdapter).find(forProgram(P2), any());
  }

  /** A read returns the resource of a readable program even when another program is forbidden. */
  private void readFromReadableProgramReturnsResource() throws Exception {
    reset(enrollmentAdapter);
    forbid(P1);
    answer(P2, enrollment);

    Resource resource = service.read(ENCOUNTER, ENCOUNTER_ID, readRequest());

    Encounter encounter = assertInstanceOf(Encounter.class, resource);
    assertEquals(ENCOUNTER_ID, encounter.getIdElement().getIdPart());
    assertEquals("Patient/" + TE, encounter.getSubject().getReference());
    FhirR4Validation.assertValid(encounter);
    verify(enrollmentAdapter)
        .find(
            argThat(
                params ->
                    params != null
                        && UID.of(P2).equals(params.getProgram())
                        && Set.of(UID.of(ENR)).equals(params.getEnrollments())),
            any());
  }

  /** A read answers 404 when no readable program holds the resource. */
  private void readAbsentFromReadableProgramIsNotFound() throws Exception {
    reset(enrollmentAdapter);
    forbid(P1);
    answer(P2);

    FhirApiException exception =
        assertThrows(
            FhirApiException.class, () -> service.read(ENCOUNTER, ENCOUNTER_ID, readRequest()));

    assertError(HttpStatus.NOT_FOUND, IssueType.NOTFOUND, exception);
  }

  /** A search forbidden in every candidate program answers 403 with fixed diagnostics. */
  private void searchForbiddenInEveryProgramIsForbidden() throws Exception {
    reset(enrollmentAdapter);
    forbid(P1);
    forbid(P2);

    FhirApiException exception =
        assertThrows(FhirApiException.class, () -> service.search(ENCOUNTER, searchRequest()));

    assertError(HttpStatus.FORBIDDEN, IssueType.FORBIDDEN, exception);
    assertEquals(FhirApiException.forbidden().getDiagnostics(), exception.getDiagnostics());
  }

  /** A search returns the resources of the readable programs and nothing of forbidden ones. */
  private void searchWithMixedAccessReturnsResourcesOfReadablePrograms() throws Exception {
    reset(enrollmentAdapter);
    forbid(P1);
    answer(P2, enrollment);

    Bundle bundle = service.search(ENCOUNTER, searchRequest());

    assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType());
    assertEquals(1, bundle.getTotal());
    assertEquals(1, bundle.getEntry().size());
    BundleEntryComponent entry = bundle.getEntryFirstRep();
    assertEquals(ENCOUNTER_ID, entry.getResource().getIdElement().getIdPart());
    assertTrue(
        entry.getFullUrl().endsWith("/api/fhir/Encounter/" + ENCOUNTER_ID), entry.getFullUrl());
    String json = FhirR4Validation.encode(bundle);
    assertFalse(json.contains(P1), "the Bundle does not mention the forbidden program");
    FhirR4Validation.assertValid(bundle);
    verify(enrollmentAdapter)
        .find(
            argThat(
                params ->
                    params != null
                        && UID.of(P2).equals(params.getProgram())
                        && UID.of(TE).equals(params.getTrackedEntity())),
            any());
  }

  /** A search of readable programs without matching data returns an empty searchset. */
  private void searchOfReadableProgramsWithoutDataReturnsEmptyBundle() throws Exception {
    reset(enrollmentAdapter);
    answer(P1);
    answer(P2);

    Bundle bundle = service.search(ENCOUNTER, searchRequest());

    assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType());
    assertTrue(bundle.getEntry().isEmpty());
    assertTrue(bundle.hasTotal());
    assertEquals(0, bundle.getTotal());
  }

  /** Makes the enrollment export deny access to the program. */
  private void forbid(String program) throws Exception {
    when(enrollmentAdapter.find(forProgram(program), any()))
        .thenThrow(new ForbiddenException("User has no data read access to program: " + program));
  }

  /** Makes the enrollment export return the enrollments for the program. */
  private void answer(String program, Enrollment... enrollments) throws Exception {
    when(enrollmentAdapter.find(forProgram(program), any())).thenReturn(page(enrollments));
  }

  private static EnrollmentRequestParams forProgram(String program) {
    return argThat(params -> params != null && UID.of(program).equals(params.getProgram()));
  }

  private static FilteredPage<Enrollment> page(Enrollment... enrollments) {
    return new FilteredPage<>(Page.withoutPager("enrollments", List.of(enrollments)), Fields.all());
  }

  /** Returns an ENCOUNTER mapping of the program and stage with an ambulatory class. */
  private static ResolvedMapping encounterMapping(String program, String stage) {
    return resolved(
        ENCOUNTER,
        TET,
        program,
        stage,
        entries(
            Entry.constant(
                ENCOUNTER_CLASS,
                ENCOUNTER_CLASS_SYSTEM,
                ENCOUNTER_CLASS_CODE,
                ENCOUNTER_CLASS_DISPLAY)),
        Map.of());
  }

  private static MockHttpServletRequest readRequest() {
    return new MockHttpServletRequest();
  }

  private static MockHttpServletRequest searchRequest() {
    return request(FhirSearchParameters.PATIENT, TE);
  }

  private static MockHttpServletRequest request(String name, String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter(name, value);
    return request;
  }

  private void elapse(Duration elapsed) {
    nanos += elapsed.toNanos();
  }

  private static void assertError(
      HttpStatus status, IssueType issueType, FhirApiException exception) {
    assertEquals(status, exception.getStatus(), exception.getDiagnostics());
    assertEquals(issueType, exception.getIssueType(), exception.getDiagnostics());
  }
}
