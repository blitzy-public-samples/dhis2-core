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
import static org.hisp.dhis.dxf2.webmessage.WebMessageUtils.error;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.common.*;
import org.hisp.dhis.dxf2.metadata.MetadataExportParams;
import org.hisp.dhis.dxf2.webmessage.WebMessageException;
import org.hisp.dhis.feedback.*;
import org.hisp.dhis.gist.*;
import org.hisp.dhis.query.*;
import org.hisp.dhis.webapi.controller.AbstractCrudController;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.util.function.ThrowingConsumer;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** CRUD API for {@link FhirResourceMapping} metadata and host of the FHIR mapping settings page. */
@OpenApi.Document(classifiers = {"team:tracker", "purpose:metadata"})
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/fhirResourceMappings")
public class FhirResourceMappingController
    extends AbstractCrudController<FhirResourceMapping, GetObjectListParams> {
  static final String SETTINGS_PAGE = "org/hisp/dhis/fhir/settings/fhir-settings.html";
  private static final Pattern FORMULA = Pattern.compile("^(\"?)(\\x{FEFF}*[=+\\-@\t\r])");
  private static final String CELL = "\"(?:[^\"]|\"\")*\"|[^\"\\r\\n\\x{%x}]+";
  private final FhirResourceMappingValidator validator;
  private final FhirResourceMappingStore store;

  /** Serves the settings page as uncached UTF-8 HTML, or a 500 web message when it is missing. */
  @OpenApi.Ignore
  @GetMapping(value = "/settings", produces = MediaType.TEXT_HTML_VALUE)
  public void getSettingsPage(HttpServletResponse out) throws IOException, WebMessageException {
    InputStream page;
    try {
      page = new ClassPathResource(SETTINGS_PAGE).getInputStream();
    } catch (IOException ex) {
      log.error("FHIR settings page {} could not be opened", SETTINGS_PAGE, ex);
      throw new WebMessageException(error("The FHIR settings page is not available"));
    }
    try (page) {
      out.setContentType("text/html;charset=UTF-8");
      out.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
      page.transferTo(out.getOutputStream());
    }
  }

  /** Exports the mapping in the metadata import format, honouring the /api/metadata options. */
  @OpenApi.Description("The mapping in the `/api/metadata` export format.")
  @OpenApi.Response(ObjectNode.class)
  @GetMapping("/{uid}/metadata")
  public ResponseEntity<MetadataExportParams> getFhirResourceMappingMetadata(
      @PathVariable("uid") UID uid,
      @RequestParam(required = false, defaultValue = "false") boolean download)
      throws NotFoundException {
    getEntity(uid);
    MetadataExportParams params =
        exportService.getParamsFromMap(contextService.getParameterValuesMap());
    params.setClasses(new HashSet<>());
    params.addQuery(Query.of(FhirResourceMapping.class).add(Filters.eq("id", uid.getValue())));
    params.setDownload(download);
    exportService.validate(params);
    return ResponseEntity.ok(params);
  }

  @Override
  protected String applyCsvSteps(
      String fields, List<FhirResourceMapping> list, char separator, String join, boolean noHeader)
      throws IOException {
    return neutralize(super.applyCsvSteps(fields, list, separator, join, noHeader), separator);
  }

  @Override
  public void getObjectGistAsCsv(UID uid, GistObjectParams params, HttpServletResponse response) {
    neutralize(response, csv -> super.getObjectGistAsCsv(uid, params, csv));
  }

  @Override
  public void getObjectListGistAsCsv(
      GistObjectListParams params, HttpServletRequest request, HttpServletResponse response) {
    neutralize(response, csv -> super.getObjectListGistAsCsv(params, request, csv));
  }

  @Override
  public void getObjectPropertyGistAsCsv(
      UID uid,
      String property,
      GistObjectPropertyParams params,
      HttpServletRequest request,
      HttpServletResponse response) {
    neutralize(response, r -> super.getObjectPropertyGistAsCsv(uid, property, params, request, r));
  }

  @SneakyThrows
  private static void neutralize(
      HttpServletResponse response, ThrowingConsumer<HttpServletResponse> gist) {
    var csv = new ContentCachingResponseWrapper(response);
    gist.acceptWithException(csv);
    String text = neutralize(new String(csv.getContentAsByteArray()), ',');
    response.getOutputStream().write(text.getBytes());
  }

  static String neutralize(String csv, char separator) {
    Matcher cells = Pattern.compile(CELL.formatted((int) separator)).matcher(csv);
    return cells.replaceAll(
        c -> Matcher.quoteReplacement(FORMULA.matcher(c.group()).replaceFirst("$1'$2")));
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
      List<ErrorReport> all = new ArrayList<>(reports);
      boolean unnamed = mapping.getName() == null || mapping.getName().isBlank();
      if (unnamed) all.add(0, new ErrorReport(FhirResourceMapping.class, ErrorCode.E4000, "name"));
      throw new ConflictException(
          all.stream().map(r -> r.getMessage().replaceAll("\\R", " ")).collect(joining("\n")));
    }
  }
}
