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

import static org.hisp.dhis.common.CodeGenerator.generateUid;
import static org.hisp.dhis.fhir.FhirResourceMappingStoreTest.FhirResponses.*;
import static org.hisp.dhis.http.HttpAssertions.assertStatus;
import static org.hisp.dhis.http.HttpClientAdapter.Accept;
import static org.hisp.dhis.http.HttpClientAdapter.Header;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.Stream;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.fhir.FhirResourceMappingStoreTest.FhirPostgresControllerTestBase;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.*;
import org.hisp.dhis.user.User;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Tests the FHIR R4 {@code Patient} read, search-type and {@code $everything} operations. */
class FhirPatientControllerTest extends FhirPostgresControllerTestBase {
  private static final String MAPPING = "/fhirResourceMappings/FhirMapPat1";
  private static final String OTHER_ORG_UNIT = "DiszpKrYNg8";
  private static final String PATIENT_PATH = "/api/fhir/Patient";
  private static final String[] SUMMER_EVERYTHING = {
    "Patient/" + SUMMER,
    "Encounter/nxP7UnKhomJ-pTzf9KYMk72",
    "Immunization/nxP7UnKhomJ-pTzf9KYMk72-DATAEL00001",
    "Observation/nxP7UnKhomJ-pTzf9KYMk72-DATAEL00006"
  };

  @Test
  void readPatientReturnsValidMappedPatient() {
    assertFrankPatient(read(Patient.class, FRANK));
  }

  @Test
  void readPatientWithProgramScopedMapping() throws Exception {
    String typeScoped = fhirBody(GET(PATIENT_PATH + "/" + FRANK), HttpStatus.OK);
    String mappingProgram = MAPPING + "?fields=program[id]";
    String program = "/programs/" + PROGRAM;
    String limit =
        GET(program + "?fields=maxTeiCountToReturn").content().get("maxTeiCountToReturn").toJson();
    String replace = "[{'op':'replace','path':'/maxTeiCountToReturn','value':%s}]";
    String scope = "[{'op':'add','path':'/program','value':{'id':'" + PROGRAM + "'}}]";
    User user = userWithScope(OTHER_ORG_UNIT, ROOT_ORG_UNIT);
    try (var scoped = patched(MAPPING, scope, "[{'op':'remove','path':'/program'}]")) {
      assertEquals(PROGRAM, GET(mappingProgram).content().getString("program.id").string());
      String programScoped = fhirBody(GET(PATIENT_PATH + "/" + FRANK), HttpStatus.OK);
      assertFrankPatient(parse(programScoped, Patient.class));
      assertEquals(typeScoped, programScoped);
      String one = PATIENT_PATH + "?_id=" + SUMMER;
      try (var limited = patched(program, replace.formatted("1"), replace.formatted(limit))) {
        asUser(user, () -> assertEquals(List.of(SUMMER), entryIds(assertPatientSearchset(one))));
        asUser(user, () -> assertInvalid(PATIENT_PATH + "?_id=" + SUMMER + "," + FRANK, "_id"));
      }
    }
    assertTrue(GET(mappingProgram).content().get("program").isUndefined());
  }

  @Test
  void readPatientAcceptsFormatAndRejectsOtherParameters() {
    assertFrankPatient(assertReadAcceptsOnlyFormat(Patient.class, FRANK));
  }

