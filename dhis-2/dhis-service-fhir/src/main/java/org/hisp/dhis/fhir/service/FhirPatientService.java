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
import java.util.*;
import javax.annotation.*;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapper.FhirPatientMapper;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.search.*;
import org.hisp.dhis.fhir.search.FhirSearchParameters.*;
import org.hisp.dhis.fhir.search.FhirSearchTranslator.TranslatedSearch;
import org.hisp.dhis.fhir.service.FhirTrackerReader.FhirSearchOrigin;
import org.hisp.dhis.webapi.controller.tracker.view.Page;
import org.hisp.dhis.webapi.controller.tracker.view.TrackedEntity;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Service;

/** Serves the FHIR {@code Patient} read, search-type and {@code $everything} operations. */
@Service
public class FhirPatientService {
  private static final String NO_USABLE_MAPPING = "No usable mapping is configured for ";
  private static final String PATH_SEPARATOR = "/";
  private static final String EVERYTHING = "/$everything";
  private final FhirResourceMappingService mappingService;
  private final FhirSearchParameters parameters;
  private final FhirSearchTranslator translator;
  private final FhirTrackerReader reader;
  private final FhirPatientMapper patientMapper;
  private final FhirEventResourceService eventResourceService;

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

  /** Reads one {@code Patient} by its tracked entity UID within one operation deadline. */
  @Nonnull
  public Patient read(@CheckForNull String id, @Nonnull HttpServletRequest request) {
    Objects.requireNonNull(request, "request");
    return reader.withinDeadline(
        () ->
            readPatient(
                Operation.READ,
                patientMapping(mappingService.resolve(FhirResourceType.PATIENT)),
                id,
                request));
  }

  /** Searches {@code Patient}s within one operation deadline into a paged searchset Bundle. */
  @Nonnull
  public Bundle search(@Nonnull HttpServletRequest request) {
    Objects.requireNonNull(request, "request");
    return reader.withinDeadline(() -> searchWithinDeadline(request));
  }

  /** Returns a {@code Patient} and its event-derived resources within one operation deadline. */
  @Nonnull
  public Bundle everything(@CheckForNull String id, @Nonnull HttpServletRequest request) {
    Objects.requireNonNull(request, "request");
    return reader.withinDeadline(() -> everythingWithinDeadline(id, request));
  }

  private Bundle searchWithinDeadline(HttpServletRequest request) {
    ResolvedMapping mapping = patientMapping(mappingService.resolve(FhirResourceType.PATIENT));
    ParsedSearch parsed = parameters.parse(Operation.PATIENT_SEARCH, request, mapping);
    TranslatedSearch translated = translator.toTrackedEntityParams(parsed, mapping);
    List<Patient> patients = new ArrayList<>();
    boolean hasNext = false;
    if (!translated.empty()) {
      Page<TrackedEntity> page =
          reader.findTrackedEntities(
              translated.trackedEntityParams(), request, translated.origin());
      for (TrackedEntity trackedEntity : itemsOf(page)) {
        if (trackedEntity != null) {
          patients.add(patientMapper.map(trackedEntity, mapping));
          reader.checkpoint();
        }
      }
      hasNext = hasNextPage(page);
    }
    reader.checkpoint();
    Bundle bundle = FhirEventResourceService.searchset();
    FhirEventResourceService.addEntries(bundle, request, patients, reader::checkpoint);
    FhirEventResourceService.addPagingLinks(
        bundle, request, FhirResourceType.PATIENT.fhirType(), translated.page(), hasNext);
    reader.checkpoint();
    return bundle;
  }

  private Bundle everythingWithinDeadline(@CheckForNull String id, HttpServletRequest request) {
    List<ResolvedMapping> mappings = mappingService.resolveAll();
    ResolvedMapping mapping = patientMapping(mappings);
    List<Resource> resources = new ArrayList<>();
    resources.add(readPatient(Operation.EVERYTHING, mapping, id, request));
    resources.addAll(
        eventResourceService.forPatient(id, mapping.trackedEntityType(), mappings, request));
    reader.checkpoint();
    Bundle bundle = FhirEventResourceService.searchset();
    FhirEventResourceService.addEntries(bundle, request, resources, reader::checkpoint);
    bundle.setTotal(bundle.getEntry().size());
    FhirEventResourceService.addSelfLink(
        bundle, request, FhirResourceType.PATIENT.fhirType() + PATH_SEPARATOR + id + EVERYTHING);
    reader.checkpoint();
    return bundle;
  }

  private ResolvedMapping patientMapping(@CheckForNull List<ResolvedMapping> mappings) {
    ResolvedMapping mapping =
        mappings == null
            ? null
            : mappings.stream()
                .filter(m -> m != null && m.resourceType() == FhirResourceType.PATIENT)
                .findFirst()
                .orElse(null);
    if (mapping == null) {
      throw FhirApiException.notSupported(NO_USABLE_MAPPING + FhirResourceType.PATIENT.fhirType());
    }
    reader.checkpoint();
    return mapping;
  }

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
    Patient patient = patientMapper.map(trackedEntity, mapping);
    reader.checkpoint();
    return patient;
  }

  private static List<TrackedEntity> itemsOf(@CheckForNull Page<TrackedEntity> page) {
    if (page == null || page.getItems() == null) {
      return List.of();
    }
    return page.getItems();
  }

  private static boolean hasNextPage(@CheckForNull Page<TrackedEntity> page) {
    return page != null && page.getPager() != null && page.getPager().getNextPage() != null;
  }
}
