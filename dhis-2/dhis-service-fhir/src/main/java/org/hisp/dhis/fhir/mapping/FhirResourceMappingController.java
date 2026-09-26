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
import java.io.*;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.OpenApi;
import org.hisp.dhis.feedback.*;
import org.hisp.dhis.query.GetObjectListParams;
import org.hisp.dhis.webapi.controller.AbstractCrudController;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** CRUD API for {@link FhirResourceMapping} metadata and host of the FHIR mapping settings page. */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:metadata"})
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fhirResourceMappings")
public class FhirResourceMappingController
    extends AbstractCrudController<FhirResourceMapping, GetObjectListParams> {
  static final String SETTINGS_PAGE = "org/hisp/dhis/fhir/settings/fhir-settings.html";
  private final FhirResourceMappingValidator validator;
  private final FhirResourceMappingStore store;

  /** Serves the FHIR mapping settings page as UTF-8 HTML that is never cached. */
  @OpenApi.Ignore
  @GetMapping(value = "/settings", produces = MediaType.TEXT_HTML_VALUE)
  public void getSettingsPage(HttpServletResponse response) throws IOException {
    response.setContentType("text/html;charset=UTF-8");
    response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    try (InputStream page = new ClassPathResource(SETTINGS_PAGE).getInputStream()) {
      page.transferTo(response.getOutputStream());
    }
  }

  @Override
  protected void preCreateEntity(FhirResourceMapping entity) throws ConflictException {
    validateOrConflict(entity);
  }

  @Override
  protected void preUpdateEntity(FhirResourceMapping persisted, FhirResourceMapping parsed)
      throws ConflictException {
    validateOrConflict(parsed);
  }

  @Override
  protected void prePatchEntity(FhirResourceMapping persisted, FhirResourceMapping patched)
      throws ConflictException {
    validateOrConflict(patched);
  }

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
