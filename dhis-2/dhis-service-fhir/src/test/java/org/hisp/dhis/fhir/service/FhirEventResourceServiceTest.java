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
import static org.hisp.dhis.fhir.mapping.FhirSourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.deadline.*;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.feedback.ForbiddenException;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.FhirR4Validation;
import org.hisp.dhis.fhir.mapper.*;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.search.*;
import org.hisp.dhis.setting.*;
import org.hisp.dhis.tracker.export.fieldfiltering.Fields;
import org.hisp.dhis.tracker.export.timeout.TrackerExportTimeout;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.*;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.FhirTrackedEntityExportAdapter;
import org.hisp.dhis.webapi.controller.tracker.view.*;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/** Tests {@link FhirEventResourceService} and the {@link FhirPatientService} operations on it. */
@ExtendWith(MockitoExtension.class)
class FhirEventResourceServiceTest {
  private static final Duration BUDGET = Duration.ofSeconds(10);
  private static final String TET = "fhirTeType1";
  private static final String TE = "fhirPerson1";
  private static final String P1 = "fhirProgrm1";
  private static final String P2 = "fhirProgrm2";
  private static final String P3 = "fhirProgrm3";
  private static final String S1 = "fhirStage01";
  private static final String S2 = "fhirStage02";
  private static final String S3 = "fhirStage03";
  private static final String ENR = "fhirEnroll1";
  private static final String ENR2 = "fhirEnroll2";
  private static final String EVT = "fhirEvent01";
  private static final String EVT2 = "fhirEvent02";
  private static final String EVT3 = "fhirEvent03";
  private static final String DE_A = "fhirDataEl1";
  private static final String DE_B = "fhirDataEl2";
  private static final String GENDER_TEA = "fhirGender1";
  private static final String ENCOUNTER_ID = FhirLogicalId.encounter(ENR, EVT).compose();
  private static final String MALFORMED_ID = "not-a-valid-id";
  @Mock private FhirTrackedEntityExportAdapter teAdapter;
  @Mock private FhirEnrollmentExportAdapter enrollmentAdapter;
  @Mock private TrackerExportTimeout timeout;
  @Mock private SystemSettingsProvider settingsProvider;
  @Mock private FhirResourceMappingService mappingService;
  private final List<Deadline> seen = new ArrayList<>();
  private long nanos = TimeUnit.SECONDS.toNanos(1_000);
  private final ResolvedMapping m1 = encounterMapping(TET, P1, S1);
  private final ResolvedMapping m2 = encounterMapping(TET, P2, S2);
  private final ResolvedMapping m3 = encounterMapping(TET, P3, S3);
  private final Enrollment enrollment = enrollment(ENR, TE, P2, completed(EVT, S2));
  private FhirEncounterMapper encounterMapper;
  private FhirEventResourceService service;
  private FhirPatientService patientService;

  @BeforeEach
  void setUp() {
    lenient().when(settingsProvider.getCurrentSettings()).thenReturn(SystemSettings.of(Map.of()));
    lenient().when(timeout.newDeadline()).thenAnswer(i -> Deadline.in(BUDGET, () -> nanos));
    FhirSearchParameters parameters = new FhirSearchParameters(settingsProvider);
    FhirSearchTranslator translator = new FhirSearchTranslator(parameters);
    FhirTrackerReader reader = new FhirTrackerReader(teAdapter, enrollmentAdapter, timeout);
    FhirValueConverter converter = new FhirValueConverter();
    encounterMapper = spy(new FhirEncounterMapper(converter));
    service =
        new FhirEventResourceService(
            mappingService,
            parameters,
            translator,
            reader,
            encounterMapper,
            new FhirImmunizationMapper(converter),
            new FhirObservationMapper(converter));
    FhirPatientMapper mapper = new FhirPatientMapper(converter);
    patientService =
        new FhirPatientService(mappingService, parameters, translator, reader, mapper, service);
  }

  @AfterEach
  void clearDeadline() {
    DeadlineHolder.clear();
  }

