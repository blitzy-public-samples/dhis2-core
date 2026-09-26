/*
 * Copyright (c) 2004-2025, University of Oslo
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

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapper.FhirPatientMapper;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.search.FhirSearchParameters;
import org.hisp.dhis.fhir.search.FhirSearchParameters.Operation;
import org.hisp.dhis.fhir.search.FhirSearchParameters.ParsedSearch;
import org.hisp.dhis.fhir.search.FhirSearchTranslator;
import org.hisp.dhis.fhir.search.FhirSearchTranslator.TranslatedSearch;
import org.hisp.dhis.fhir.service.FhirTrackerReader.FhirSearchOrigin;
import org.hisp.dhis.webapi.controller.tracker.view.Page;
import org.hisp.dhis.webapi.controller.tracker.view.TrackedEntity;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Service;

/**
 * Serves the FHIR {@code Patient} read, search-type and {@code $everything} operations.
 *
 * <p>Tracked entities are read only through {@link FhirTrackerReader#findTrackedEntities}, and the
 * event-derived resources of {@code $everything} only through {@link
 * FhirEventResourceService#forPatient}. The service performs no access check of its own: access
 * denials, org-unit scoping, program ownership and attribute value filtering are those of the
 * Tracker export path.
 *
 * <p>Every operation checks, in this order, stopping at the first failure:
 *
 * <ol>
 *   <li>a usable {@code PATIENT} mapping exists, otherwise {@code 501 not-supported};
 *   <li>the query parameters, otherwise {@code 400 invalid} naming the offending parameter;
 *   <li>for reads and {@code $everything}, the UID syntax of the id, otherwise {@code 404
 *       not-found} without a Tracker call;
 *   <li>access to the mapped program or tracked entity type, otherwise {@code 403 forbidden};
 *   <li>for reads and {@code $everything}, the presence of the tracked entity among the rows the
 *       user may see, otherwise {@code 404 not-found}.
 * </ol>
 *
 * <p>Each operation runs within exactly one {@link FhirTrackerReader#withinDeadline deadline},
 * which starts before mapping resolution; {@link FhirTrackerReader#checkpoint()} runs after mapping
 * resolution and after the resources are mapped. {@link
 * org.hisp.dhis.deadline.DeadlineExceededException} and every exception other than {@link
 * FhirApiException} propagate unchanged.
 */
@Service
public class FhirPatientService {
  private static final String NO_USABLE_MAPPING = "No usable mapping is configured for ";

  private final FhirResourceMappingService mappingService;

  private final FhirSearchParameters parameters;

  private final FhirSearchTranslator translator;

  private final FhirTrackerReader reader;

  private final FhirPatientMapper patientMapper;

  private final FhirEventResourceService eventResourceService;

  /**
   * Creates the service.
   *
   * @param mappingService resolves the usable {@code PATIENT} mapping
   * @param parameters validates the query parameters of every Patient operation
   * @param translator builds the tracked entity request parameters of reads and searches
   * @param reader performs every tracked entity request and holds the operation deadline
   * @param patientMapper maps tracked entities to {@code Patient}s
   * @param eventResourceService supplies the event-derived resources of {@code $everything}
   * @throws NullPointerException if an argument is {@code null}
   */
  public FhirPatientService(
      FhirResourceMappingService mappingService,
      FhirSearchParameters parameters,
      FhirSearchTranslator translator,
      FhirTrackerReader reader,
      FhirPatientMapper patientMapper,
      FhirEventResourceService eventResourceService) {
    this.mappingService = Objects.requireNonNull(mappingService, "mappingService");
    this.parameters = Objects.requireNonNull(parameters, "parameters");
    this.translator = Objects.requireNonNull(translator, "translator");
    this.reader = Objects.requireNonNull(reader, "reader");
    this.patientMapper = Objects.requireNonNull(patientMapper, "patientMapper");
    this.eventResourceService =
        Objects.requireNonNull(eventResourceService, "eventResourceService");
  }

