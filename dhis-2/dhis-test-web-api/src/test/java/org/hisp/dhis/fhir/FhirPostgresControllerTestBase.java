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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hisp.dhis.fhir.FhirResourceSerializer.FHIR_JSON_MEDIA_TYPE;
import static org.hisp.dhis.http.HttpAssertions.assertStatus;
import static org.junit.jupiter.api.Assertions.*;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.hisp.dhis.external.conf.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMapping;
import org.hisp.dhis.http.HttpStatus;
import org.hisp.dhis.jsontree.*;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.test.config.PostgresDhisConfigurationProvider;
import org.hisp.dhis.test.webapi.PostgresControllerIntegrationTestBase;
import org.hisp.dhis.user.User;
import org.hisp.dhis.webapi.controller.tracker.TestSetup;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.OperationOutcome.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;

/**
 * Base of the FHIR controller tests on PostgreSQL with {@code fhir.api.enabled=true}. It imports
 * the Tracker fixtures and {@value #MAPPINGS_FILE} once and deletes every mapping afterwards. Each
 * test starts with public access {@value #DATA_READ} on the person type and both programs and
 * {@value #METADATA_ONLY} on the given-name attribute.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ContextConfiguration(classes = FhirPostgresControllerTestBase.FhirApiEnabledConfig.class)
abstract class FhirPostgresControllerTestBase extends PostgresControllerIntegrationTestBase {
  static final String NO_ACCESS = "--------";
  static final String DATA_READ = "rwrw----";
  static final String METADATA_ONLY = "rw------";
  static final String ROOT_ORG_UNIT = "h4w96yEMlzO";
  static final String PERSON_TYPE = "ja8NY4PW7Xm";
  static final String PROGRAM = "BFcipDERJnf";
  static final String SECOND_PROGRAM = "shPjYNifvMK";
  static final String GIVEN_ATTRIBUTE = "dIVt4l5vIOa";
  static final String FRANK = "dUE514NMOlo";
  static final String SUMMER = "QS6w44flWAf";
  private static final String IMPORT_USER_UID = "tTgjgobT1oS";
  private static final String MAPPINGS_FILE = "fhir/fhir_resource_mappings.json";

  private final AtomicInteger userCounter = new AtomicInteger();
  @Autowired private TestSetup testSetup;
  User importUser;

  public static class FhirApiEnabledConfig {
    @Bean
    public DhisConfigurationProvider dhisConfigurationProvider() {
      Properties override = new Properties();
      override.put(ConfigurationKey.FHIR_API_ENABLED.getKey(), "true");
      PostgresDhisConfigurationProvider provider = new PostgresDhisConfigurationProvider(null);
      provider.addProperties(override);
      return provider;
    }
  }

  @BeforeAll
  void importFixtures() throws IOException {
    doInTransaction(() -> manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete));
    testSetup.importMetadata();
    importUser = userService.getUser(IMPORT_USER_UID);
    injectSecurityContextUser(importUser);
    testSetup.importTrackerData();
    manager.flush();
    manager.clear();
    testSetup.importMetadata(MAPPINGS_FILE);
    manager.flush();
    manager.clear();
  }

  @AfterAll
  void deleteFixtureMappings() {
    injectSecurityContextUser(importUser);
    doInTransaction(() -> manager.getAllNoAcl(FhirResourceMapping.class).forEach(manager::delete));
  }

  @BeforeEach
  void switchToImportUserWithBaselineSharing() {
    switchContextToUser(importUser);
    setPublicSharing("trackedEntityType", PERSON_TYPE, DATA_READ);
    setPublicSharing("program", PROGRAM, DATA_READ);
    setPublicSharing("program", SECOND_PROGRAM, DATA_READ);
    setPublicSharing("trackedEntityAttribute", GIVEN_ATTRIBUTE, METADATA_ONLY);
  }

  /** Parses FHIR JSON strictly: unknown elements and invalid values fail the parse. */
  static <T extends IBaseResource> T parse(String body, Class<T> type) {
    return FhirContext.forR4Cached()
        .newJsonParser()
        .setParserErrorHandler(new StrictErrorHandler())
        .parseResource(type, body);
  }

  static <T extends IBaseResource> T parseOk(HttpResponse response, Class<T> type) {
    return parse(fhirBody(response, HttpStatus.OK), type);
  }

  /** Reads {@code /api/fhir/{type}/{idAndQuery}}: a {@code 200} with the id and lastUpdated. */
  <T extends IBaseResource> T read(Class<T> type, String idAndQuery) {
    T resource = parseOk(GET("/api/fhir/" + type.getSimpleName() + "/" + idAndQuery), type);
    assertEquals(idAndQuery.split("\\?")[0], resource.getIdElement().getIdPart(), idAndQuery);
    assertNotNull(resource.getMeta().getLastUpdated(), idAndQuery);
    return resource;
  }

  /** Asserts the status and the FHIR JSON content type of a response and returns its body. */
  static String fhirBody(HttpResponse response, HttpStatus status) {
    assertEquals(status, response.status(), () -> response.contentUnchecked().toString());
    String body = response.content("application/fhir+json");
    assertEquals(FHIR_JSON_MEDIA_TYPE, MediaType.parseMediaType(response.getContentType()));
    return body;
  }

  /** Returns the logical ids of the entries of a Bundle in order, asserting that they differ. */
  static List<String> entryIds(Bundle bundle) {
    List<String> ids = bundle.getEntry().stream().map(e -> e.getResource().getIdPart()).toList();
    assertEquals(ids.size(), Set.copyOf(ids).size(), ids::toString);
    return ids;
  }

  /**
   * Parses a {@code searchset} whose only {@code self} link decodes to {@code url} and whose match
   * entries carry their {@code fullUrl}s and, for a type search, the searched type.
   */
  static Bundle searchset(String url, String body) {
    Bundle bundle = parse(body, Bundle.class);
    assertEquals(Bundle.BundleType.SEARCHSET, bundle.getType(), body);
    List<String> self =
        bundle.getLink().stream()
            .filter(link -> Bundle.LINK_SELF.equals(link.getRelation()))
            .map(link -> URLDecoder.decode(link.getUrl().replaceFirst("^.*?/api/", "/api/"), UTF_8))
            .toList();
    assertEquals(List.of(URLDecoder.decode(url, UTF_8)), self, body);
    String type = url.split("\\?")[0].replaceAll(".*/", "");
    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
      Resource resource = entry.getResource();
      String fullUrl = "/api/fhir/" + resource.fhirType() + "/" + resource.getIdPart();
      assertTrue(type.startsWith("$") || type.equals(resource.fhirType()), body);
      assertTrue(entry.getFullUrl().endsWith(fullUrl), entry::getFullUrl);
      assertEquals(Bundle.SearchEntryMode.MATCH, entry.getSearch().getMode(), body);
    }
    return bundle;
  }

  /** Asserts an {@code OperationOutcome} with one {@code error} issue and returns the body. */
  static String assertOutcome(
      HttpResponse response, HttpStatus status, IssueType code, Predicate<String> diagnostics) {
    String body = fhirBody(response, status);
    OperationOutcome outcome = parse(body, OperationOutcome.class);
    assertEquals(1, outcome.getIssue().size(), body);
    OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
    assertEquals(IssueSeverity.ERROR, issue.getSeverity(), body);
    assertEquals(code, issue.getCode(), body);
    assertTrue(diagnostics.test(issue.getDiagnostics()), body);
    return body;
  }

  /** Asserts {@code 400 invalid} naming {@code parameter} and no other query parameter. */
  void assertInvalid(String url, String parameter) {
    String query = url.contains("?") ? url.substring(url.indexOf('?') + 1) : "";
    List<String> others =
        Stream.of(query.split("&"))
            .map(pair -> pair.split("=", 2)[0])
            .filter(name -> !name.isEmpty() && !name.equals(parameter))
            .toList();
    String prefix = "Invalid parameter '" + parameter + "':";
    Predicate<String> namesOnlyParameter =
        d -> d.startsWith(prefix) && others.stream().noneMatch(n -> d.contains("'" + n + "'"));
    assertOutcome(GET(url), HttpStatus.BAD_REQUEST, IssueType.INVALID, namesOnlyParameter);
  }

  static String assertNotFound(HttpResponse response) {
    String diagnostics = FhirApiException.notFound().getDiagnostics();
    return assertOutcome(response, HttpStatus.NOT_FOUND, IssueType.NOTFOUND, diagnostics::equals);
  }

  String assertForbidden(String url) {
    String diagnostics = FhirApiException.forbidden().getDiagnostics();
    return assertOutcome(GET(url), HttpStatus.FORBIDDEN, IssueType.FORBIDDEN, diagnostics::equals);
  }

  /** Asserts one {@code 403} body for all {@code urls} that contains none of {@code hidden}. */
  String assertSameForbidden(List<String> urls, String... hidden) {
    String body = assertForbidden(urls.get(0));
    urls.stream().skip(1).forEach(url -> assertEquals(body, assertForbidden(url), url));
    Stream.of(hidden).forEach(value -> assertFalse(body.contains(value), body));
    return body;
  }

  /** Runs {@code test} as a new user under the restrictions, then restores public access. */
  void asRestrictedUser(List<Restriction> restrictions, Runnable test) {
    Map<Restriction, String> previous = new LinkedHashMap<>();
    try {
      restrictions.forEach(r -> previous.put(r, setPublicSharing(r.type(), r.uid(), r.access())));
      asUser(userWithScope(ROOT_ORG_UNIT, ROOT_ORG_UNIT), test);
    } finally {
      previous.forEach((r, access) -> setPublicSharing(r.type(), r.uid(), access));
    }
  }

  /** Runs {@code check} as the import user, then as a new user with the baseline sharing. */
  void asImportAndBaselineUser(Runnable check) {
    check.run();
    asRestrictedUser(List.of(), check);
  }

  void asUser(User user, Runnable test) {
    switchContextToUser(user);
    try {
      test.run();
    } finally {
      switchContextToUser(importUser);
    }
  }

  /** Creates a user without authorities, user groups or user sharing. */
  User userWithScope(String captureOrgUnit, String searchOrgUnit) {
    User user = createUserWithAuth("fhiruser" + userCounter.incrementAndGet());
    user.addOrganisationUnit(manager.get(OrganisationUnit.class, captureOrgUnit));
    user.setTeiSearchOrganisationUnits(Set.of(manager.get(OrganisationUnit.class, searchOrgUnit)));
    userService.updateUser(user);
    manager.clear();
    return user;
  }

  /** Sets public access through {@code /api/sharing} and returns the previous public access. */
  String setPublicSharing(String type, String uid, String access) {
    String url = "/sharing?type=" + type + "&id=" + uid;
    JsonObject object = GET(url).content(HttpStatus.OK).getObject("object");
    String users = arrayJson(object.getArray("userAccesses"));
    String groups = arrayJson(object.getArray("userGroupAccesses"));
    String body = "{'object':{'publicAccess':'%s','userAccesses':%s,'userGroupAccesses':%s}}";
    assertStatus(HttpStatus.OK, POST(url, body.formatted(access, users, groups)));
    manager.flush();
    manager.clear();
    return object.getString("publicAccess").string();
  }

  private static String arrayJson(JsonArray array) {
    return array.exists() ? array.toJson() : "[]";
  }

  /** The public access a test gives one object, by its {@code /api/sharing} type and UID. */
  record Restriction(String type, String uid, String access) {}
}
