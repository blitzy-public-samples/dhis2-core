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
package org.hisp.dhis.fhir.search;

import static java.util.Map.entry;
import static org.hisp.dhis.common.QueryOperator.*;
import static org.hisp.dhis.common.ValueType.*;
import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.hisp.dhis.fhir.search.FhirSearchTranslator.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.hisp.dhis.common.*;
import org.hisp.dhis.feedback.BadRequestException;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.search.FhirSearchParameters.Operation;
import org.hisp.dhis.fhir.service.FhirTrackerReader;
import org.hisp.dhis.fhir.service.FhirTrackerReader.FhirSearchOrigin;
import org.hisp.dhis.setting.*;
import org.hisp.dhis.tracker.export.fieldfiltering.*;
import org.hisp.dhis.tracker.export.timeout.TrackerExportTimeout;
import org.hisp.dhis.webapi.controller.tracker.export.FilterParser;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.*;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.*;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/** Tests {@link FhirSearchParameters} validation and {@link FhirSearchTranslator} translation. */
@ExtendWith(MockitoExtension.class)
class FhirSearchTranslatorTest {
  private static final String TYPE = "TeType00001";
  private static final String PROGRAM = "Program0001";
  private static final String STAGE = "Stage000001";
  private static final String TEA_IDENT = "TeaIdent001";
  private static final String TEA_IDENT_2 = "TeaIdent002";
  private static final String TEA_FAMILY = "TeaFamily01";
  private static final String TEA_GIVEN = "TeaGiven001";
  private static final String TEA_BIRTH = "TeaBirth001";
  private static final String TEA_GENDER = "TeaGender01";
  private static final String TEA_INTEGER = "TeaInteger1";
  private static final String TE_1 = "TrackedEnt1";
  private static final String TE_2 = "TrackedEnt2";
  private static final String ENR = "Enrollment1";
  private static final String ENR_2 = "Enrollment2";
  private static final String EVT = "EventUid001";
  private static final String DE_1 = "DataElem001";
  private static final String DE_2 = "DataElem002";
  private static final String ENCOUNTER_ID = ENR + "-" + EVT;
  private static final String PER_DE_ID = ENCOUNTER_ID + "-" + DE_1;
  private static final String LOINC = "http://loinc.org";
  private static final String HEIGHT = "8302-2";
  private static final String WEIGHT = "29463-7";
  private static final List<FhirResourceType> EVENT_TYPES =
      List.of(ENCOUNTER, IMMUNIZATION, OBSERVATION);
  private static final Map<String, ValueType> PATIENT_VALUE_TYPES =
      Map.of(TEA_IDENT, TEXT, TEA_FAMILY, TEXT, TEA_GIVEN, TEXT, TEA_BIRTH, DATE, TEA_GENDER, TEXT);
  private static final ResolvedMapping FULL_MAPPING = patientMapping(null, Map.of(), Map.of());
  private static final Fields EXPECTED_PATIENT_FIELDS =
      FieldsParser.parse("trackedEntity,trackedEntityType,updatedAt,attributes");
  private static final Fields EXPECTED_EVENT_FIELDS =
      FieldsParser.parse(
          "enrollment,trackedEntity,program,updatedAt,events[event,programStage,status,occurredAt,scheduledAt,updatedAt,dataValues[dataElement,value]]");
  @Mock private SystemSettingsProvider settingsProvider;
  @Mock private SystemSettings settings;
  @Mock private FhirTrackedEntityExportAdapter trackedEntityAdapter;
  @Mock private FhirEnrollmentExportAdapter enrollmentAdapter;
  @Mock private TrackerExportTimeout timeout;
  private FhirSearchParameters parameters;
  private FhirSearchTranslator translator;

  @BeforeEach
  void setUp() {
    lenient().when(settingsProvider.getCurrentSettings()).thenReturn(settings);
    lenient().when(settings.getTrackedEntityMaxLimit()).thenReturn(100);
    parameters = new FhirSearchParameters(settingsProvider);
    translator = new FhirSearchTranslator(parameters);
  }