  @Test
  void readAndSearchAggregationRules() throws Exception {
    when(mappingService.resolve(ENCOUNTER)).thenReturn(List.of(m1, m2));
    when(mappingService.resolve(IMMUNIZATION)).thenReturn(List.of());
    FhirApiException unmapped = readError(IMMUNIZATION, MALFORMED_ID, "foo", "bar");
    assertError(HttpStatus.NOT_IMPLEMENTED, IssueType.NOTSUPPORTED, unmapped);
    FhirApiException invalid = readError(ENCOUNTER, MALFORMED_ID, "foo", "bar");
    assertError(HttpStatus.BAD_REQUEST, IssueType.INVALID, invalid);
    assertTrue(invalid.getDiagnostics().startsWith("Invalid parameter 'foo'"));
    for (String id : List.of(MALFORMED_ID, ENR, ENCOUNTER_ID + "-" + S2)) {
      assertError(HttpStatus.NOT_FOUND, IssueType.NOTFOUND, readError(ENCOUNTER, id));
    }
    verifyNoInteractions(enrollmentAdapter, teAdapter);
    forbid(P1);
    forbid(P2);
    FhirApiException readForbidden = readError(ENCOUNTER, ENCOUNTER_ID);
    assertError(HttpStatus.FORBIDDEN, IssueType.FORBIDDEN, readForbidden);
    assertEquals(FhirApiException.forbidden().getDiagnostics(), readForbidden.getDiagnostics());
    verify(enrollmentAdapter).find(forProgram(P1), any());
    verify(enrollmentAdapter).find(forProgram(P2), any());
    FhirApiException searchForbidden =
        assertThrows(FhirApiException.class, () -> service.search(ENCOUNTER, searchRequest()));
    assertError(HttpStatus.FORBIDDEN, IssueType.FORBIDDEN, searchForbidden);
    assertEquals(FhirApiException.forbidden().getDiagnostics(), searchForbidden.getDiagnostics());
    reset(enrollmentAdapter);
    forbid(P1);
    answer(P2, enrollment);
    Encounter encounter =
        assertInstanceOf(Encounter.class, service.read(ENCOUNTER, ENCOUNTER_ID, request()));
    assertEquals(ENCOUNTER_ID, encounter.getIdElement().getIdPart());
    assertEquals("Patient/" + TE, encounter.getSubject().getReference());
    FhirR4Validation.assertValid(encounter);
    verify(enrollmentAdapter)
        .find(forProgram(P2, params -> Set.of(UID.of(ENR)).equals(params.getEnrollments())), any());
    Bundle bundle = service.search(ENCOUNTER, searchRequest());
    assertEntries(bundle, "Encounter/" + ENCOUNTER_ID);
    assertFalse(FhirR4Validation.encode(bundle).contains(P1));
    verify(enrollmentAdapter)
        .find(forProgram(P2, params -> UID.of(TE).equals(params.getTrackedEntity())), any());
    reset(enrollmentAdapter);
    forbid(P1);
    answer(P2);
    assertError(HttpStatus.NOT_FOUND, IssueType.NOTFOUND, readError(ENCOUNTER, ENCOUNTER_ID));
    assertEntries(service.search(ENCOUNTER, searchRequest()));
    assertNull(DeadlineHolder.get());
  }

  @Test
  void deadlineStartsBeforeMappingResolution() {
    when(mappingService.resolve(ENCOUNTER)).thenAnswer(i -> recorded(spent(List.of(m1, m2))));
    assertExpired(() -> service.read(ENCOUNTER, ENCOUNTER_ID, request()));
    assertNull(DeadlineHolder.get());
    assertExpired(() -> service.search(ENCOUNTER, searchRequest()));
    assertNull(DeadlineHolder.get());
    assertEquals(2, seen.size());
    assertFalse(seen.contains(null), "a deadline is held while mappings resolve");
    verify(timeout, times(2)).newDeadline();
    verifyNoInteractions(enrollmentAdapter, teAdapter);
  }

  @Test
  void multiProgramOperationStopsWhenBudgetIsSpent() throws Exception {
    when(mappingService.resolve(ENCOUNTER)).thenReturn(List.of(m1, m2, m3));
    when(enrollmentAdapter.find(any(), any())).thenReturn(page()).thenAnswer(i -> spent(page()));
    assertExpired(() -> service.search(ENCOUNTER, searchRequest()));
    verify(enrollmentAdapter, times(2)).find(any(), any());
    verify(timeout, times(1)).newDeadline();
    assertNull(DeadlineHolder.get());
    verifyNoInteractions(teAdapter);
  }