  /**
   * Reads one {@code Patient} by its tracked entity UID, within one operation deadline.
   *
   * <p>Steps, in order: resolve the usable {@code PATIENT} mapping; checkpoint; accept only {@code
   * _format}; check the UID syntax of {@code id}; request the tracked entity with the mapping's
   * program or else its tracked entity type; map the returned tracked entity whose UID equals
   * {@code id}.
   *
   * @param id the logical id from the request path
   * @param request the current HTTP request
   * @return the Patient
   * @throws FhirApiException {@code 501 not-supported} when no usable {@code PATIENT} mapping
   *     exists or the export path cannot use it; {@code 400 invalid} for any parameter other than
   *     an accepted {@code _format}; {@code 403 forbidden} when the export path denies access;
   *     {@code 404 not-found} when {@code id} is not a UID or the export path returns no tracked
   *     entity with that UID
   * @throws NullPointerException if {@code request} is {@code null}
   */
  @Nonnull
  public Patient read(@CheckForNull String id, @Nonnull HttpServletRequest request) {
    Objects.requireNonNull(request, "request");
    return reader.withinDeadline(() -> readPatient(Operation.READ, resolveMapping(), id, request));
  }

  /**
   * Searches {@code Patient}s, within one operation deadline.
   *
   * <p>Steps, in order: resolve the usable {@code PATIENT} mapping; checkpoint; parse the query
   * with {@link FhirSearchParameters#parse}; translate it with {@link
   * FhirSearchTranslator#toTrackedEntityParams}; request one page of tracked entities, unless the
   * translation is {@link TranslatedSearch#empty() empty}; map every returned tracked entity in
   * page order; checkpoint.
   *
   * <p>The Bundle is a {@code searchset} without {@code total}, holding one entry per returned
   * tracked entity, with the links {@code self}, {@code next} when the export path reports a
   * further page, and {@code previous} when {@code _page > 1}. An empty translation yields a Bundle
   * without entries and without a {@code next} link.
   *
   * @param request the current HTTP request
   * @return the {@code searchset} Bundle
   * @throws FhirApiException {@code 501 not-supported} when no usable {@code PATIENT} mapping
   *     exists or the export path cannot use it; {@code 400 invalid} naming the offending
   *     parameter; {@code 403 forbidden} when the export path denies access
   * @throws NullPointerException if {@code request} is {@code null}
   */
  @Nonnull
  public Bundle search(@Nonnull HttpServletRequest request) {
    Objects.requireNonNull(request, "request");
    return reader.withinDeadline(() -> searchWithinDeadline(request));
  }

  /**
   * Returns a {@code Patient} together with its {@code Encounter}s, {@code Immunization}s and
   * {@code Observation}s, within one operation deadline shared by every Tracker request.
   *
   * <p>Steps, in order: read the Patient exactly as {@link #read} does, accepting only {@code
   * _format}; collect the event-derived resources with {@link FhirEventResourceService#forPatient}
   * for the Patient mapping's tracked entity type, which omits programs the user may not read;
   * checkpoint.
   *
   * <p>The Bundle is an unpaged {@code searchset} whose first entry is the Patient, followed by the
   * event-derived resources in the order returned, and whose {@code total} is the number of
   * entries. It carries no links.
   *
   * @param id the logical id of the Patient from the request path
   * @param request the current HTTP request
   * @return the {@code searchset} Bundle
   * @throws FhirApiException {@code 501 not-supported} when no usable {@code PATIENT} mapping
   *     exists or the export path cannot use a mapping; {@code 400 invalid} for any parameter other
   *     than an accepted {@code _format}; {@code 403 forbidden} when the export path denies access
   *     to the Patient; {@code 404 not-found} when {@code id} is not a UID or the export path
   *     returns no tracked entity with that UID
   * @throws NullPointerException if {@code request} is {@code null}
   */
  @Nonnull
  public Bundle everything(@CheckForNull String id, @Nonnull HttpServletRequest request) {
    Objects.requireNonNull(request, "request");
    return reader.withinDeadline(() -> everythingWithinDeadline(id, request));
  }

