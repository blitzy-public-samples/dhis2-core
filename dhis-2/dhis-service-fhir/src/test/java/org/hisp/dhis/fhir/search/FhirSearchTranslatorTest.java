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

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Map.entry;
import static org.hisp.dhis.common.QueryOperator.*;
import static org.hisp.dhis.common.ValueType.*;
import static org.hisp.dhis.fhir.FhirTestFixtures.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceMappingValidator.*;
import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.*;
import static org.hisp.dhis.fhir.search.FhirSearchTranslator.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.util.*;
import java.util.Locale;
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
import org.springframework.security.web.firewall.*;

/** Tests {@link FhirSearchParameters} validation and {@link FhirSearchTranslator} translation. */
@ExtendWith(MockitoExtension.class)
class FhirSearchTranslatorTest {
  private static final String TYPE = "TeType00001";
  private static final String PROGRAM = "Program0001";
  private static final String TE_1 = "TrackedEnt1";
  private static final String TEA_IDENT = "TeaIdent001";
  private static final String TEA_IDENT_2 = "TeaIdent002";
  private static final String TEA_FAMILY = "TeaFamily01";
  private static final String TEA_GIVEN = "TeaGiven001";
  private static final String TEA_BIRTH = "TeaBirth001";
  private static final String TEA_GENDER = "TeaGender01";
  private static final String TEA_INTEGER = "TeaInteger1";
  private static final String TE_2 = "TrackedEnt2";
  private static final String ENR = "Enrollment1";
  private static final String ENR_2 = "Enrollment2";
  private static final String EVT = "EventUid001";
  private static final String DE_1 = "DataElem001";
  private static final String DE_2 = "DataElem002";
  private static final String NUL = "\u0000";
  private static final String ENCOUNTER_ID = ENR + "-" + EVT;
  private static final String PER_DE_ID = ENCOUNTER_ID + "-" + DE_1;
  private static final String LOINC = "http://loinc.org";
  private static final String HEIGHT = "8302-2";
  private static final String WEIGHT = "29463-7";
  private static final IllegalStateException UNDECODABLE =
      new IllegalStateException("Character decoding failed");
  private static final List<FhirFieldMapping> OBSERVATION_VALUES =
      entries(
          Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, DE_1).system(LOINC).code(HEIGHT),
          Entry.field(OBSERVATION_VALUE, DATA_ELEMENT, DE_2).system(LOINC).code(WEIGHT));
  private static final Map<String, ValueType> VALUE_TYPES =
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
    return new ResolvedMapping(
        uid(), PATIENT, TYPE, program, null, fields, VALUE_TYPES, blocked, minChars, UPDATED);
  }

  private static ResolvedMapping patientWith(Map<String, ValueType> valueTypes, Entry... fields) {
    return resolved(PATIENT, TYPE, null, null, entries(fields), valueTypes);
  }

  private static ResolvedMapping genderMapping(Map<String, String> valueMap) {
    Entry gender = Entry.field(PATIENT_GENDER, ATTRIBUTE, TEA_GENDER).valueMap(valueMap);
    return patientWith(VALUE_TYPES, gender);
  }

  private static MockHttpServletRequest request(String query) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/fhir");
    for (String pair : query.isEmpty() ? new String[0] : query.split("&")) {
      String[] nameValue = pair.split("=", 2);
      request.addParameter(nameValue[0], nameValue.length == 2 ? nameValue[1] : "");
    }
    return request;
  }

  private static HttpServletRequest firewalled(String rawQuery) {
    MockHttpServletRequest request = request(URLDecoder.decode(rawQuery, UTF_8));
    request.setQueryString(rawQuery);
    return new StrictHttpFirewall().getFirewalledRequest(request);
  }

  private static MockHttpServletRequest undecodable(String rawQuery) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/fhir") {
          @Override
          public Map<String, String[]> getParameterMap() {
            throw UNDECODABLE;
          }
        };
    request.setQueryString(rawQuery);
    return request;
  }

  private void parsePatient(HttpServletRequest request) {
    parameters.parse(Operation.PATIENT_SEARCH, request, FULL_MAPPING);
  }

  private TranslatedSearch translatePatient(ResolvedMapping mapping, String query) {
    return translator.toTrackedEntityParams(
        parameters.parse(Operation.search(PATIENT), request(query), mapping), mapping);
  }

  private TranslatedSearch translateEvents(FhirResourceType type, String query) {
    var parsed = parameters.parse(Operation.search(type), request(query), null);
    return translator.toEnrollmentParams(parsed, PROGRAM);
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

  private static void assertFilter(
      Map<UID, List<QueryFilter>> filters, String tea, QueryOperator operator, String... values) {
    List<QueryFilter> onAttribute = filters.getOrDefault(UID.of(tea), List.of());
    assertEquals(1, onAttribute.size(), () -> tea + " in " + filters.keySet());
    assertEquals(operator, onAttribute.get(0).getOperator(), tea);
    String[] actual = onAttribute.get(0).getFilter().split(QueryFilter.OPTION_SEP, -1);
    assertEquals(Set.of(values), Set.of(actual), tea);
  }

  private void assertCodes(String query, boolean height, boolean weight) {
    TranslatedSearch search = translateEvents(OBSERVATION, query);
    assertEquals(height, search.matchesCode(OBSERVATION_VALUES.get(0)), query);
    assertEquals(weight, search.matchesCode(OBSERVATION_VALUES.get(1)), query);
    assertFalse(search.empty(), query);
  }

  @Test
  void filtersEscapeSeparatorsAndParseInTurkishAndAzerbaijaniLocales() throws BadRequestException {
    for (String identifier : List.of("urn:test:ident|ABC123", "ABC123")) {
      var filters = patientFilters(FULL_MAPPING, "identifier=" + identifier);
      assertEquals(Set.of(UID.of(TEA_IDENT)), filters.keySet());
      assertFilter(filters, TEA_IDENT, EQ, "ABC123");
    }
    var filters = patientFilters(FULL_MAPPING, "identifier=ABC123&family=rain&given=Fra");
    assertEquals(UID.of(TEA_IDENT, TEA_FAMILY, TEA_GIVEN), filters.keySet());
    for (String date : List.of("2000-01-15", "0001-01-01")) {
      for (var p : Map.of("eq", EQ, "ge", GE, "le", LE, "gt", GT, "lt", LT, "", EQ).entrySet()) {
        String query = "birthdate=" + p.getKey() + date;
        assertFilter(patientFilters(FULL_MAPPING, query), TEA_BIRTH, p.getValue(), date);
      }
    }
    Entry birthDate = Entry.field(PATIENT_BIRTH_DATE, ATTRIBUTE, TEA_BIRTH);
    ResolvedMapping age = patientWith(Map.of(TEA_BIRTH, AGE), birthDate);
    Consumer<String> dob = d -> parameters.checkAttributeFilter("birthdate", TEA_BIRTH, EQ, d, age);
    assertInvalid("birthdate", () -> dob.accept("0000-01-01"));
    assertDoesNotThrow(() -> dob.accept("0001-01-01"));
    assertFilter(patientFilters(FULL_MAPPING, "gender=male"), TEA_GENDER, EQ, "m");
    assertFilter(patientFilters(FULL_MAPPING, "gender=other"), TEA_GENDER, IN, "o", "x");
    assertFilter(patientFilters(FULL_MAPPING, "gender=male,female"), TEA_GENDER, IN, "m", "f");
    assertFalse(translatePatient(FULL_MAPPING, "gender=male").empty());
    TranslatedSearch unmapped = translatePatient(FULL_MAPPING, "gender=unknown&family=rain");
    assertTrue(unmapped.empty());
    assertEquals(Set.of(UID.of(TEA_FAMILY)), filters(unmapped.trackedEntityParams()).keySet());
    ResolvedMapping blank = genderMapping(Map.of("", "male"));
    assertThrows(IllegalArgumentException.class, () -> translatePatient(blank, "gender=male"));
    ResolvedMapping separator = genderMapping(Map.of("A;B", "male"));
    var single = patientFilters(separator, "gender=male,female").get(UID.of(TEA_GENDER));
    assertEquals(List.of(new QueryFilter(EQ, "a;b")), single);
    ResolvedMapping shared = genderMapping(Map.of("A;B", "male", "C", "male", "F", "female"));
    assertFilter(patientFilters(shared, "gender=female"), TEA_GENDER, EQ, "f");
    for (String genders : List.of("gender=male", "gender=male,female")) {
      assertThrows(IllegalArgumentException.class, () -> translatePatient(shared, genders));
    }
    ResolvedMapping mapping = genderMapping(Map.of("ΟΔΟΣ", "unknown", "I", "male"));
    for (String tag : List.of("tr", "en")) {
      Locale tracker = Locale.forLanguageTag(tag);
      for (var code : Map.of("unknown", "ΟΔΟΣ", "male", "I").entrySet()) {
        var params = translatePatient(mapping, "gender=" + code.getKey()).trackedEntityParams();
        String operand = params.getFilter().split(":", 3)[2].toLowerCase(tracker);
        for (String value : List.of("ΟΔΟΣ", "οδοσ", "οδος", "I", "i", "İ", "ı")) {
          boolean mapped = genderKeyMatches(code.getValue(), value);
          assertEquals(mapped, genderFold(value).equals(operand), tag + " " + value);
        }
      }
    }
    Locale previous = Locale.getDefault();
    try {
      for (String tag : List.of("tr", "az")) {
        Locale.setDefault(Locale.forLanguageTag(tag));
        var genders = patientFilters(FULL_MAPPING, "gender=male,female");
        assertFilter(genders, TEA_GENDER, IN, "m", "f");
        assertFilter(patientFilters(FULL_MAPPING, "gender=other"), TEA_GENDER, IN, "o", "x");
        assertFilter(patientFilters(FULL_MAPPING, "family=rain"), TEA_FAMILY, SW, "rain");
        assertFilter(
            patientFilters(FULL_MAPPING, "birthdate=le2000-01-15"), TEA_BIRTH, LE, "2000-01-15");
      }
    } finally {
      Locale.setDefault(previous);
    }
    var params = translatePatient(FULL_MAPPING, "family=a/b:c").trackedEntityParams();
    assertEquals(TEA_FAMILY + ":SW:a//b/:c", params.getFilter());
    assertFilter(filters(params), TEA_FAMILY, SW, "a/b:c");
    var comma = translatePatient(FULL_MAPPING, "family=a\\,b").trackedEntityParams();
    assertEquals(TEA_FAMILY + ":SW:a/,b", comma.getFilter());
    String token = FhirSearchTranslator.escape("a/,b:c");
    assertEquals("a///,b/:c", token);
    assertFilter(FilterParser.parseFilters(TEA_FAMILY + ":sw:" + token), TEA_FAMILY, SW, "a/,b:c");
    assertFilter(patientFilters(FULL_MAPPING, "family=a\\,b"), TEA_FAMILY, SW, "a,b");
    assertFilter(patientFilters(FULL_MAPPING, "family=a\\\\b"), TEA_FAMILY, SW, "a\\b");
    assertFilter(patientFilters(FULL_MAPPING, "family=a\\b"), TEA_FAMILY, SW, "a\\b");
    assertFilter(patientFilters(FULL_MAPPING, "given=\\$x\\|y\\"), TEA_GIVEN, SW, "$x|y\\");
    String system = "identifier=urn:test:ident|";
    assertFilter(patientFilters(FULL_MAPPING, system + "A\\,B"), TEA_IDENT, EQ, "A,B");
    assertFilter(patientFilters(FULL_MAPPING, system + "A\\|B"), TEA_IDENT, EQ, "A|B");
    assertFilter(patientFilters(FULL_MAPPING, "identifier=A\\|B"), TEA_IDENT, EQ, "A|B");
    assertAllInvalid(
        query -> translatePatient(FULL_MAPPING, query),
        """
        family=a,b family=a\\\\,b _id=TrackedEnt1\\,TrackedEnt2 gender=male\\,female
        identifier=urn:test:ident|A|B identifier=urn:test:ident\\|A|B|C birthdate=2000-01-01\\,
        """);
    String patient = "patient=" + TE_1 + "&code=";
    assertCodes(patient + LOINC + "\\|" + HEIGHT, false, false);
    assertCodes(patient + HEIGHT + "\\," + WEIGHT, false, false);
    assertCodes(patient + "8302\\-2," + LOINC + "|" + WEIGHT, false, true);
    assertInvalid("code", () -> translateEvents(OBSERVATION, patient + LOINC + "|8302-2|\\"));
  }

  @Test
  void multiParameterRequestNamesOnlyOffendingParameter() throws Exception {
    Consumer<String> patient = query -> translatePatient(FULL_MAPPING, query);
    assertOnlyNamed("birthdate", "family=rain&given=Fra&birthdate=2000-01", patient);
    assertOnlyNamed("_page", "_id=" + TE_1 + "&family=rain&_page=0", patient);
    assertOnlyNamed("_page", "_count=50&_page=42949674", patient);
    Consumer<String> encounter = q -> translateEvents(ENCOUNTER, q);
    Consumer<String> observation = q -> translateEvents(OBSERVATION, q);
    assertOnlyNamed("_id", "patient=" + TE_1 + "&_count=5&_id=bad", encounter);
    assertOnlyNamed("_format", "patient=" + TE_1 + "&_page=2&_format=xml", observation);
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
    assertAllInvalid(
        patient,
        """
        foo=1 family:exact=x patient=TrackedEnt1 _sort=family _include=Patient:organization
        _revinclude=Encounter:subject _summary=true _elements=name _total=accurate _type=Patient
        family=a&family=b family= family=a,b _id=bad _id=TrackedEnt1, identifier=urn:other|1
        identifier=|ABC123 identifier=urn:test:ident| identifier=urn:test:ident|X|Y
        birthdate=0000-01-01 birthdate=ge0000-12-31 birthdate=2000-01 birthdate=2000
        birthdate=ne2000-01-01 birthdate=sa2000-01-01 birthdate=ge2000-13-45 gender=bogus
        gender=male, _count=0 _count=abc _count=101 _page=0 _page=abc _format=xml
        """);
    var urnA = Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, TEA_IDENT).system("urn:a");
    var urnB = Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, TEA_IDENT_2).system("urn:b");
    var twoIdentifiers = patientWith(Map.of(TEA_IDENT, TEXT, TEA_IDENT_2, TEXT), urnA, urnB);
    assertAllInvalid(q -> translatePatient(twoIdentifiers, q), "identifier=1");
    assertFilter(patientFilters(twoIdentifiers, "identifier=urn:b|1"), TEA_IDENT_2, EQ, "1");
    var familyName = Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, TEA_FAMILY);
    var familyOnly = patientWith(Map.of(TEA_FAMILY, TEXT), familyName);
    assertAllInvalid(
        q -> translatePatient(familyOnly, q),
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
    assertEquals(List.of(1, false), List.of(read.getPageSize(), read.isTotalPages()));
    assertEquals(EXPECTED_PATIENT_FIELDS, read.getFields());
    assertNull(read.getFilter());
    TranslatedSearch paged = translatePatient(FULL_MAPPING, "_count=10&_page=3");
    TrackedEntityRequestParams params = paged.trackedEntityParams();
    assertEquals(List.of(10, 3), List.of(params.getPageSize(), params.getPage()));
    assertEquals(List.of(10, 3), List.of(paged.count(), paged.page()));
    assertFalse(params.isTotalPages());
    assertEquals(EXPECTED_PATIENT_FIELDS, params.getFields());
    var defaults = translatePatient(FULL_MAPPING, "family=rain").trackedEntityParams();
    assertEquals(List.of(50, 1), List.of(defaults.getPageSize(), defaults.getPage()));
    assertEquals(42949673, translatePatient(FULL_MAPPING, "_count=50&_page=42949673").page());
    assertDoesNotThrow(() -> translatePatient(FULL_MAPPING, "family=rain&_format=json"));
    TranslatedSearch min = translatePatient(FULL_MAPPING, "_count=1");
    assertEquals(List.of(1, 1), List.of(min.count(), min.trackedEntityParams().getPageSize()));
    assertEquals(100, translatePatient(FULL_MAPPING, "_count=100").count());
    when(settings.getTrackedEntityMaxLimit()).thenReturn(10);
    assertInvalid("_count", () -> translatePatient(FULL_MAPPING, "family=rain"), "family");
    for (int limit : new int[] {0, -1, Integer.MAX_VALUE}) {
      when(settings.getTrackedEntityMaxLimit()).thenReturn(limit);
      assertEquals(5000, translatePatient(FULL_MAPPING, "_count=5000").count());
      var max = translatePatient(FULL_MAPPING, "_count=2147483647&_page=2");
      var size = max.trackedEntityParams().getPageSize();
      assertEquals(List.of(2147483646, 2147483646, 2), List.of(max.count(), size, max.page()));
    }
  }

  @Test
  void nulFirewalledAndUndecodableInputIsInvalidNamingTheOffendingParameter() {
    Consumer<String> patient = q -> translatePatient(FULL_MAPPING, q);
    Consumer<String> observation = q -> translateEvents(OBSERVATION, q);
    Consumer<String> metadata = q -> parameters.checkFormatOnly(Operation.METADATA, request(q));
    String detail = assertInvalid("family", () -> patient.accept("family=" + NUL));
    assertTrue(detail.endsWith("must not contain NUL characters"), detail);
    assertAll(
        () -> assertOnlyNamed("family", "given=Fra&family=rainy day" + NUL + "x", patient),
        () -> assertOnlyNamed("given", "given=Fr" + NUL, patient),
        () -> assertOnlyNamed("identifier", "identifier=urn:test:ident|x" + NUL, patient),
        () -> assertOnlyNamed("identifier", "identifier=" + NUL + "&family=rain", patient),
        () -> assertOnlyNamed("_id", "_id=" + TE_1 + NUL, patient),
        () -> assertOnlyNamed("_format", "family=rain&_format=json" + NUL, patient),
        () -> assertOnlyNamed("patient", "patient=" + TE_1 + NUL, observation),
        () -> assertOnlyNamed("code", "patient=" + TE_1 + "&code=" + HEIGHT + NUL, observation),
        () -> assertOnlyNamed("_format", "_format=" + NUL + "json", metadata));
    HttpServletRequest lineFeed = firewalled("fam%0Aily=rain");
    assertThrows(RequestRejectedException.class, lineFeed::getParameterMap);
    String search = assertInvalid("fam%0Aily", () -> parsePatient(lineFeed));
    assertTrue(search.endsWith("is not a supported search parameter"), search);
    Consumer<String> firewall = q -> parameters.checkFormatOnly(Operation.METADATA, firewalled(q));
    String format = assertInvalid("a%09b", () -> firewall.accept("a%09b=1"));
    assertTrue(format.endsWith("is not a supported parameter"), format);
    assertInvalid("a%0D%0AX-Injected: yes", () -> firewall.accept("a%0D%0AX-Injected:%20yes=1"));
    HttpServletRequest encounter = firewalled("patient=" + TE_1 + "&x%0D=1");
    assertInvalid("x%0D", () -> parameters.parse(Operation.ENCOUNTER_SEARCH, encounter, null));
    assertInvalid("fam%0Aily", () -> translatePatient(FULL_MAPPING, "fam\nily=rain"));
    assertInvalid("a%C2%85b", () -> translatePatient(FULL_MAPPING, "a\u0085b=1"));
    for (String value : List.of("%ZZ", "%C3%28", "%FF", "ab%4", "%")) {
      String bad = assertInvalid("family", () -> parsePatient(undecodable("family=" + value)));
      assertTrue(bad.endsWith("value is not valid percent-encoded UTF-8"), bad);
    }
    String name = assertInvalid("%ZZ", () -> parsePatient(undecodable("%ZZ=1")));
    assertTrue(name.endsWith("name is not valid percent-encoded UTF-8"), name);
    assertInvalid(
        "_format", () -> parameters.checkFormatOnly(Operation.READ, undecodable("_format=%ZZ")));
    assertInvalid(
        "x", () -> parameters.checkFormatOnly(Operation.EVERYTHING, undecodable("x=%C3%28")));
    assertInvalid("foo", () -> parsePatient(undecodable("foo=1&family=%ZZ")), "family");
    String repeated = assertInvalid("family", () -> parsePatient(undecodable("family=a&family=b")));
    assertTrue(repeated.endsWith("must not be repeated"), repeated);
    for (String valid : Arrays.asList("family=rain&&_count=5&given=Fr%C3%A9+d", null)) {
      MockHttpServletRequest req = undecodable(valid);
      assertSame(UNDECODABLE, assertThrows(IllegalStateException.class, () -> parsePatient(req)));
    }
  }

  @Test
  void attributeConstraintsNameOnlyOffendingParameter() throws BadRequestException {
    TranslatedSearch search = translatePatient(FULL_MAPPING, "_id=" + TE_1 + "," + TE_2);
    assertNull(search.trackedEntityParams().getFilter());
    assertEquals(Map.of(), search.origin().attributeToParameter());
    assertEquals(List.of(), search.origin().suppliedAttributeParameters());
    assertFalse(search.empty());
    String code = "patient=" + TE_1 + "&code=";
    assertCodes(code + LOINC + "|" + HEIGHT, true, false);
    assertCodes(code + HEIGHT, true, false);
    assertCodes(code + HEIGHT + "," + WEIGHT, true, true);
    assertCodes(code + "http://other|" + HEIGHT, false, false);
    assertCodes(code + "unknown", false, false);
    assertCodes("patient=" + TE_1, true, true);
    List<String> ids = Stream.iterate(0, i -> i + 1).limit(500).map("Te%09d"::formatted).toList();
    List<String> obs = ids.stream().map(id -> id + "-" + EVT + "-" + DE_1).toList();
    String idList = String.join(",", ids);
    var patients = translatePatient(FULL_MAPPING, "_id=" + idList);
    assertEquals(UID.of(ids), patients.trackedEntityParams().getTrackedEntities());
    var events = translateEvents(OBSERVATION, "_id=" + String.join(",", obs));
    assertEquals(Set.copyOf(obs), events.logicalIds());
    String males = "gender=" + String.join(",", Collections.nCopies(500, "male"));
    assertEquals(patientFilters(FULL_MAPPING, "gender=male"), patientFilters(FULL_MAPPING, males));
    assertCodes(code + idList + "," + HEIGHT, true, false);
    assertCodes(code + "x".repeat(8192), false, false);
    assertInvalid("_id", () -> translatePatient(FULL_MAPPING, "_id=" + idList + ",bad"));
    ResolvedMapping blockedFamily = patientMapping(null, Map.of(TEA_FAMILY, Set.of(SW)), Map.of());
    assertOnlyNamed("family", "family=rain&given=Frank", q -> translatePatient(blockedFamily, q));
    ResolvedMapping shortGiven = patientMapping(null, Map.of(), Map.of(TEA_GIVEN, 3));
    assertOnlyNamed("given", "family=rain&given=Fr", q -> translatePatient(shortGiven, q));
    assertFilter(patientFilters(shortGiven, "given=Fra"), TEA_GIVEN, SW, "Fra");
    var intId = Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, TEA_INTEGER).system("urn:test:int");
    var familyName = Entry.field(PATIENT_FAMILY_NAME, ATTRIBUTE, TEA_FAMILY);
    var integer = patientWith(Map.of(TEA_INTEGER, INTEGER, TEA_FAMILY, TEXT), intId, familyName);
    String query = "identifier=urn:test:int|abc&family=rain";
    assertOnlyNamed("identifier", query, q -> translatePatient(integer, q));
    assertFilter(patientFilters(integer, "identifier=urn:test:int|42"), TEA_INTEGER, EQ, "42");
    ResolvedMapping shortGender = patientMapping(null, Map.of(), Map.of(TEA_GENDER, 2));
    assertOnlyNamed("gender", "gender=male&family=rain", q -> translatePatient(shortGender, q));
    assertFilter(patientFilters(shortGender, "gender=other"), TEA_GENDER, IN, "o", "x");
  }

  @Test
  void eventPatientAndSubjectTranslateToTrackedEntityOfOneProgram() {
    var references = List.of("patient=", "patient=Patient/", "subject=", "subject=Patient/");
    List<EnrollmentRequestParams> all = new ArrayList<>();
    for (FhirResourceType type : List.of(ENCOUNTER, IMMUNIZATION, OBSERVATION)) {
      for (String reference : references.subList(0, type == IMMUNIZATION ? 2 : 4)) {
        TranslatedSearch search = translateEvents(type, reference + TE_1);
        EnrollmentRequestParams params = search.enrollmentParams();
        assertEquals(UID.of(TE_1), params.getTrackedEntity());
        assertEquals(Set.of(), params.getEnrollments());
        assertEquals(FhirSearchOrigin.empty(), search.origin());
        assertFalse(search.empty());
        all.add(params);
      }
    }
    EnrollmentRequestParams read = translator.eventReadParams(ENR, PROGRAM);
    assertEquals(Set.of(UID.of(ENR)), read.getEnrollments());
    assertNull(read.getTrackedEntity());
    EnrollmentRequestParams everything = translator.everythingParams(TE_1, PROGRAM);
    assertEquals(UID.of(TE_1), everything.getTrackedEntity());
    assertEquals(Set.of(), everything.getEnrollments());
    Collections.addAll(all, read, everything);
    for (EnrollmentRequestParams params : all) {
      assertEquals(UID.of(PROGRAM), params.getProgram());
      assertFalse(params.isPaging());
      assertFalse(params.isTotalPages());
      assertEquals(EXPECTED_EVENT_FIELDS, params.getFields());
    }
    TranslatedSearch paged = translateEvents(ENCOUNTER, "patient=" + TE_1 + "&_count=2&_page=3");
    assertEquals(List.of(2, 3), List.of(paged.count(), paged.page()));
    assertNull(paged.enrollmentParams().getPageSize());
    assertNull(paged.enrollmentParams().getPage());
    TranslatedSearch defaults = translateEvents(OBSERVATION, "patient=" + TE_1);
    assertEquals(List.of(50, 1), List.of(defaults.count(), defaults.page()));
    for (FhirResourceType type : List.of(ENCOUNTER, IMMUNIZATION, OBSERVATION)) {
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
    String withTe = "&patient=" + TE_1;
    for (FhirResourceType type : List.of(ENCOUNTER, IMMUNIZATION, OBSERVATION)) {
      assertAllInvalid(
          query -> translateEvents(type, query),
          "patient=Patient/bad patient=Group/TrackedEnt1 subject=Patient/bad");
      assertAllInvalid(
          query -> translateEvents(type, query + withTe),
          "_id=bad foo=1 family=rain _count=abc _count=0 _page=0 _format=xml"
              + " _page=2147483647&_count=50");
      String missing = assertInvalid("patient", () -> translateEvents(type, ""));
      String required = type == IMMUNIZATION ? "patient or _id" : "patient, subject or _id";
      assertTrue(missing.endsWith("': " + required + " is required"), missing);
      assertInvalid("patient", () -> translateEvents(type, "_count=5"), "_count");
      assertInvalid("subject", () -> translateEvents(type, "subject=" + TE_2 + withTe));
    }
    assertAllInvalid(q -> translateEvents(ENCOUNTER, q + withTe), "_id=" + PER_DE_ID + " code=x");
    assertAllInvalid(
        query -> translateEvents(IMMUNIZATION, query), "_id=" + ENCOUNTER_ID + " subject=" + TE_1);
    assertAllInvalid(
        query -> translateEvents(OBSERVATION, query + withTe),
        """
        _id=Enrollment1-EventUid001 code=http://loinc.org| code=8302-2,,
        code=http://loinc.org|8302-2|x code=29463-7,http://loinc.org|8302-2|x
        """);
    assertInvalid("patient", () -> translateEvents(OBSERVATION, "code=" + HEIGHT), "code");
    for (Operation operation : List.of(Operation.READ, Operation.EVERYTHING, Operation.METADATA)) {
      Consumer<String> check = query -> parameters.checkFormatOnly(operation, request(query));
      List.of("json", "application/json", "application/fhir+json", "application/fhir json")
          .forEach(format -> assertDoesNotThrow(() -> check.accept("_format=" + format)));
      assertAllInvalid(check, "foo=1 _format=xml _format=json&_format=json _format= family=rain");
      assertInvalid("_format", () -> check.accept("_format=application/fhir xml"));
    }
    Executable patient = () -> parameters.checkFormatOnly(Operation.PATIENT_SEARCH, request(""));
    assertThrows(IllegalArgumentException.class, patient);
  }
}