  @Test
  void readAndSearchCheckTheDeadlineBeforeAnswering() throws Exception {
    when(mappingService.resolve(ENCOUNTER)).thenReturn(List.of(m2));
    when(enrollmentAdapter.find(forProgram(P2), any()))
        .thenReturn(page(enrollment))
        .thenAnswer(i -> spent(page()))
        .thenAnswer(
            i -> {
              throw spent(denied(P2));
            })
        .thenReturn(page(enrollment));
    doAnswer(i -> spent(i.callRealMethod()))
        .doCallRealMethod()
        .when(encounterMapper)
        .map(any(), any(), any());
    Executable read = () -> service.read(ENCOUNTER, ENCOUNTER_ID, request());
    assertThrows(DeadlineExceededException.class, read, "spent mapping the event: no 200");
    assertThrows(DeadlineExceededException.class, read, "spent by an empty export: no 404");
    assertThrows(DeadlineExceededException.class, read, "spent by a forbidden export: no 403");
    assertExpired(() -> service.search(ENCOUNTER, expiringOnLinks("patient", TE)));
    when(mappingService.resolve(PATIENT)).thenReturn(List.of(patientMapping()));
    when(mappingService.resolveAll()).thenReturn(List.of(patientMapping()));
    FilteredPage<TrackedEntity> patient = page(trackedEntity(TE, TET, UPDATED));
    when(teAdapter.find(any(), any())).thenAnswer(i -> spent(patient)).thenReturn(patient);
    assertExpired(() -> patientService.read(TE, request()));
    assertExpired(() -> patientService.everything(TE, expiringOnLinks()));
    verify(teAdapter, times(2)).find(any(), any());
    verify(enrollmentAdapter, times(4)).find(any(), any());
    assertNull(DeadlineHolder.get());
  }

  @Test
  void everythingRunsWithinOneDeadlineOverMappingsResolvedOnce() throws Exception {
    ResolvedMapping otherType = encounterMapping("fhirTeType2", P3, S3);
    when(mappingService.resolveAll())
        .thenReturn(List.of(patientMapping(), m2, otherType, observationMapping(), m1));
    when(teAdapter.find(any(), any()))
        .thenAnswer(i -> recorded(page(trackedEntity(TE, TET, UPDATED))));
    Event observed = completed(EVT2, S1, dataValue(DE_A, "12"));
    Enrollment first = enrollment(ENR, TE, P1, observed, completed(EVT, S1));
    when(enrollmentAdapter.find(forProgram(P1), any())).thenAnswer(i -> recorded(page(first)));
    Enrollment other = enrollment(ENR2, TE, P2, completed(EVT3, S2));
    when(enrollmentAdapter.find(forProgram(P2), any())).thenAnswer(i -> recorded(page(other)));
    Bundle bundle = patientService.everything(TE, request());
    String second = ENR + "-" + EVT2;
    assertEntries(
        bundle,
        "Patient/" + TE,
        "Encounter/" + ENCOUNTER_ID,
        "Encounter/" + second,
        "Observation/" + second + "-" + DE_A,
        "Encounter/" + ENR2 + "-" + EVT3);
    assertEquals(1, bundle.getLink().size());
    String self = bundle.getLink(Bundle.LINK_SELF).getUrl();
    assertEquals("http://localhost/api/fhir/Patient/" + TE + "/$everything", self);
    assertNotNull(seen.get(0));
    assertEquals(Collections.nCopies(3, seen.get(0)), seen);
    verify(timeout, times(1)).newDeadline();
    verify(mappingService, times(1)).resolveAll();
    verify(mappingService, never()).resolve(any());
    verify(enrollmentAdapter, times(2)).find(any(), any());
    assertNull(DeadlineHolder.get());
  }

  @Test
  void searchWithUnmappedGenderReturnsEmptySearchsetWithoutExportCall() throws Exception {
    when(mappingService.resolve(PATIENT)).thenReturn(List.of(patientMapping()));
    Bundle empty = patientService.search(request(FhirSearchParameters.GENDER, "unknown"));
    assertEquals(Bundle.BundleType.SEARCHSET, empty.getType());
    assertTrue(empty.getEntry().isEmpty());
    assertFalse(empty.hasTotal());
    assertEquals(1, empty.getLink().size());
    assertNotNull(empty.getLink(Bundle.LINK_SELF));
    verifyNoInteractions(teAdapter, enrollmentAdapter);
    Attribute male = attribute(GENDER_TEA, ValueType.TEXT, "M");
    when(teAdapter.find(any(), any())).thenReturn(page(trackedEntity(TE, TET, UPDATED, male)));
    Bundle found = patientService.search(request(FhirSearchParameters.GENDER, "male"));
    assertEquals(List.of("Patient/" + TE), references(found));
    verify(teAdapter)
        .find(argThat(p -> p.getFilter() != null && p.getFilter().contains(GENDER_TEA)), any());
  }

