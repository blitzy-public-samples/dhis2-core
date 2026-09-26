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
import static org.hisp.dhis.http.HttpAssertions.assertStatus;
import static org.hisp.dhis.http.HttpClientAdapter.Accept;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.Stream;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.*;
import org.hisp.dhis.user.User;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Tests the FHIR R4 {@code Patient} read, search-type and {@code $everything} operations. */
class FhirPatientControllerTest extends FhirPostgresControllerTestBase {
  private static final String MAPPING_URL = "/fhirResourceMappings/FhirMapPat1";
  private static final String OTHER_TYPE_TRACKED_ENTITY = "XUitxQbWYNq";
  private static final String IDENTIFIER_SYSTEM = "urn:dhis2:fhir-test:integer-attr";
  private static final String FAMILY_ATTRIBUTE = "toUpdate000";
  private static final String OTHER_ORG_UNIT = "DiszpKrYNg8";
  private static final String PATIENT_PATH = "/api/fhir/Patient";
  private static final String MAX_LIMIT_SETTING = "KeyTrackedEntityMaxLimit";
  private static final List<String> SUMMER_EVERYTHING =
      List.of(
          "Patient/" + SUMMER,
          "Encounter/nxP7UnKhomJ-pTzf9KYMk72",
          "Immunization/nxP7UnKhomJ-pTzf9KYMk72-DATAEL00001",
          "Observation/nxP7UnKhomJ-pTzf9KYMk72-DATAEL00006");

  @Test
  void readPatientReturnsValidMappedPatient() {
    assertFrankPatient(read(Patient.class, FRANK));
  }

  @Test
  void readPatientWithProgramScopedMapping() {
    String typeScoped = fhirBody(GET(PATIENT_PATH + "/" + FRANK), HttpStatus.OK);
    try {
      patch(MAPPING_URL, "[{'op':'add','path':'/program','value':{'id':'" + PROGRAM + "'}}]");
      assertEquals(PROGRAM, mappingProgram().getString("id").string());
      String programScoped = fhirBody(GET(PATIENT_PATH + "/" + FRANK), HttpStatus.OK);
      assertFrankPatient(parse(programScoped, Patient.class));
      assertEquals(typeScoped, programScoped);
    } finally {
      patch(MAPPING_URL, "[{'op':'remove','path':'/program'}]");
    }
    assertTrue(mappingProgram().isUndefined());
  }