  /** The search steps that run within the operation deadline; see {@link #search}. */
  private Bundle searchWithinDeadline(HttpServletRequest request) {
    ResolvedMapping mapping = resolveMapping();
    ParsedSearch parsed = parameters.parse(Operation.PATIENT_SEARCH, request, mapping);
    TranslatedSearch translated = translator.toTrackedEntityParams(parsed, mapping);

    Bundle bundle = FhirEventResourceService.searchset();
    boolean hasNext = false;
    if (!translated.empty()) {
      Page<TrackedEntity> page =
          reader.findTrackedEntities(
              translated.trackedEntityParams(), request, translated.origin());
      for (TrackedEntity trackedEntity : itemsOf(page)) {
        if (trackedEntity != null) {
          FhirEventResourceService.addEntry(
              bundle, request, patientMapper.map(trackedEntity, mapping));
        }
      }
      hasNext = hasNextPage(page);
    }
    reader.checkpoint();

    FhirEventResourceService.addPagingLinks(
        bundle, request, FhirResourceType.PATIENT.fhirType(), translated.page(), hasNext);
    return bundle;
  }

  /** The {@code $everything} steps that run within the deadline; see {@link #everything}. */
  private Bundle everythingWithinDeadline(@CheckForNull String id, HttpServletRequest request) {
    ResolvedMapping mapping = resolveMapping();
    Patient patient = readPatient(Operation.EVERYTHING, mapping, id, request);
    List<Resource> related =
        eventResourceService.forPatient(id, mapping.trackedEntityType(), request);
    reader.checkpoint();

    Bundle bundle = FhirEventResourceService.searchset();
    FhirEventResourceService.addEntry(bundle, request, patient);
    for (Resource resource : related) {
      FhirEventResourceService.addEntry(bundle, request, resource);
    }
    bundle.setTotal(bundle.getEntry().size());
    return bundle;
  }

  /**
   * Returns the usable {@code PATIENT} mapping, then runs a checkpoint.
   *
   * @return the first usable {@code PATIENT} mapping in resolution order
   * @throws FhirApiException {@code 501 not-supported} when there is none
   */
  private ResolvedMapping resolveMapping() {
    List<ResolvedMapping> mappings = mappingService.resolve(FhirResourceType.PATIENT);
    if (mappings == null || mappings.isEmpty()) {
      throw FhirApiException.notSupported(NO_USABLE_MAPPING + FhirResourceType.PATIENT.fhirType());
    }
    reader.checkpoint();
    return mappings.get(0);
  }

  /**
   * Reads and maps one tracked entity with the resolved mapping, within the caller's deadline.
   *
   * <p>Steps, in order: accept only {@code _format} for {@code operation}; check the UID syntax of
   * {@code id} without a Tracker call; request the tracked entity; map the first returned tracked
   * entity whose UID equals {@code id}.
   *
   * @throws FhirApiException {@code 400 invalid} for any parameter other than an accepted {@code
   *     _format}; {@code 403 forbidden} when the export path denies access; {@code 404 not-found}
   *     when {@code id} is not a UID or no returned tracked entity has that UID
   */
  private Patient readPatient(
      Operation operation,
      ResolvedMapping mapping,
      @CheckForNull String id,
      HttpServletRequest request) {
    parameters.checkFormatOnly(operation, request);
    if (!UID.isValid(id)) {
      throw FhirApiException.notFound();
    }

    Page<TrackedEntity> page =
        reader.findTrackedEntities(
            translator.patientReadParams(id, mapping), request, FhirSearchOrigin.empty());
    TrackedEntity trackedEntity =
        itemsOf(page).stream()
            .filter(
                item ->
                    item != null
                        && item.getTrackedEntity() != null
                        && id.equals(item.getTrackedEntity().getValue()))
            .findFirst()
            .orElseThrow(FhirApiException::notFound);
    return patientMapper.map(trackedEntity, mapping);
  }

  /** Returns the items of a page; a {@code null} page or item list becomes empty. */
  private static List<TrackedEntity> itemsOf(@CheckForNull Page<TrackedEntity> page) {
    if (page == null || page.getItems() == null) {
      return List.of();
    }
    return page.getItems();
  }

  /** Returns whether the export path reports a page after the returned one. */
  private static boolean hasNextPage(@CheckForNull Page<TrackedEntity> page) {
    return page != null && page.getPager() != null && page.getPager().getNextPage() != null;
  }
}