  @Test
  void idsAndFullUrlsAreUniqueWithinBundle() throws Exception {
    stubObservationSearch();
    Bundle bundle = service.search(OBSERVATION, searchRequest());
    List<String> expected = new ArrayList<>();
    for (String event : List.of(ENR + "-" + EVT, ENR + "-" + EVT2, ENR2 + "-" + EVT3)) {
      expected.add("Observation/" + event + "-" + DE_A);
      expected.add("Observation/" + event + "-" + DE_B);
    }
    assertEntries(bundle, expected.toArray(String[]::new));
  }

  @Test
  void observationCodeSelectsEntriesAndUnmatchedCodeMakesNoExportCall() throws Exception {
    stubObservationSearch();
    String height = LOINC_SYSTEM + "|" + LOINC_BODY_HEIGHT_CODE;
    Bundle matched = service.search(OBSERVATION, request("patient", TE, "code", height));
    String[] events = {ENR + "-" + EVT, ENR + "-" + EVT2, ENR2 + "-" + EVT3};
    assertEntries(
        matched,
        Stream.of(events).map(e -> "Observation/" + e + "-" + DE_A).toArray(String[]::new));
    reset(enrollmentAdapter);
    String unknown = LOINC_SYSTEM + "|unknown";
    assertEntries(service.search(OBSERVATION, request("patient", TE, "code", unknown)));
    verifyNoInteractions(enrollmentAdapter);
  }

  @Test
  void idSearchMapsOnlyTheRequestedEvents() throws Exception {
    when(mappingService.resolve(ENCOUNTER)).thenReturn(List.of(m2));
    Event other = completed(EVT2, S2);
    answer(P2, enrollment(ENR, TE, P2, other, completed(EVT, S2), completed(EVT3, S2)));
    Bundle bundle = service.search(ENCOUNTER, request("_id", ENCOUNTER_ID));
    assertEntries(bundle, "Encounter/" + ENCOUNTER_ID);
    verify(encounterMapper, times(1)).map(any(), any(), any());
    verify(encounterMapper).map(any(), argThat(e -> EVT.equals(e.getEvent().getValue())), any());
  }

  @Test
  void manyStageSearchIndexesMappingsByStage() throws Exception {
    List<ResolvedMapping> mappings =
        Stream.of(S1, S3, "fhirStage04").map(s -> spy(encounterMapping(TET, P1, s))).toList();
    when(mappingService.resolve(ENCOUNTER)).thenReturn(mappings);
    Event last = completed("fhirEvent04", "fhirStage04");
    Event[] events = {last, completed(EVT3, S3), completed(EVT2, S2), completed(EVT, S1)};
    answer(P1, enrollment(ENR, TE, P1, events));
    assertEntries(
        service.search(ENCOUNTER, searchRequest()),
        "Encounter/" + ENCOUNTER_ID,
        "Encounter/" + ENR + "-" + EVT3,
        "Encounter/" + ENR + "-fhirEvent04");
    mappings.forEach(mapping -> verify(mapping, atMost(2)).programStage());
  }

  private FhirApiException readError(FhirResourceType type, String id, String... query) {
    return assertThrows(FhirApiException.class, () -> service.read(type, id, request(query)));
  }

  private void forbid(String program) throws Exception {
    when(enrollmentAdapter.find(forProgram(program), any())).thenThrow(denied(program));
  }

  private void answer(String program, Enrollment... enrollments) throws Exception {
    when(enrollmentAdapter.find(forProgram(program), any())).thenReturn(page(enrollments));
  }

  private void stubObservationSearch() throws Exception {
    when(mappingService.resolve(OBSERVATION)).thenReturn(List.of(observationMapping()));
    when(mappingService.resolve(ENCOUNTER)).thenReturn(List.of(m1));
    DataValue[] observed = {dataValue(DE_A, "172.5"), dataValue(DE_B, "70")};
    Enrollment first =
        enrollment(ENR, TE, P1, completed(EVT2, S1, observed), completed(EVT, S1, observed));
    answer(P1, first, enrollment(ENR2, TE, P1, completed(EVT3, S1, observed)));
  }

