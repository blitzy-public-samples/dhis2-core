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
package org.hisp.dhis.fhir.mapping;

import static java.util.stream.Collectors.joining;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.OpenApi;
import org.hisp.dhis.feedback.ConflictException;
import org.hisp.dhis.feedback.ErrorReport;
import org.hisp.dhis.query.GetObjectListParams;
import org.hisp.dhis.webapi.controller.AbstractCrudController;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD API for {@link FhirResourceMapping} metadata at {@code /api/fhirResourceMappings}, and the
 * host of the FHIR mapping settings page.
 *
 * <p>List, read, gist, create, update, patch, delete, sharing and translation operations are
 * inherited from {@link AbstractCrudController}. Before a create, update or patch is imported, the
 * submitted mapping is checked with {@link FhirResourceMappingValidator} against every other stored
 * mapping; a mapping that violates any rule is rejected with a {@link ConflictException} whose
 * message lists every violation, separated by {@code "; "}, and nothing is stored.
 *
 * <p>{@code GET /api/fhirResourceMappings/settings} serves the self-contained HTML settings page,
 * which manages mappings through this API.
 */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:metadata"})
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fhirResourceMappings")
public class FhirResourceMappingController
    extends AbstractCrudController<FhirResourceMapping, GetObjectListParams> {

  /** Classpath location of the FHIR mapping settings page. */
  static final String SETTINGS_PAGE = "org/hisp/dhis/fhir/settings/fhir-settings.html";

  private final FhirResourceMappingValidator validator;

  private final FhirResourceMappingStore store;

  /**
   * Serves the FHIR mapping settings page as UTF-8 HTML that is never cached.
   *
   * @param response the response the page is written to
   * @throws IOException when the page cannot be read from the classpath or written to the response
   */
  @OpenApi.Ignore
  @GetMapping(value = "/settings", produces = MediaType.TEXT_HTML_VALUE)
  public void getSettingsPage(HttpServletResponse response) throws IOException {
    response.setContentType("text/html;charset=UTF-8");
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    try (InputStream page = new ClassPathResource(SETTINGS_PAGE).getInputStream()) {
      page.transferTo(response.getOutputStream());
    }
  }

  /**
   * Rejects a new mapping that violates the FHIR mapping rules.
   *
   * @param entity the mapping to create
   * @throws ConflictException listing every violated rule when the mapping is invalid
   */
  @Override
  protected void preCreateEntity(FhirResourceMapping entity) throws ConflictException {
    validateOrConflict(entity);
  }

  /**
   * Rejects a replacement mapping that violates the FHIR mapping rules.
   *
   * @param persisted the stored mapping being replaced
   * @param parsed the submitted replacement, carrying the UID of {@code persisted}
   * @throws ConflictException listing every violated rule when the replacement is invalid
   */
  @Override
  protected void preUpdateEntity(FhirResourceMapping persisted, FhirResourceMapping parsed)
      throws ConflictException {
    validateOrConflict(parsed);
  }

  /**
   * Rejects a patched mapping that violates the FHIR mapping rules.
   *
   * @param persisted the stored mapping being patched
   * @param patched the mapping with the patch applied, carrying the UID of {@code persisted}
   * @throws ConflictException listing every violated rule when the patched mapping is invalid
   */
  @Override
  protected void prePatchEntity(FhirResourceMapping persisted, FhirResourceMapping patched)
      throws ConflictException {
    validateOrConflict(patched);
  }

  /**
   * Validates a mapping against every stored mapping except those with its UID, and rejects it when
   * any rule is violated. The mapping itself is not modified.
   *
   * @param mapping the mapping about to be imported; its UID may be {@code null} on create
   * @throws ConflictException whose message joins the messages of all violations with {@code "; "}
   */
  private void validateOrConflict(FhirResourceMapping mapping) throws ConflictException {
    String uid = mapping.getUid();
    List<FhirResourceMapping> others =
        store.getAllNoAcl().stream()
            .filter(other -> uid == null || !uid.equals(other.getUid()))
            .toList();
    List<ErrorReport> reports = validator.validate(mapping, others);
    if (!reports.isEmpty()) {
      throw new ConflictException(
          reports.stream().map(ErrorReport::getMessage).collect(joining("; ")));
    }
  }
}