  @Test
  void readPatientAcceptsFormatAndRejectsOtherParameters() {
    assertFrankPatient(read(Patient.class, FRANK + "?_format=json"));
    assertInvalid(PATIENT_PATH + "/" + FRANK + "?_format=xml", "_format");
    assertInvalid(PATIENT_PATH + "/" + FRANK + "?foo=1", "foo");
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
  void patientEverythingReturnsPatientAndEventResources() {
    assertEverything(SUMMER, SUMMER_EVERYTHING);
    assertEverything(
        FRANK,
        List.of(
            "Patient/" + FRANK,
            "Encounter/TvctPPhpD8z-D9PbzJY8bJM",
            "Immunization/TvctPPhpD8z-D9PbzJY8bJM-DATAEL00001",
            "Observation/TvctPPhpD8z-D9PbzJY8bJM-DATAEL00006",
            "Observation/TvctPPhpD8z-D9PbzJY8bJM-GieVkTxp4HH"));
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
        """
        foo=1 family:exact=rain family=rain&family=sun _count=abc _count=0 _page=0 _page=x _id=bad
        identifier=urn:unknown|70 birthdate=2000-01-01 gender=male _format=xml
        """;
    for (String query : queries.strip().split("\\s+")) {
      assertInvalid(PATIENT_PATH + "?" + query, query.substring(0, query.indexOf('=')));
    }
    String previousLimit =
        GET("/systemSettings/{key}", MAX_LIMIT_SETTING, Accept("text/plain")).content("text/plain");
    try {
      setMaxLimit("2");
      assertInvalid(PATIENT_PATH + "?family=rain&_count=3", "_count");
      Bundle atLimit =
          assertPatientSearchset(PATIENT_PATH + "?_id=" + SUMMER + "," + FRANK + "&_count=2");
      assertEquals(Set.of(SUMMER, FRANK), Set.copyOf(entryIds(atLimit)));
      assertNull(atLimit.getLink(Bundle.LINK_NEXT));
    } finally {
      setMaxLimit(previousLimit);
    }
  }

  @Test
  void searchWithNonSearchableAttributeOutsideCaptureScopeReturnsInvalid() {
    User user = userWithScope(OTHER_ORG_UNIT, ROOT_ORG_UNIT);
    String query = PATIENT_PATH + "?family=summer";
    assertTrue(entryIds(assertPatientSearchset(query)).contains(SUMMER), query);
    asUser(user, () -> assertFalse(entryIds(assertPatientSearchset(query)).isEmpty()));
    try {
      setTypeAttributeSearchable(FAMILY_ATTRIBUTE, false);
      asUser(user, () -> assertInvalid(query, "family"));
    } finally {
      setTypeAttributeSearchable(FAMILY_ATTRIBUTE, true);
    }
  }

  @Test
  void searchRejectsAttributeConstraintViolations() {
    String search = PATIENT_PATH + "?family=rain&";
    assertInvalid(search + "identifier=" + IDENTIFIER_SYSTEM + "|abc", "identifier");
    Runnable givenTooShort = () -> assertInvalid(search + "given=Fr", "given");
    withAttributeSetting(GIVEN_ATTRIBUTE, "minCharactersToSearch", "3", givenTooShort);
    Runnable familyBlocked = () -> assertInvalid(search + "given=Fra", "family");
    withAttributeSetting(FAMILY_ATTRIBUTE, "blockedSearchOperators", "['SW']", familyBlocked);
    assertFalse(entryIds(assertPatientSearchset(search + "given=Fra")).isEmpty());
  }

  @Test
  void readUnknownPatientReturnsNotFound() {
    assertFrankPatient(read(Patient.class, FRANK));
    for (String id : List.of(generateUid(), OTHER_TYPE_TRACKED_ENTITY, "bad", FRANK + "x")) {
      assertNotFound(GET(PATIENT_PATH + "/" + id));
    }
    assertNotFound(GET(PATIENT_PATH + "/TvctPPhpD8z-D9PbzJY8bJM"));
  }

  @Test
  void readPatientOutsideUserScopeReturnsNotFound() {
    read(Patient.class, SUMMER);
    User user = userWithScope(OTHER_ORG_UNIT, OTHER_ORG_UNIT);
    asUser(user, () -> assertNotFound(GET(PATIENT_PATH + "/" + SUMMER)));
  }

  @Test
  void forbiddenResponseIsIdenticalForExistingAndUnknownIds() {
    asImportAndBaselineUser(() -> read(Patient.class, SUMMER));
    String unknown = generateUid();
    List<String> reads = List.of(PATIENT_PATH + "/" + SUMMER, PATIENT_PATH + "/" + unknown);
    Set<String> bodies = new HashSet<>();
    for (String typeAccess : List.of(NO_ACCESS, METADATA_ONLY)) {
      asRestrictedUser(
          List.of(new Restriction("trackedEntityType", PERSON_TYPE, typeAccess)),
          () -> bodies.add(assertSameForbidden(reads, SUMMER, unknown)));
    }
    assertEquals(1, bodies.size(), bodies::toString);
  }

  @Test
  void searchPatientsForbiddenWithoutTypeReadAccess() {
    String search = PATIENT_PATH + "?";
    assertEquals(List.of(SUMMER), entryIds(assertPatientSearchset(search + "_id=" + SUMMER)));
    String unknown = generateUid();
    List<String> searches =
        List.of(search + "_id=" + SUMMER, search + "family=summer", search + "_id=" + unknown);
    asRestrictedUser(
        List.of(new Restriction("trackedEntityType", PERSON_TYPE, NO_ACCESS)),
        () -> assertSameForbidden(searches, SUMMER, unknown));
  }

  @Test
  void patientEverythingOmitsUnreadableProgram() {
    asImportAndBaselineUser(() -> assertEverything(SUMMER, SUMMER_EVERYTHING));
    for (String programAccess : List.of(NO_ACCESS, METADATA_ONLY)) {
      asRestrictedUser(
          List.of(new Restriction("program", PROGRAM, programAccess)),
          () -> {
            String body = assertEverything(SUMMER, List.of("Patient/" + SUMMER));
            for (String hidden :
                List.of("pTzf9KYMk72", "nxP7UnKhomJ", "DATAEL00001", "DATAEL00006")) {
              assertFalse(body.contains(hidden), () -> hidden + " in " + body);
            }
          });
    }
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
          assertFalse(body.contains("Frank"), body);
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

  /** Asserts a {@link #searchset} of Patients without a total. */
  private Bundle assertPatientSearchset(String url) {
    Bundle bundle = searchset(url, fhirBody(GET(url), HttpStatus.OK));
    assertFalse(bundle.hasTotal(), url);
    return bundle;
  }

  /** Asserts the {@code $everything} {@link #searchset} entries as {@code Type/id}, in order. */
  private String assertEverything(String patientId, List<String> expected) {
    String url = PATIENT_PATH + "/" + patientId + "/$everything";
    String body = fhirBody(GET(url), HttpStatus.OK);
    Bundle bundle = searchset(url, body);
    List<String> actual =
        bundle.getEntry().stream()
            .map(entry -> entry.getResource().fhirType() + "/" + entry.getResource().getIdPart())
            .toList();
    assertEquals(expected, actual, body);
    assertEquals(expected.size(), bundle.getTotal(), body);
    assertEquals(1, bundle.getLink().size(), body);
    return body;
  }

  private JsonObject mappingProgram() {
    return GET(MAPPING_URL + "?fields=program[id]").content().getObject("program");
  }

  private void patch(String url, String jsonPatch) {
    assertStatus(HttpStatus.OK, PATCH(url, jsonPatch));
    manager.clear();
  }

  /** Runs {@code test} with an attribute property set to {@code json}, then restores it. */
  private void withAttributeSetting(String attribute, String property, String json, Runnable test) {
    String url = "/trackedEntityAttributes/" + attribute;
    String add = "[{'op':'add','path':'/" + property + "','value':%s}]";
    JsonValue previous = GET(url + "?fields=" + property).content().get(property);
    String restore = previous.isUndefined() || previous.isNull() ? "[]" : previous.toJson();
    patch(url, add.formatted(json));
    try {
      test.run();
    } finally {
      patch(url, add.formatted(restore));
    }
  }

  private void setTypeAttributeSearchable(String attribute, boolean searchable) {
    String type = "/trackedEntityTypes/" + PERSON_TYPE;
    String fields = "?fields=trackedEntityTypeAttributes[trackedEntityAttribute[id]]";
    List<String> attributes =
        GET(type + fields).content().getArray("trackedEntityTypeAttributes").stream()
            .map(a -> a.getObject("trackedEntityAttribute").getString("id").string())
            .toList();
    int index = attributes.indexOf(attribute);
    assertTrue(index >= 0, attributes::toString);
    String path = "/trackedEntityTypeAttributes/" + index + "/searchable";
    patch(type, "[{'op':'replace','path':'" + path + "','value':" + searchable + "}]");
  }

  private void setMaxLimit(String value) {
    assertStatus(HttpStatus.OK, POST("/systemSettings/" + MAX_LIMIT_SETTING + "?value=" + value));
    manager.clear();
  }
}