  private static ForbiddenException denied(String program) {
    return new ForbiddenException("User has no data read access to program: " + program);
  }

  private static Event completed(String uid, String stage, DataValue... values) {
    return event(uid, stage, EventStatus.COMPLETED, OCCURRED, null, UPDATED, values);
  }

  private static EnrollmentRequestParams forProgram(String program) {
    return forProgram(program, params -> true);
  }

  private static EnrollmentRequestParams forProgram(
      String program, Predicate<EnrollmentRequestParams> matches) {
    return argThat(p -> p != null && UID.of(program).equals(p.getProgram()) && matches.test(p));
  }

  @SafeVarargs
  private static <T> FilteredPage<T> page(T... items) {
    return new FilteredPage<>(Page.withoutPager("items", List.of(items)), Fields.all());
  }

  private static List<String> references(Bundle bundle) {
    return bundle.getEntry().stream().map(FhirEventResourceServiceTest::reference).toList();
  }

  private static String reference(BundleEntryComponent entry) {
    return entry.getResource().fhirType() + "/" + entry.getResource().getIdElement().getIdPart();
  }

  private static void assertEntries(Bundle bundle, String... expected) {
    assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType());
    assertEquals(List.of(expected), references(bundle));
    assertTrue(bundle.hasTotal());
    assertEquals(expected.length, bundle.getTotal());
    Stream<String> fullUrls = bundle.getEntry().stream().map(BundleEntryComponent::getFullUrl);
    assertEquals(expected.length, fullUrls.distinct().count());
    for (BundleEntryComponent entry : bundle.getEntry()) {
      assertEquals("http://localhost/api/fhir/" + reference(entry), entry.getFullUrl());
      assertEquals(Bundle.SearchEntryMode.MATCH, entry.getSearch().getMode());
    }
    assertNotNull(bundle.getLink(Bundle.LINK_SELF));
    FhirR4Validation.assertValid(bundle);
  }

  private static ResolvedMapping encounterMapping(String type, String program, String stage) {
    Entry ambulatory =
        Entry.constant(
            ENCOUNTER_CLASS, ENCOUNTER_CLASS_SYSTEM, ENCOUNTER_CLASS_CODE, ENCOUNTER_CLASS_DISPLAY);
    return resolved(ENCOUNTER, type, program, stage, entries(ambulatory), Map.of());
  }

  private static ResolvedMapping observationMapping() {
    Entry a = Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, DE_A).system(LOINC_SYSTEM);
    Entry b = Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, DE_B).system(LOINC_SYSTEM);
    var values = entries(a.code(LOINC_BODY_HEIGHT_CODE), b.code(LOINC_BODY_WEIGHT_CODE));
    var types = Map.of(DE_A, ValueType.NUMBER, DE_B, ValueType.NUMBER);
    return resolved(OBSERVATION, TET, P1, S1, values, types);
  }

  private static ResolvedMapping patientMapping() {
    Entry gender = Entry.field(PATIENT_GENDER, ATTRIBUTE, GENDER_TEA).valueMap(Map.of("M", "male"));
    return resolved(PATIENT, TET, null, null, entries(gender), Map.of(GENDER_TEA, ValueType.TEXT));
  }

  private static MockHttpServletRequest searchRequest() {
    return request(FhirSearchParameters.PATIENT, TE);
  }

  private static MockHttpServletRequest request(String... namesAndValues) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    for (int i = 0; i < namesAndValues.length; i += 2) {
      request.setParameter(namesAndValues[i], namesAndValues[i + 1]);
    }
    return request;
  }

  private MockHttpServletRequest expiringOnLinks(String... namesAndValues) {
    MockHttpServletRequest request =
        new MockHttpServletRequest() {
          @Override
          public String getQueryString() {
            return spent(super.getQueryString());
          }
        };
    request.setParameters(request(namesAndValues).getParameterMap());
    return request;
  }

  private <T> T recorded(T result) {
    seen.add(DeadlineHolder.get());
    return result;
  }

  private <T> T spent(T result) {
    nanos += BUDGET.plusSeconds(1).toNanos();
    return result;
  }

  private static void assertExpired(Executable operation) {
    assertThrows(DeadlineExceededException.class, operation);
  }

  private static void assertError(
      HttpStatus status, IssueType issueType, FhirApiException exception) {
    assertEquals(status, exception.getStatus(), exception.getDiagnostics());
    assertEquals(issueType, exception.getIssueType(), exception.getDiagnostics());
  }
}