  static Stream<Arguments> patientSearches() {
    Set<String> both = Set.of(SUMMER, FRANK);
    String paged = "_id=" + SUMMER + "," + FRANK + "&_count=1&_page=";
    return Stream.of(
        Arguments.of("_id=" + SUMMER, Set.of(SUMMER), 1, false, false),
        Arguments.of("_id=" + SUMMER + "," + FRANK, both, 2, false, false),
        Arguments.of("identifier=" + IDENTIFIER_SYSTEM + "|70", Set.of(FRANK), 1, false, false),
        Arguments.of("identifier=88", Set.of(SUMMER), 1, false, false),
        Arguments.of("family=rain", Set.of(FRANK), 1, false, false),
        Arguments.of("given=frank", Set.of(FRANK), 1, false, false),
        Arguments.of("family=rain&_format=json", Set.of(FRANK), 1, false, false),
        Arguments.of("family=a\\,b", Set.of(), 0, false, false),
        Arguments.of(paged + "1", both, 1, true, false),
        Arguments.of(paged + "2", both, 1, false, true),
        Arguments.of(paged + "3", both, 0, false, true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("patientSearches")
  void searchPatientsByEachSupportedParameter(
      String query, Set<String> ids, int entries, boolean next, boolean previous) {
    Bundle bundle = assertPatientSearchset(PATIENT_PATH + "?" + query);
    List<String> found = entryIds(bundle);
    assertEquals(entries, found.size(), () -> query + ": " + found);
    assertEquals(next, bundle.getLink(Bundle.LINK_NEXT) != null, query);
    assertEquals(previous, bundle.getLink(Bundle.LINK_PREV) != null, query);
    Set<String> allPages = new HashSet<>(found);
    for (String relation : List.of(Bundle.LINK_NEXT, Bundle.LINK_PREV)) {
      Bundle page = bundle;
      for (int step = 0; page.getLink(relation) != null; step++) {
        assertTrue(step < ids.size(), () -> query + ": " + relation + " chain too long");
        String url = page.getLink(relation).getUrl();
        page = assertPatientSearchset(url.substring(url.indexOf("/api/")));
        List<String> linked = entryIds(page);
        assertFalse(linked.isEmpty(), url);
        assertTrue(linked.stream().noneMatch(allPages::contains), () -> url + ": " + linked);
        allPages.addAll(linked);
      }
    }
    assertEquals(ids, allPages, query);
  }

  @Test
  void searchUrlsIgnoreForwardedHeaders() throws Exception {
    String url = PATIENT_PATH + "?_id=" + SUMMER + "," + FRANK + "&_count=1";
    Set<String> ids = new HashSet<>();
    var config = webApplicationContext.getBean(DhisConfigurationProvider.class);
    try (var baseUrl = override(config, ConfigurationKey.SERVER_BASE_URL, OTHER_BASE_URL)) {
      forwardedPages(url, PATIENT_PATH).forEach(page -> ids.addAll(entryIds(page)));
    }
    assertEquals(Set.of(SUMMER, FRANK), ids);
  }

  @ParameterizedTest
  @ValueSource(strings = {"evil\"example", "evil example", "\"><x"})
  void pagedSearchWithMalformedForwardedHostReturnsSearchset(String host) {
    String url = PATIENT_PATH + "?_id=" + SUMMER + "," + FRANK + "&_count=1&_page=";
    Set<String> ids = new HashSet<>();
    for (String page : List.of("1", "2")) {
      Bundle bundle = parseOk(GET(url + page, Header("X-Forwarded-Host", host)), Bundle.class);
      assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType(), host);
      assertEquals(1, bundle.getEntry().size(), host);
      ids.addAll(entryIds(bundle));
    }
    assertEquals(Set.of(SUMMER, FRANK), ids, host);
  }

  @Test
  void patientEverythingReturnsPatientAndEventResources() {
    assertEverything(SUMMER, SUMMER_EVERYTHING);
    assertEverything(
        FRANK,
        "Patient/" + FRANK,
        "Encounter/TvctPPhpD8z-D9PbzJY8bJM",
        "Immunization/TvctPPhpD8z-D9PbzJY8bJM-DATAEL00001",
        "Observation/TvctPPhpD8z-D9PbzJY8bJM-DATAEL00006",
        "Observation/TvctPPhpD8z-D9PbzJY8bJM-GieVkTxp4HH");
  }

  @Test
  void patientEverythingRejectsParametersOtherThanFormat() {
    String everything = PATIENT_PATH + "/" + FRANK + "/$everything";
    assertEquals(FRANK, entryIds(parseOk(GET(everything + "?_format=json"), Bundle.class)).get(0));
    assertInvalid(everything + "?_count=1", "_count");
    assertInvalid(everything + "?foo=1", "foo");
  }

  @Test
  void searchPatientsRejectsInvalidParameters() {
    String queries =
        "foo=1 family:exact=rain family=rain&family=sun _count=abc _count=0 _page=0 _page=x _id=bad"
            + " identifier=urn:unknown|70 birthdate=2000-01-01 gender=male _format=xml";
    for (String query : queries.split(" "))
      assertInvalid(PATIENT_PATH + "?" + query, query.substring(0, query.indexOf('=')));
    String setting = "/systemSettings/KeyTrackedEntityMaxLimit";
    String previousLimit = GET(setting, Accept("text/plain")).content("text/plain");
    try {
      assertStatus(HttpStatus.OK, POST(setting + "?value=2"));
      manager.clear();
      assertInvalid(PATIENT_PATH + "?family=rain&_count=3", "_count");
      Bundle atLimit =
          assertPatientSearchset(PATIENT_PATH + "?_id=" + SUMMER + "," + FRANK + "&_count=2");
      assertEquals(Set.of(SUMMER, FRANK), Set.copyOf(entryIds(atLimit)));
      assertNull(atLimit.getLink(Bundle.LINK_NEXT));
    } finally {
      assertStatus(HttpStatus.OK, POST(setting + "?value=" + previousLimit));
      manager.clear();
    }
  }

  @Test
  void searchWithNonSearchableAttributeOutsideCaptureScopeReturnsInvalid() throws Exception {
    User user = userWithScope(OTHER_ORG_UNIT, ROOT_ORG_UNIT);
    String query = PATIENT_PATH + "?family=summer";
    assertTrue(entryIds(assertPatientSearchset(query)).contains(SUMMER), query);
    asUser(user, () -> assertFalse(entryIds(assertPatientSearchset(query)).isEmpty()));
    try (var notSearchable = unsearchable(FAMILY_ATTRIBUTE)) {
      asUser(user, () -> assertInvalid(query, "family"));
    }
    String entries = GET(MAPPING + "?fields=fieldMappings").content().get("fieldMappings").toJson();
    String replace = "[{'op':'replace','path':'/fieldMappings','value':%s}]";
    String byId = PATIENT_PATH + "?_id=" + SUMMER;
    try (var unmapped = patched(MAPPING, replace.formatted("[]"), replace.formatted(entries))) {
      asUser(user, () -> assertInvalid(PATIENT_PATH + "?_count=5", "_id"));
      asUser(user, () -> assertEquals(List.of(SUMMER), entryIds(assertPatientSearchset(byId))));
    }
    assertFrankPatient(read(Patient.class, FRANK));
  }

  @Test
  void searchRejectsAttributeConstraintViolations() throws Exception {
    String search = PATIENT_PATH + "?family=rain&";
    assertInvalid(search + "identifier=" + IDENTIFIER_SYSTEM + "|abc", "identifier");
    try (var minimum = attributeSetting(GIVEN_ATTRIBUTE, "minCharactersToSearch", "3")) {
      assertInvalid(search + "given=Fr", "given");
    }
    try (var blocked = attributeSetting(FAMILY_ATTRIBUTE, "blockedSearchOperators", "['SW']")) {
      assertInvalid(search + "given=Fra", "family");
    }
    assertFalse(entryIds(assertPatientSearchset(search + "given=Fra")).isEmpty());
  }

  @Test
  void readUnknownPatientReturnsNotFound() {
    List.of(generateUid(), "XUitxQbWYNq", "bad", FRANK + "x", "TvctPPhpD8z-D9PbzJY8bJM")
        .forEach(id -> assertNotFound(GET(PATIENT_PATH + "/" + id)));
  }

  @Test
  void readPatientOutsideUserScopeReturnsNotFound() {
    User user = userWithScope(OTHER_ORG_UNIT, OTHER_ORG_UNIT);
    asUser(user, () -> assertNotFound(GET(PATIENT_PATH + "/" + SUMMER)));
  }

  @Test
  void forbiddenResponseIsIdenticalForExistingAndUnknownIds() {
    asImportAndBaselineUser(() -> read(Patient.class, SUMMER));
    String unknown = generateUid();
    List<String> reads = List.of(PATIENT_PATH + "/" + SUMMER, PATIENT_PATH + "/" + unknown);
    Set<String> bodies = new HashSet<>();
    for (String typeAccess : List.of(NO_ACCESS, METADATA_ONLY))
      asRestrictedUser(
          List.of(new Restriction("trackedEntityType", PERSON, typeAccess)),
          () -> bodies.add(assertSameForbidden(reads, SUMMER, unknown)));
    assertEquals(1, bodies.size(), bodies::toString);
  }

  @Test
  void searchPatientsForbiddenWithoutTypeReadAccess() {
    String search = PATIENT_PATH + "?";
    String unknown = generateUid();
    List<String> searches =
        List.of(search + "_id=" + SUMMER, search + "family=summer", search + "_id=" + unknown);
    asRestrictedUser(
        List.of(new Restriction("trackedEntityType", PERSON, NO_ACCESS)),
        () -> assertSameForbidden(searches, SUMMER, unknown));
  }

  @Test
  void patientEverythingOmitsUnreadableProgram() {
    asImportAndBaselineUser(() -> assertEverything(SUMMER, SUMMER_EVERYTHING));
    String[] events = {"pTzf9KYMk72", "nxP7UnKhomJ", "DATAEL00001", "DATAEL00006"};
    for (String programAccess : List.of(NO_ACCESS, METADATA_ONLY))
      asRestrictedUser(
          List.of(new Restriction("program", PROGRAM, programAccess)),
          () -> assertOmits(assertEverything(SUMMER, "Patient/" + SUMMER), events));
  }

  @Test
  void hiddenAttributeIsAbsentFromPatient() {
    asImportAndBaselineUser(() -> assertFrankPatient(read(Patient.class, FRANK)));
    asRestrictedUser(
        List.of(new Restriction("trackedEntityAttribute", GIVEN_ATTRIBUTE, NO_ACCESS)),
        () -> {
          String body = fhirBody(GET(PATIENT_PATH + "/" + FRANK), HttpStatus.OK);
          Patient patient = parse(body, Patient.class);
          assertEquals(FRANK, patient.getIdPart());
          assertEquals(1, patient.getName().size(), body);
          assertEquals("rainy day", patient.getNameFirstRep().getFamily());
          assertFalse(patient.getNameFirstRep().hasGiven(), body);
          assertOmits(body, "Frank");
        });
  }

  private static void assertFrankPatient(Patient patient) {
    assertEquals(FRANK, patient.getIdElement().getIdPart());
    assertEquals(1, patient.getName().size());
    assertEquals("rainy day", patient.getNameFirstRep().getFamily());
    assertEquals(1, patient.getNameFirstRep().getGiven().size());
    assertEquals("Frank PTEA", patient.getNameFirstRep().getGiven().get(0).getValue());
    assertEquals(1, patient.getIdentifier().size());
    assertEquals(IDENTIFIER_SYSTEM, patient.getIdentifierFirstRep().getSystem());
    assertEquals("70", patient.getIdentifierFirstRep().getValue());
  }

  private Bundle assertPatientSearchset(String url) {
    Bundle bundle = searchset(url, fhirBody(GET(url), HttpStatus.OK));
    assertFalse(bundle.hasTotal(), url);
    return bundle;
  }

  private String assertEverything(String patientId, String... expected) {
    String url = PATIENT_PATH + "/" + patientId + "/$everything";
    String body = fhirBody(GET(url), HttpStatus.OK);
    Bundle bundle = searchset(url, body);
    List<String> actual =
        bundle.getEntry().stream()
            .map(entry -> entry.getResource().fhirType() + "/" + entry.getResource().getIdPart())
            .toList();
    assertEquals(List.of(expected), actual, body);
    assertEquals(expected.length, bundle.getTotal(), body);
    assertEquals(1, bundle.getLink().size(), body);
    return body;
  }

  private void patch(String url, String jsonPatch) {
    assertStatus(HttpStatus.OK, PATCH(url, jsonPatch));
    manager.clear();
  }

  /** Applies {@code jsonPatch} to {@code url}; closing the returned handle applies restore. */
  private AutoCloseable patched(String url, String jsonPatch, String restore) {
    patch(url, jsonPatch);
    return () -> patch(url, restore);
  }

  private AutoCloseable attributeSetting(String attribute, String property, String json) {
    String url = "/trackedEntityAttributes/" + attribute;
    String add = "[{'op':'add','path':'/" + property + "','value':%s}]";
    JsonValue previous = GET(url + "?fields=" + property).content().get(property);
    assertFalse(previous.isUndefined(), () -> url + " has no " + property);
    return patched(url, add.formatted(json), add.formatted(previous.toJson()));
  }

  private AutoCloseable unsearchable(String attribute) {
    String type = "/trackedEntityTypes/" + PERSON;
    String fields = "?fields=trackedEntityTypeAttributes[trackedEntityAttribute[id]]";
    List<String> attributes =
        GET(type + fields).content().getArray("trackedEntityTypeAttributes").stream()
            .map(a -> a.getObject("trackedEntityAttribute").getString("id").string())
            .toList();
    int index = attributes.indexOf(attribute);
    assertTrue(index >= 0, attributes::toString);
    String path = "/trackedEntityTypeAttributes/" + index + "/searchable";
    String searchable = "[{'op':'replace','path':'" + path + "','value':%s}]";
    return patched(type, searchable.formatted(false), searchable.formatted(true));
  }
}
