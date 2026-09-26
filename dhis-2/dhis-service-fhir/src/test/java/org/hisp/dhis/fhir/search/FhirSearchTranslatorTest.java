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

import static org.hisp.dhis.common.QueryOperator.EQ;
import static org.hisp.dhis.common.QueryOperator.GE;
import static org.hisp.dhis.common.QueryOperator.GT;
import static org.hisp.dhis.common.QueryOperator.IN;
import static org.hisp.dhis.common.QueryOperator.LE;
import static org.hisp.dhis.common.QueryOperator.LT;
import static org.hisp.dhis.common.QueryOperator.SW;
import static org.hisp.dhis.common.ValueType.DATE;
import static org.hisp.dhis.common.ValueType.INTEGER;
import static org.hisp.dhis.common.ValueType.NUMBER;
import static org.hisp.dhis.common.ValueType.TEXT;
import static org.hisp.dhis.fhir.FhirTestFixtures.entries;
import static org.hisp.dhis.fhir.FhirTestFixtures.resolved;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.ENCOUNTER;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.IMMUNIZATION;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.OBSERVATION;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.PATIENT;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.ATTRIBUTE;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.DATA_ELEMENT;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.ENCOUNTER_CLASS;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_ADMINISTERED;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_VACCINE_CODE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.OBSERVATION_VALUE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_BIRTH_DATE;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_FAMILY_NAME;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_GENDER;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_GIVEN_NAME;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.PATIENT_IDENTIFIER;
import static org.hisp.dhis.fhir.search.FhirSearchParameters.DEFAULT_COUNT;
import static org.hisp.dhis.fhir.search.FhirSearchParameters.DEFAULT_PAGE;
import static org.hisp.dhis.fhir.search.FhirSearchTranslator.EVENT_FIELDS;
import static org.hisp.dhis.fhir.search.FhirSearchTranslator.PATIENT_FIELDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.hisp.dhis.common.QueryFilter;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.feedback.BadRequestException;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.FhirTestFixtures.Entry;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.search.FhirSearchParameters.Operation;
import org.hisp.dhis.fhir.search.FhirSearchTranslator.TranslatedSearch;
import org.hisp.dhis.fhir.service.FhirTrackerReader.FhirSearchOrigin;
import org.hisp.dhis.setting.SystemSettings;
import org.hisp.dhis.setting.SystemSettingsProvider;
import org.hisp.dhis.webapi.controller.tracker.export.FilterParser;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.EnrollmentRequestParams;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.TrackedEntityRequestParams;
import org.hl7.fhir.r4.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Tests that {@link FhirSearchParameters} validates the query of every FHIR operation, rejecting
 * each offending parameter with a {@code 400 invalid} that names only that parameter, and that
 * {@link FhirSearchTranslator} turns validated queries and read ids into Tracker export request
 * parameters.
 */
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
  private static final List<String> JSON_FORMATS =
      List.of("json", "application/json", "application/fhir+json");
  private static final List<FhirResourceType> EVENT_TYPES =
      List.of(ENCOUNTER, IMMUNIZATION, OBSERVATION);
  private static final Map<String, ValueType> PATIENT_VALUE_TYPES =
      Map.of(TEA_IDENT, TEXT, TEA_FAMILY, TEXT, TEA_GIVEN, TEXT, TEA_BIRTH, DATE, TEA_GENDER, TEXT);

  @Mock private SystemSettingsProvider settingsProvider;
  @Mock private SystemSettings settings;

  private FhirSearchParameters parameters;
  private FhirSearchTranslator translator;

  @BeforeEach
  void setUp() {
    lenient().when(settingsProvider.getCurrentSettings()).thenReturn(settings);
    lenient().when(settings.getTrackedEntityMaxLimit()).thenReturn(100);
    parameters = new FhirSearchParameters(settingsProvider);
    translator = new FhirSearchTranslator(parameters);
  }

  /** The Patient mapping on {@link #TYPE} with an entry for every attribute search parameter. */
  private static ResolvedMapping patientMapping(
      String program, Map<String, Set<QueryOperator>> blocked, Map<String, Integer> minChars) {
    return resolved(
        PATIENT,
        TYPE,
        program,
        null,
        entries(
            Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, TEA_IDENT).system("urn:test:ident"),
            Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, TEA_FAMILY),
            Entry.field(PATIENT_GIVEN_NAME, ATTRIBUTE, TEA_GIVEN),
            Entry.field(PATIENT_BIRTH_DATE, ATTRIBUTE, TEA_BIRTH),
            Entry.field(PATIENT_GENDER, ATTRIBUTE, TEA_GENDER)
                .valueMap(Map.of("M", "male", "F", "female", "O", "other", "X", "other"))),
        PATIENT_VALUE_TYPES,
        blocked,
        minChars);
  }

  private static ResolvedMapping fullMapping() {
    return patientMapping(null, Map.of(), Map.of());
  }

  private static ResolvedMapping patientWith(Map<String, ValueType> valueTypes, Entry... fields) {
    return resolved(PATIENT, TYPE, null, null, entries(fields), valueTypes);
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

  /** Builds a GET request holding every {@code name=value} pair of the {@code &}-joined query. */
  private static MockHttpServletRequest request(String query) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fhir");
    if (!query.isEmpty()) {
      request.setQueryString(query);
      for (String pair : query.split("&")) {
        String[] nameValue = pair.split("=", 2);
        request.addParameter(nameValue[0], nameValue.length == 2 ? nameValue[1] : "");
      }
    }
    return request;
  }

  private TranslatedSearch translatePatient(ResolvedMapping mapping, String query) {
    return translator.toTrackedEntityParams(
        parameters.parse(Operation.PATIENT_SEARCH, request(query), mapping), mapping);
  }

  private TranslatedSearch translateEvents(FhirResourceType type, String query) {
    return translator.toEnrollmentParams(
        parameters.parse(Operation.search(type), request(query), null),
        List.of(eventMapping(type)),
        PROGRAM);
  }

  private Map<UID, List<QueryFilter>> patientFilters(ResolvedMapping mapping, String query)
      throws BadRequestException {
    return filters(translatePatient(mapping, query).trackedEntityParams());
  }

  private static Map<UID, List<QueryFilter>> filters(TrackedEntityRequestParams params)
      throws BadRequestException {
    return FilterParser.parseFilters(params.getFilter());
  }

  /**
   * Asserts a {@code 400 invalid} naming {@code parameter} whose diagnostics omit {@code others}.
   */
  private static void assertInvalid(String parameter, Executable call, String... others) {
    FhirApiException exception = assertThrows(FhirApiException.class, call, parameter);
    String diagnostics = exception.getDiagnostics();
    assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus(), diagnostics);
    assertEquals(IssueType.INVALID, exception.getIssueType(), diagnostics);
    assertTrue(diagnostics.startsWith("Invalid parameter '" + parameter + "': "), diagnostics);
    assertTrue(Stream.of(others).noneMatch(diagnostics::contains), diagnostics);
  }

  /**
   * Asserts that each whitespace-separated query is rejected with a {@code 400} naming the first
   * parameter of that query.
   */
  private static void assertAllInvalid(Consumer<String> call, String queries) {
    assertAll(
        Stream.of(queries.strip().split("\\s+"))
            .map(q -> () -> assertInvalid(q.split("=")[0], () -> call.accept(q))));
  }

  /** Asserts a {@code 400} on {@code query} naming {@code parameter} and no other of its names. */
  private static void assertOnlyNamed(String parameter, String query, Consumer<String> call) {
    String[] others =
        Stream.of(query.split("&"))
            .map(pair -> pair.split("=")[0])
            .filter(name -> !name.equals(parameter))
            .toArray(String[]::new);
    assertInvalid(parameter, () -> call.accept(query), others);
  }

  /** Asserts that the attribute has exactly one filter, with the operator and unescaped value. */
  private static void assertFilter(
      Map<UID, List<QueryFilter>> filters, String tea, QueryOperator operator, String value) {
    List<QueryFilter> onAttribute = filters.getOrDefault(UID.of(tea), List.of());
    assertEquals(1, onAttribute.size(), () -> tea + " in " + filters.keySet());
    assertEquals(operator, onAttribute.get(0).getOperator(), tea);
    assertEquals(value, onAttribute.get(0).getFilter(), tea);
  }

  /** Asserts one {@code in} filter on the gender attribute over exactly {@code values}. */
  private static void assertInFilter(TranslatedSearch search, Set<String> values)
      throws BadRequestException {
    List<QueryFilter> onGender = filters(search.trackedEntityParams()).get(UID.of(TEA_GENDER));
    assertEquals(1, onGender.size());
    assertEquals(IN, onGender.get(0).getOperator());
    assertEquals(values, Set.of(onGender.get(0).getFilter().split(QueryFilter.OPTION_SEP)));
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
    TranslatedSearch search = translatePatient(fullMapping(), "_id=" + TE_1 + "," + TE_2);
    assertEquals(UID.of(TE_1, TE_2), search.trackedEntityParams().getTrackedEntities());
    assertNull(search.trackedEntityParams().getFilter());
    assertEquals(Map.of(), search.origin().attributeToParameter());
    assertEquals(List.of(), search.origin().suppliedAttributeParameters());
    assertFalse(search.empty());
  }

  @Test
  void attributeParametersTranslateToCombinedTrackerFilters() throws BadRequestException {
    ResolvedMapping mapping = fullMapping();
    for (String identifier : List.of("urn:test:ident|ABC123", "ABC123")) {
      Map<UID, List<QueryFilter>> filters = patientFilters(mapping, "identifier=" + identifier);
      assertEquals(Set.of(UID.of(TEA_IDENT)), filters.keySet());
      assertFilter(filters, TEA_IDENT, EQ, "ABC123");
    }
    assertFilter(patientFilters(mapping, "family=rain"), TEA_FAMILY, SW, "rain");
    assertFilter(patientFilters(mapping, "given=Fra"), TEA_GIVEN, SW, "Fra");

    TrackedEntityRequestParams params =
        translatePatient(mapping, "identifier=ABC123&family=rain&given=Fra").trackedEntityParams();
    Map<UID, List<QueryFilter>> filters = filters(params);
    assertEquals(3, params.getFilter().split(",").length);
    assertEquals(UID.of(TEA_IDENT, TEA_FAMILY, TEA_GIVEN), filters.keySet());
    assertFilter(filters, TEA_IDENT, EQ, "ABC123");
    assertFilter(filters, TEA_FAMILY, SW, "rain");
    assertFilter(filters, TEA_GIVEN, SW, "Fra");
  }

  @Test
  void birthdatePrefixesTranslateToOperators() {
    Map<String, QueryOperator> prefixes =
        Map.of("eq", EQ, "ge", GE, "le", LE, "gt", GT, "lt", LT, "", EQ);
    assertAll(
        prefixes.entrySet().stream()
            .map(p -> () -> assertBirthdateFilter(p.getKey(), p.getValue())));
  }

  private void assertBirthdateFilter(String prefix, QueryOperator operator)
      throws BadRequestException {
    String query = "birthdate=" + prefix + "2000-01-15";
    assertFilter(patientFilters(fullMapping(), query), TEA_BIRTH, operator, "2000-01-15");
  }

  @Test
  void genderTranslatesThroughValueMap() throws BadRequestException {
    ResolvedMapping mapping = fullMapping();
    assertFilter(patientFilters(mapping, "gender=male"), TEA_GENDER, EQ, "M");
    assertInFilter(translatePatient(mapping, "gender=other"), Set.of("O", "X"));
    assertInFilter(translatePatient(mapping, "gender=male,female"), Set.of("M", "F"));
    assertFalse(translatePatient(mapping, "gender=male").empty());

    TranslatedSearch unmapped = translatePatient(mapping, "gender=unknown&family=rain");
    assertTrue(unmapped.empty());
    assertEquals(Set.of(UID.of(TEA_FAMILY)), filters(unmapped.trackedEntityParams()).keySet());
  }

  @Test
  void patientPagingFieldsAndFormatTranslate() {
    ResolvedMapping mapping = fullMapping();
    TranslatedSearch paged = translatePatient(mapping, "_count=10&_page=3");
    TrackedEntityRequestParams params = paged.trackedEntityParams();
    assertEquals(10, params.getPageSize());
    assertEquals(3, params.getPage());
    assertEquals(10, paged.count());
    assertEquals(3, paged.page());
    assertFalse(params.isTotalPages());
    assertEquals(PATIENT_FIELDS, params.getFields());
    assertTrue(PATIENT_FIELDS.test("attributes"));
    assertFalse(PATIENT_FIELDS.test("enrollments"));

    TrackedEntityRequestParams defaults =
        translatePatient(mapping, "family=rain").trackedEntityParams();
    assertEquals(DEFAULT_COUNT, defaults.getPageSize());
    assertEquals(DEFAULT_PAGE, defaults.getPage());
    for (String format : JSON_FORMATS) {
      assertDoesNotThrow(() -> translatePatient(mapping, "family=rain&_format=" + format));
    }
  }

  @Test
  void filterValuesAreEscapedForTheTrackerFilterGrammar() throws BadRequestException {
    TrackedEntityRequestParams params =
        translatePatient(fullMapping(), "family=a/b:c").trackedEntityParams();
    assertEquals(TEA_FAMILY + ":sw:a//b/:c", params.getFilter());
    assertFilter(filters(params), TEA_FAMILY, SW, "a/b:c");

    // escape() also escapes commas, which a parsed family value never contains.
    String escaped = FhirSearchTranslator.escape("a/,b:c");
    assertEquals("a///,b/:c", escaped);
    assertFilter(
        FilterParser.parseFilters(TEA_FAMILY + ":sw:" + escaped), TEA_FAMILY, SW, "a/,b:c");
  }

  @Test
  void patientOriginRecordsFilteredAttributesAndParameters() {
    ResolvedMapping mapping = fullMapping();
    FhirSearchOrigin origin =
        translatePatient(mapping, "identifier=urn:test:ident|X&family=rain&given=Fra&_count=5")
            .origin();
    assertEquals(
        Map.of(TEA_IDENT, "identifier", TEA_FAMILY, "family", TEA_GIVEN, "given"),
        origin.attributeToParameter());
    assertEquals(List.of("identifier", "family", "given"), origin.suppliedAttributeParameters());
    List<String> configured = List.of("identifier", "family", "given", "birthdate", "gender");
    assertEquals(configured, origin.configuredAttributeParameters());
    assertEquals(configured, parameters.configuredAttributeParameters(mapping));

    ResolvedMapping blockedFamily = patientMapping(null, Map.of(TEA_FAMILY, Set.of(SW)), Map.of());
    assertEquals(
        List.of("identifier", "given", "birthdate", "gender"),
        parameters.configuredAttributeParameters(blockedFamily));
  }

  @Test
  void patientCountRespectsPositiveTrackedEntityMaxLimit() {
    ResolvedMapping mapping = fullMapping();
    assertEquals(100, translatePatient(mapping, "_count=100").count());
    assertInvalid("_count", () -> translatePatient(mapping, "_count=101"));

    when(settings.getTrackedEntityMaxLimit()).thenReturn(10);
    assertInvalid("_count", () -> translatePatient(mapping, "family=rain"), "family");

    when(settings.getTrackedEntityMaxLimit()).thenReturn(0);
    assertEquals(5000, translatePatient(mapping, "_count=5000").count());
  }

  @Test
  void patientSearchRejectsInvalidParameters() throws BadRequestException {
    assertAllInvalid(
        query -> translatePatient(fullMapping(), query),
        """
        foo=1 family:exact=x patient=TrackedEnt1
        _sort=family _include=Patient:organization _revinclude=Encounter:subject
        _summary=true _elements=name _total=accurate _type=Patient
        family=a&family=b family= family=a,b _id=bad _id=TrackedEnt1,
        identifier=urn:other|1 identifier=|ABC123 identifier=urn:test:ident|
        birthdate=2000-01 birthdate=2000 birthdate=ne2000-01-01 birthdate=sa2000-01-01
        birthdate=ge2000-13-45 gender=bogus gender=male,
        _count=0 _count=abc _count=101 _page=0 _page=abc _format=xml
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
    ResolvedMapping byType = fullMapping();
    ResolvedMapping byProgram = patientMapping(PROGRAM, Map.of(), Map.of());
    for (TrackedEntityRequestParams params :
        List.of(
            translatePatient(byType, "family=rain").trackedEntityParams(),
            translator.patientReadParams(TE_1, byType))) {
      assertEquals(UID.of(TYPE), params.getTrackedEntityType());
      assertNull(params.getProgram());
    }
    for (TrackedEntityRequestParams params :
        List.of(
            translatePatient(byProgram, "family=rain").trackedEntityParams(),
            translator.patientReadParams(TE_1, byProgram))) {
      assertEquals(UID.of(PROGRAM), params.getProgram());
      assertNull(params.getTrackedEntityType());
    }

    TrackedEntityRequestParams read = translator.patientReadParams(TE_1, byProgram);
    assertEquals(Set.of(UID.of(TE_1)), read.getTrackedEntities());
    assertEquals(1, read.getPageSize());
    assertFalse(read.isTotalPages());
    assertEquals(PATIENT_FIELDS, read.getFields());
    assertNull(read.getFilter());
  }

  @Test
  void multiParameterRequestNamesOnlyOffendingParameter() {
    Consumer<String> patient = query -> translatePatient(fullMapping(), query);
    assertOnlyNamed("birthdate", "family=rain&given=Fra&birthdate=2000-01", patient);
    assertOnlyNamed("_page", "_id=" + TE_1 + "&family=rain&_page=0", patient);
    assertOnlyNamed(
        "_id", "patient=" + TE_1 + "&_count=5&_id=bad", q -> translateEvents(ENCOUNTER, q));
    assertOnlyNamed(
        "_format",
        "patient=" + TE_1 + "&_page=2&_format=xml",
        q -> translateEvents(OBSERVATION, q));
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
    assertInFilter(translatePatient(shortGender, "gender=other"), Set.of("O", "X"));
  }

  @Test
  void eventPatientAndSubjectTranslateToTrackedEntityOfOneProgram() {
    List<TranslatedSearch> searches = new ArrayList<>();
    for (FhirResourceType type : EVENT_TYPES) {
      searches.add(translateEvents(type, "patient=" + TE_1));
      searches.add(translateEvents(type, "patient=Patient/" + TE_1));
    }
    searches.add(translateEvents(ENCOUNTER, "subject=" + TE_1));
    searches.add(translateEvents(OBSERVATION, "subject=Patient/" + TE_1));
    for (TranslatedSearch search : searches) {
      EnrollmentRequestParams params = search.enrollmentParams();
      assertEquals(UID.of(TE_1), params.getTrackedEntity());
      assertEquals(Set.of(), params.getEnrollments());
      assertEquals(UID.of(PROGRAM), params.getProgram());
      assertFalse(params.isPaging());
      assertEquals(EVENT_FIELDS, params.getFields());
      assertEquals(FhirSearchOrigin.empty(), search.origin());
      assertFalse(search.empty());
    }
    assertTrue(EVENT_FIELDS.test("events"));
  }

  @Test
  void eventIdTranslatesToEnrollmentsAndLogicalIds() {
    TranslatedSearch encounter = translateEvents(ENCOUNTER, "_id=" + ENCOUNTER_ID);
    assertEquals(Set.of(UID.of(ENR)), encounter.enrollmentParams().getEnrollments());
    assertNull(encounter.enrollmentParams().getTrackedEntity());
    assertEquals(Set.of(ENCOUNTER_ID), encounter.logicalIds());
    assertTrue(encounter.matchesId(ENCOUNTER_ID));
    assertFalse(encounter.matchesId(ENR + "-EventUid002"));
    for (FhirResourceType type : List.of(IMMUNIZATION, OBSERVATION)) {
      TranslatedSearch perDataElement = translateEvents(type, "_id=" + PER_DE_ID);
      assertEquals(Set.of(UID.of(ENR)), perDataElement.enrollmentParams().getEnrollments());
      assertTrue(perDataElement.matchesId(PER_DE_ID));
      assertFalse(perDataElement.matchesId(ENCOUNTER_ID + "-" + DE_2));
    }

    String second = ENR_2 + "-" + EVT;
    TranslatedSearch two = translateEvents(ENCOUNTER, "_id=" + ENCOUNTER_ID + "," + second);
    assertEquals(UID.of(ENR, ENR_2), two.enrollmentParams().getEnrollments());
    assertEquals(Set.of(ENCOUNTER_ID, second), two.logicalIds());
    assertTrue(translateEvents(ENCOUNTER, "patient=" + TE_1).matchesId(ENCOUNTER_ID));
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
          query -> translateEvents(type, query), "patient=Patient/bad patient=Group/TrackedEnt1");
      assertAllInvalid(
          query -> translateEvents(type, query + withPatient),
          "_id=bad foo=1 family=rain _count=abc _count=0 _page=0 _format=xml");
      assertInvalid("patient", () -> translateEvents(type, ""));
      assertInvalid("patient", () -> translateEvents(type, "_count=5"), "_count");
    }
    assertAllInvalid(
        query -> translateEvents(ENCOUNTER, query + withPatient), "_id=" + PER_DE_ID + " code=x");
    assertAllInvalid(
        query -> translateEvents(IMMUNIZATION, query), "_id=" + ENCOUNTER_ID + " subject=" + TE_1);
    assertAllInvalid(
        query -> translateEvents(OBSERVATION, query + withPatient),
        "_id=" + ENCOUNTER_ID + " code=" + LOINC + "| code=" + HEIGHT + ",,");
    assertInvalid("subject", () -> translateEvents(ENCOUNTER, "patient=" + TE_1 + "&subject=x"));
    assertInvalid("patient", () -> translateEvents(OBSERVATION, "code=" + HEIGHT), "code");
  }

  @Test
  void eventPagingStaysOutOfTheTrackerRequest() {
    TranslatedSearch paged = translateEvents(ENCOUNTER, "patient=" + TE_1 + "&_count=2&_page=3");
    assertEquals(2, paged.count());
    assertEquals(3, paged.page());
    assertNull(paged.enrollmentParams().getPageSize());
    assertNull(paged.enrollmentParams().getPage());

    TranslatedSearch defaults = translateEvents(OBSERVATION, "patient=" + TE_1);
    assertEquals(DEFAULT_COUNT, defaults.count());
    assertEquals(DEFAULT_PAGE, defaults.page());
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
      assertEquals(EVENT_FIELDS, params.getFields());
    }
  }

  @Test
  void formatOnlyOperationsAcceptOnlyJsonFormat() {
    for (Operation operation : List.of(Operation.READ, Operation.EVERYTHING, Operation.METADATA)) {
      Consumer<String> check = query -> parameters.checkFormatOnly(operation, request(query));
      assertDoesNotThrow(() -> check.accept(""));
      JSON_FORMATS.forEach(format -> assertDoesNotThrow(() -> check.accept("_format=" + format)));
      assertAllInvalid(check, "foo=1 _format=xml _format=json&_format=json _format= family=rain");
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> parameters.checkFormatOnly(Operation.PATIENT_SEARCH, request("")));
  }

  @Test
  void searchOperationMatchesResourceType() {
    assertEquals(Operation.PATIENT_SEARCH, Operation.search(PATIENT));
    assertEquals(Operation.ENCOUNTER_SEARCH, Operation.search(ENCOUNTER));
    assertEquals(Operation.IMMUNIZATION_SEARCH, Operation.search(IMMUNIZATION));
    assertEquals(Operation.OBSERVATION_SEARCH, Operation.search(OBSERVATION));
  }
}