  private static ResolvedMapping patientMapping(
      String program, Map<String, Set<QueryOperator>> blocked, Map<String, Integer> minChars) {
    List<FhirFieldMapping> fields =
        entries(
            Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, TEA_IDENT).system("urn:test:ident"),
            Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, TEA_FAMILY),
            Entry.field(PATIENT_GIVEN_NAME, ATTRIBUTE, TEA_GIVEN),
            Entry.field(PATIENT_BIRTH_DATE, ATTRIBUTE, TEA_BIRTH),
            Entry.field(PATIENT_GENDER, ATTRIBUTE, TEA_GENDER)
                .valueMap(Map.of("M", "male", "F", "female", "O", "other", "X", "other")));
    return resolved(PATIENT, TYPE, program, null, fields, PATIENT_VALUE_TYPES, blocked, minChars);
  }

  private static ResolvedMapping patientWith(Map<String, ValueType> valueTypes, Entry... fields) {
    return resolved(PATIENT, TYPE, null, null, entries(fields), valueTypes);
  }

  private static ResolvedMapping genderMapping(Map<String, String> valueMap) {
    Entry gender = Entry.field(PATIENT_GENDER, ATTRIBUTE, TEA_GENDER).valueMap(valueMap);
    return patientWith(PATIENT_VALUE_TYPES, gender);
  }

  private static ResolvedMapping eventMapping(FhirResourceType type) {
    List<FhirFieldMapping> fields =
        switch (type) {
          case ENCOUNTER -> entries(Entry.constant(ENCOUNTER_CLASS, "urn:class", "AMB", "amb"));
          case IMMUNIZATION ->
              entries(
                  Entry.field(IMMUNIZATION_ADMINISTERED, DATA_ELEMENT, DE_1),
                  Entry.constant(IMMUNIZATION_VACCINE_CODE, "urn:cvx", "03", "MMR"));
          case OBSERVATION ->
              entries(
                  Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, DE_1).system(LOINC).code(HEIGHT),
                  Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, DE_2).system(LOINC).code(WEIGHT));
          case PATIENT -> throw new IllegalArgumentException(type.name());
        };
    return resolved(type, TYPE, PROGRAM, STAGE, fields, Map.of(DE_1, INTEGER, DE_2, NUMBER));
  }

  private static MockHttpServletRequest request(String query) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fhir");
    for (String pair : query.isEmpty() ? new String[0] : query.split("&")) {
      String[] nameValue = pair.split("=", 2);
      request.addParameter(nameValue[0], nameValue.length == 2 ? nameValue[1] : "");
    }
    return request;
  }

  private TranslatedSearch translatePatient(ResolvedMapping mapping, String query) {
    return translator.toTrackedEntityParams(
        parameters.parse(Operation.search(PATIENT), request(query), mapping), mapping);
  }

  private TranslatedSearch translateEvents(FhirResourceType type, String query) {
    var parsed = parameters.parse(Operation.search(type), request(query), null);
    return translator.toEnrollmentParams(parsed, List.of(eventMapping(type)), PROGRAM);
  }

  private Map<UID, List<QueryFilter>> patientFilters(ResolvedMapping mapping, String query)
      throws BadRequestException {
    return filters(translatePatient(mapping, query).trackedEntityParams());
  }

  private static Map<UID, List<QueryFilter>> filters(TrackedEntityRequestParams params)
      throws BadRequestException {
    return FilterParser.parseFilters(params.getFilter());
  }

  private static String assertInvalid(String parameter, Executable call, String... others) {
    FhirApiException exception = assertThrows(FhirApiException.class, call, parameter);
    String diagnostics = exception.getDiagnostics();
    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus(), diagnostics);
    assertEquals(IssueType.INVALID, exception.getIssueType(), diagnostics);
    assertTrue(diagnostics.startsWith("Invalid parameter '" + parameter + "': "), diagnostics);
    assertTrue(Stream.of(others).noneMatch(diagnostics::contains), diagnostics);
    return diagnostics;
  }

  private static void assertAllInvalid(Consumer<String> call, String queries) {
    assertAll(
        Stream.of(queries.strip().split("\\s+"))
            .map(q -> () -> assertInvalid(q.split("=")[0], () -> call.accept(q))));
  }

  private static void assertOnlyNamed(String parameter, String query, Consumer<String> call) {
    String[] others =
        Stream.of(query.split("=[^&]*&?")).filter(n -> !n.equals(parameter)).toArray(String[]::new);
    assertInvalid(parameter, () -> call.accept(query), others);
  }

  private static void assertOrBounds(String name, String element, Consumer<String> call) {
    String copies = name + "=" + String.join(",", Collections.nCopies(100, element));
    assertDoesNotThrow(() -> call.accept(copies));
    String tooMany = assertInvalid(name, () -> call.accept(copies + "," + element));
    assertTrue(tooMany.endsWith("must not contain more than 100 values"), tooMany);
    String tooLong = assertInvalid(name, () -> call.accept(name + "=" + ",".repeat(4097)));
    assertTrue(tooLong.endsWith("must not be longer than 4096 characters"), tooLong);
  }

  private static void assertFilter(
      Map<UID, List<QueryFilter>> filters, String tea, QueryOperator operator, String... values) {
    List<QueryFilter> onAttribute = filters.getOrDefault(UID.of(tea), List.of());
    assertEquals(1, onAttribute.size(), () -> tea + " in " + filters.keySet());
    assertEquals(operator, onAttribute.get(0).getOperator(), tea);
    String[] actual = onAttribute.get(0).getFilter().split(QueryFilter.OPTION_SEP, -1);
    assertEquals(Set.of(values), Set.of(actual), tea);
  }

  private void assertCodes(String query, boolean height, boolean weight) {
    List<FhirFieldMapping> values = eventMapping(OBSERVATION).entries(OBSERVATION_VALUE);
    TranslatedSearch search = translateEvents(OBSERVATION, query);
    assertEquals(height, search.matchesCode(values.get(0)), query);
    assertEquals(weight, search.matchesCode(values.get(1)), query);
    assertEquals(!height && !weight, search.empty(), query);
  }

  @Test
  void patientIdTranslatesToTrackedEntitiesWithoutFilters() {
    TranslatedSearch search = translatePatient(FULL_MAPPING, "_id=" + TE_1 + "," + TE_2);
    assertEquals(UID.of(TE_1, TE_2), search.trackedEntityParams().getTrackedEntities());
    assertNull(search.trackedEntityParams().getFilter());
    assertEquals(Map.of(), search.origin().attributeToParameter());
    assertEquals(List.of(), search.origin().suppliedAttributeParameters());
    assertFalse(search.empty());
  }

  @Test
  void attributeParametersTranslateToCombinedTrackerFilters() throws BadRequestException {
    for (String identifier : List.of("urn:test:ident|ABC123", "ABC123")) {
      var filters = patientFilters(FULL_MAPPING, "identifier=" + identifier);
      assertEquals(Set.of(UID.of(TEA_IDENT)), filters.keySet());
      assertFilter(filters, TEA_IDENT, EQ, "ABC123");
    }
    var filters = patientFilters(FULL_MAPPING, "identifier=ABC123&family=rain&given=Fra");
    assertEquals(UID.of(TEA_IDENT, TEA_FAMILY, TEA_GIVEN), filters.keySet());
    assertFilter(filters, TEA_IDENT, EQ, "ABC123");
    assertFilter(filters, TEA_FAMILY, SW, "rain");
    assertFilter(filters, TEA_GIVEN, SW, "Fra");
  }

  @Test
  void birthdatePrefixesTranslateToOperators() throws BadRequestException {
    for (String date : List.of("2000-01-15", "0001-01-01")) {
      for (var p : Map.of("eq", EQ, "ge", GE, "le", LE, "gt", GT, "lt", LT, "", EQ).entrySet()) {
        String query = "birthdate=" + p.getKey() + date;
        assertFilter(patientFilters(FULL_MAPPING, query), TEA_BIRTH, p.getValue(), date);
      }
    }
    Entry birthDate = Entry.field(PATIENT_BIRTH_DATE, ATTRIBUTE, TEA_BIRTH);
    ResolvedMapping age = patientWith(Map.of(TEA_BIRTH, AGE), birthDate);
    Consumer<String> check =
        date -> parameters.checkAttributeFilter("birthdate", TEA_BIRTH, EQ, date, age);
    assertInvalid("birthdate", () -> check.accept("0000-01-01"));
    assertDoesNotThrow(() -> check.accept("0001-01-01"));
  }

  @Test
  void genderTranslatesThroughValueMap() throws BadRequestException {
    assertFilter(patientFilters(FULL_MAPPING, "gender=male"), TEA_GENDER, EQ, "M");
    assertFilter(patientFilters(FULL_MAPPING, "gender=other"), TEA_GENDER, IN, "O", "X");
    assertFilter(patientFilters(FULL_MAPPING, "gender=male,female"), TEA_GENDER, IN, "M", "F");
    assertFalse(translatePatient(FULL_MAPPING, "gender=male").empty());
    TranslatedSearch unmapped = translatePatient(FULL_MAPPING, "gender=unknown&family=rain");
    assertTrue(unmapped.empty());
    assertEquals(Set.of(UID.of(TEA_FAMILY)), filters(unmapped.trackedEntityParams()).keySet());
    for (var bad : List.of(Map.of("", "male"), Map.of("A;B", "male", "C", "male", "F", "female"))) {
      ResolvedMapping invalid = genderMapping(bad);
      assertThrows(IllegalArgumentException.class, () -> translatePatient(invalid, "gender=male"));
    }
    ResolvedMapping unrequested = genderMapping(Map.of("A;B", "male", "F", "female"));
    assertFilter(patientFilters(unrequested, "gender=female"), TEA_GENDER, EQ, "F");
  }

  @Test
  void patientPagingFieldsAndFormatTranslate() {
    TranslatedSearch paged = translatePatient(FULL_MAPPING, "_count=10&_page=3");
    TrackedEntityRequestParams params = paged.trackedEntityParams();
    assertEquals(
        List.of(10, 3, 10, 3),
        List.of(params.getPageSize(), params.getPage(), paged.count(), paged.page()));
    assertFalse(params.isTotalPages());
    assertEquals(EXPECTED_PATIENT_FIELDS, params.getFields());
    var defaults = translatePatient(FULL_MAPPING, "family=rain").trackedEntityParams();
    assertEquals(List.of(50, 1), List.of(defaults.getPageSize(), defaults.getPage()));
    assertEquals(42949673, translatePatient(FULL_MAPPING, "_count=50&_page=42949673").page());
    assertDoesNotThrow(() -> translatePatient(FULL_MAPPING, "family=rain&_format=json"));
  }

  @Test
  void filterValuesAreEscapedForTheTrackerFilterGrammar() throws BadRequestException {
    var params = translatePatient(FULL_MAPPING, "family=a/b:c").trackedEntityParams();
    assertEquals(TEA_FAMILY + ":sw:a//b/:c", params.getFilter());
    assertFilter(filters(params), TEA_FAMILY, SW, "a/b:c");
    String token = FhirSearchTranslator.escape("a/,b:c");
    assertEquals("a///,b/:c", token);
    assertFilter(FilterParser.parseFilters(TEA_FAMILY + ":sw:" + token), TEA_FAMILY, SW, "a/,b:c");
  }

  @Test
  void patientOriginRecordsFilteredAttributesAndParameters() throws Exception {
    String query = "identifier=urn:test:ident|X&family=rain&given=Fra&_count=5";
    TranslatedSearch search = translatePatient(FULL_MAPPING, query);
    FhirSearchOrigin origin = search.origin();
    assertEquals(
        List.of(
            entry(TEA_IDENT, "identifier"), entry(TEA_FAMILY, "family"), entry(TEA_GIVEN, "given")),
        List.copyOf(origin.attributeToParameter().entrySet()));
    assertEquals(List.of("identifier", "family", "given"), origin.suppliedAttributeParameters());
    List<String> configured = List.of("identifier", "family", "given", "birthdate", "gender");
    assertEquals(configured, origin.configuredAttributeParameters());
    assertEquals(configured, parameters.configuredAttributeParameters(FULL_MAPPING));
    ResolvedMapping blockedFamily = patientMapping(null, Map.of(TEA_FAMILY, Set.of(SW)), Map.of());
    assertEquals(
        List.of("identifier", "given", "birthdate", "gender"),
        parameters.configuredAttributeParameters(blockedFamily));
    var params = search.trackedEntityParams();
    var request = new MockHttpServletRequest();
    var message = "Non-searchable attribute(s) can not be used during global search:  [%s, %s]";
    when(trackedEntityAdapter.find(params, request))
        .thenThrow(new IllegalQueryException(message.formatted(TEA_GIVEN, TEA_FAMILY)));
    var reader = new FhirTrackerReader(trackedEntityAdapter, enrollmentAdapter, timeout);
    assertInvalid("family, given", () -> reader.findTrackedEntities(params, request, origin));
  }

  @Test
  void patientCountRespectsPositiveTrackedEntityMaxLimit() {
    TranslatedSearch minimum = translatePatient(FULL_MAPPING, "_count=1");
    assertEquals(1, minimum.count());
    assertEquals(1, minimum.trackedEntityParams().getPageSize());
    assertEquals(100, translatePatient(FULL_MAPPING, "_count=100").count());
    assertInvalid("_count", () -> translatePatient(FULL_MAPPING, "_count=101"));
    when(settings.getTrackedEntityMaxLimit()).thenReturn(10);
    assertInvalid("_count", () -> translatePatient(FULL_MAPPING, "family=rain"), "family");
    when(settings.getTrackedEntityMaxLimit()).thenReturn(0);
    assertEquals(2147483646, translatePatient(FULL_MAPPING, "_count=2147483646").count());
    for (int limit : new int[] {0, Integer.MAX_VALUE}) {
      when(settings.getTrackedEntityMaxLimit()).thenReturn(limit);
      var max = assertInvalid("_count", () -> translatePatient(FULL_MAPPING, "_count=2147483647"));
      assertTrue(max.endsWith("must not exceed 2147483646"), max);
    }
  }

  @Test
  void patientSearchRejectsInvalidParameters() throws BadRequestException {
    assertAllInvalid(
        query -> translatePatient(FULL_MAPPING, query),
        """
        foo=1 family:exact=x patient=TrackedEnt1 _sort=family _include=Patient:organization
        _revinclude=Encounter:subject _summary=true _elements=name _total=accurate _type=Patient
        family=a&family=b family= family=a,b _id=bad _id=TrackedEnt1, identifier=urn:other|1
        identifier=|ABC123 identifier=urn:test:ident| identifier=urn:test:ident|X|Y
        birthdate=0000-01-01 birthdate=ge0000-12-31 birthdate=2000-01 birthdate=2000
        birthdate=ne2000-01-01 birthdate=sa2000-01-01 birthdate=ge2000-13-45 gender=bogus
        gender=male, _count=0 _count=abc _count=101 _page=0 _page=abc _format=xml
        """);
    ResolvedMapping twoIdentifiers =
        patientWith(
            Map.of(TEA_IDENT, TEXT, TEA_IDENT_2, TEXT),
            Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, TEA_IDENT).system("urn:a"),
            Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, TEA_IDENT_2).system("urn:b"));
    assertAllInvalid(query -> translatePatient(twoIdentifiers, query), "identifier=1");
    assertFilter(patientFilters(twoIdentifiers, "identifier=urn:b|1"), TEA_IDENT_2, EQ, "1");
    ResolvedMapping familyOnly =
        patientWith(
            Map.of(TEA_FAMILY, TEXT), Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, TEA_FAMILY));
    assertAllInvalid(
        query -> translatePatient(familyOnly, query),
        "given=Fra identifier=x birthdate=2000-01-01 gender=male");
    assertThrows(
        IllegalArgumentException.class,
        () -> parameters.parse(Operation.PATIENT_SEARCH, request("family=rain"), null));
  }

  @Test
  void patientSelectorIsProgramOrTypeNeverBoth() {
    for (String program : Arrays.asList(null, PROGRAM)) {
      ResolvedMapping mapping = patientMapping(program, Map.of(), Map.of());
      var search = translatePatient(mapping, "family=rain").trackedEntityParams();
      for (var params : List.of(search, translator.patientReadParams(TE_1, mapping))) {
        assertEquals(program == null ? UID.of(TYPE) : null, params.getTrackedEntityType(), program);
        assertEquals(program == null ? null : UID.of(program), params.getProgram(), program);
      }
    }
    var read = translator.patientReadParams(TE_1, patientMapping(PROGRAM, Map.of(), Map.of()));
    assertEquals(Set.of(UID.of(TE_1)), read.getTrackedEntities());
    assertEquals(1, read.getPageSize());
    assertFalse(read.isTotalPages());
    assertEquals(EXPECTED_PATIENT_FIELDS, read.getFields());
    assertNull(read.getFilter());
  }

  @Test
  void multiParameterRequestNamesOnlyOffendingParameter() {
    Consumer<String> patient = query -> translatePatient(FULL_MAPPING, query);
    assertOnlyNamed("birthdate", "family=rain&given=Fra&birthdate=2000-01", patient);
    assertOnlyNamed("_page", "_id=" + TE_1 + "&family=rain&_page=0", patient);
    assertOnlyNamed("_page", "_count=50&_page=2147483647", patient);
    assertOnlyNamed("_page", "_count=50&_page=42949674", patient);
    Consumer<String> encounter = q -> translateEvents(ENCOUNTER, q);
    Consumer<String> observation = q -> translateEvents(OBSERVATION, q);
    assertOnlyNamed("_id", "patient=" + TE_1 + "&_count=5&_id=bad", encounter);
    assertOnlyNamed("_format", "patient=" + TE_1 + "&_page=2&_format=xml", observation);
    Consumer<String> code = q -> observation.accept(q + "&patient=" + TE_1);
    assertOrBounds("_id", TE_1, patient);
    assertOrBounds("_id", ENCOUNTER_ID, encounter);
    assertOrBounds("gender", "male", patient);
    assertOrBounds("code", HEIGHT, code);
    assertDoesNotThrow(() -> code.accept("code=" + "x".repeat(4096)));
  }

  @Test
  void attributeConstraintsNameOnlyOffendingParameter() throws BadRequestException {
    ResolvedMapping blockedFamily = patientMapping(null, Map.of(TEA_FAMILY, Set.of(SW)), Map.of());
    assertOnlyNamed("family", "family=rain&given=Frank", q -> translatePatient(blockedFamily, q));
    ResolvedMapping shortGiven = patientMapping(null, Map.of(), Map.of(TEA_GIVEN, 3));
    assertOnlyNamed("given", "family=rain&given=Fr", q -> translatePatient(shortGiven, q));
    assertFilter(patientFilters(shortGiven, "given=Fra"), TEA_GIVEN, SW, "Fra");
    ResolvedMapping integer =
        patientWith(
            Map.of(TEA_INTEGER, INTEGER, TEA_FAMILY, TEXT),
            Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, TEA_INTEGER).system("urn:test:int"),
            Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, TEA_FAMILY));
    String query = "identifier=urn:test:int|abc&family=rain";
    assertOnlyNamed("identifier", query, q -> translatePatient(integer, q));
    assertFilter(patientFilters(integer, "identifier=urn:test:int|42"), TEA_INTEGER, EQ, "42");
    ResolvedMapping shortGender = patientMapping(null, Map.of(), Map.of(TEA_GENDER, 2));
    assertOnlyNamed("gender", "gender=male&family=rain", q -> translatePatient(shortGender, q));
    assertFilter(patientFilters(shortGender, "gender=other"), TEA_GENDER, IN, "O", "X");
  }

  @Test
  void eventPatientAndSubjectTranslateToTrackedEntityOfOneProgram() {
    var references = List.of("patient=", "patient=Patient/", "subject=", "subject=Patient/");
    for (FhirResourceType type : EVENT_TYPES) {
      for (String reference : references.subList(0, type == IMMUNIZATION ? 2 : 4)) {
        TranslatedSearch search = translateEvents(type, reference + TE_1);
        EnrollmentRequestParams params = search.enrollmentParams();
        assertEquals(UID.of(TE_1), params.getTrackedEntity());
        assertEquals(Set.of(), params.getEnrollments());
        assertEquals(UID.of(PROGRAM), params.getProgram());
        assertFalse(params.isPaging());
        assertFalse(params.isTotalPages());
        assertEquals(EXPECTED_EVENT_FIELDS, params.getFields());
        assertEquals(FhirSearchOrigin.empty(), search.origin());
        assertFalse(search.empty());
      }
    }
  }

  @Test
  void eventIdTranslatesToEnrollmentsAndLogicalIds() {
    for (FhirResourceType type : EVENT_TYPES) {
      String id = type == ENCOUNTER ? ENCOUNTER_ID : PER_DE_ID;
      TranslatedSearch search = translateEvents(type, "_id=" + id);
      assertEquals(Set.of(UID.of(ENR)), search.enrollmentParams().getEnrollments());
      assertNull(search.enrollmentParams().getTrackedEntity());
      assertEquals(Set.of(id), search.logicalIds());
      assertTrue(search.matchesId(id));
      assertFalse(search.matchesId(id.replaceFirst("1$", "2")));
    }
    String second = ENR_2 + "-" + EVT;
    TranslatedSearch two = translateEvents(ENCOUNTER, "_id=" + ENCOUNTER_ID + "," + second);
    assertEquals(UID.of(ENR, ENR_2), two.enrollmentParams().getEnrollments());
    assertEquals(Set.of(ENCOUNTER_ID, second), two.logicalIds());
  }

  @Test
  void observationCodeSelectsMappedEntries() {
    String query = "patient=" + TE_1 + "&code=";
    assertCodes(query + LOINC + "|" + HEIGHT, true, false);
    assertCodes(query + HEIGHT, true, false);
    assertCodes(query + HEIGHT + "," + WEIGHT, true, true);
    assertCodes(query + "http://other|" + HEIGHT, false, false);
    assertCodes(query + "unknown", false, false);
    assertCodes("patient=" + TE_1, true, true);
  }

  @Test
  void eventSearchRejectsInvalidParameters() {
    String withPatient = "&patient=" + TE_1;
    for (FhirResourceType type : EVENT_TYPES) {
      assertAllInvalid(
          query -> translateEvents(type, query),
          "patient=Patient/bad patient=Group/TrackedEnt1 subject=Patient/bad");
      assertAllInvalid(
          query -> translateEvents(type, query + withPatient),
          "_id=bad foo=1 family=rain _count=abc _count=0 _page=0 _format=xml"
              + " _page=2147483647&_count=50");
      assertInvalid("patient", () -> translateEvents(type, ""));
      assertInvalid("patient", () -> translateEvents(type, "_count=5"), "_count");
      assertInvalid("subject", () -> translateEvents(type, "subject=" + TE_2 + withPatient));
    }
    assertAllInvalid(
        query -> translateEvents(ENCOUNTER, query + withPatient), "_id=" + PER_DE_ID + " code=x");
    assertAllInvalid(
        query -> translateEvents(IMMUNIZATION, query), "_id=" + ENCOUNTER_ID + " subject=" + TE_1);
    assertAllInvalid(
        query -> translateEvents(OBSERVATION, query + withPatient),
        """
        _id=Enrollment1-EventUid001 code=http://loinc.org| code=8302-2,,
        code=http://loinc.org|8302-2|x code=29463-7,http://loinc.org|8302-2|x
        """);
    assertInvalid("patient", () -> translateEvents(OBSERVATION, "code=" + HEIGHT), "code");
  }

  @Test
  void eventPagingStaysOutOfTheTrackerRequest() {
    TranslatedSearch paged = translateEvents(ENCOUNTER, "patient=" + TE_1 + "&_count=2&_page=3");
    assertEquals(List.of(2, 3), List.of(paged.count(), paged.page()));
    assertNull(paged.enrollmentParams().getPageSize());
    assertNull(paged.enrollmentParams().getPage());
    TranslatedSearch defaults = translateEvents(OBSERVATION, "patient=" + TE_1);
    assertEquals(List.of(50, 1), List.of(defaults.count(), defaults.page()));
  }

  @Test
  void eventReadAndEverythingParamsSelectOneProgramWithoutPaging() {
    EnrollmentRequestParams read = translator.eventReadParams(ENR, PROGRAM);
    assertEquals(Set.of(UID.of(ENR)), read.getEnrollments());
    assertNull(read.getTrackedEntity());
    EnrollmentRequestParams everything = translator.everythingParams(TE_1, PROGRAM);
    assertEquals(UID.of(TE_1), everything.getTrackedEntity());
    assertEquals(Set.of(), everything.getEnrollments());
    for (EnrollmentRequestParams params : List.of(read, everything)) {
      assertEquals(UID.of(PROGRAM), params.getProgram());
      assertFalse(params.isPaging());
      assertFalse(params.isTotalPages());
      assertEquals(EXPECTED_EVENT_FIELDS, params.getFields());
    }
  }

  @Test
  void formatOnlyOperationsAcceptOnlyJsonFormat() {
    for (Operation operation : List.of(Operation.READ, Operation.EVERYTHING, Operation.METADATA)) {
      Consumer<String> check = query -> parameters.checkFormatOnly(operation, request(query));
      List.of("json", "application/json", "application/fhir+json")
          .forEach(format -> assertDoesNotThrow(() -> check.accept("_format=" + format)));
      assertAllInvalid(check, "foo=1 _format=xml _format=json&_format=json _format= family=rain");
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> parameters.checkFormatOnly(Operation.PATIENT_SEARCH, request("")));
  }
}
