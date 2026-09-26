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
import java.time.Instant;
import java.util.*;
import java.util.function.Predicate;
import javax.annotation.*;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapper.*;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.search.*;
import org.hisp.dhis.fhir.search.FhirSearchParameters.*;
import org.hisp.dhis.fhir.search.FhirSearchTranslator.TranslatedSearch;
import org.hisp.dhis.fhir.service.FhirTrackerReader.EnrollmentResult;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.Event;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/** Serves Encounter, Immunization and Observation reads, searches and Patient/$everything data. */
@Slf4j
@Service
public class FhirEventResourceService {
  private static final String FHIR_BASE_PATH = "/api/fhir";
  private static final String PATH_SEPARATOR = "/";
  private static final String LINK_SELF = "self";
  private static final String LINK_NEXT = "next";
  private static final String LINK_PREVIOUS = "previous";
  private static final String NO_USABLE_MAPPING = "No usable mapping is configured for ";
  private static final List<FhirResourceType> EVENT_TYPES =
      List.of(
          FhirResourceType.ENCOUNTER, FhirResourceType.IMMUNIZATION, FhirResourceType.OBSERVATION);
  private static final Comparator<Event> EVENT_ORDER =
      Comparator.comparing(
              Event::getOccurredAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
          .thenComparing(event -> event.getEvent().getValue());
  private final FhirResourceMappingService mappingService;
  private final FhirSearchParameters parameters;
  private final FhirSearchTranslator translator;
  private final FhirTrackerReader reader;
  private final FhirEncounterMapper encounterMapper;
  private final FhirImmunizationMapper immunizationMapper;
  private final FhirObservationMapper observationMapper;

  public FhirEventResourceService(
      FhirResourceMappingService mappingService,
      FhirSearchParameters parameters,
      FhirSearchTranslator translator,
      FhirTrackerReader reader,
      FhirEncounterMapper encounterMapper,
      FhirImmunizationMapper immunizationMapper,
      FhirObservationMapper observationMapper) {
    this.mappingService = Objects.requireNonNull(mappingService, "mappingService");
    this.parameters = Objects.requireNonNull(parameters, "parameters");
    this.translator = Objects.requireNonNull(translator, "translator");
    this.reader = Objects.requireNonNull(reader, "reader");
    this.encounterMapper = Objects.requireNonNull(encounterMapper, "encounterMapper");
    this.immunizationMapper = Objects.requireNonNull(immunizationMapper, "immunizationMapper");
    this.observationMapper = Objects.requireNonNull(observationMapper, "observationMapper");
  }

  /** Reads one event-derived resource by its logical id within one operation deadline. */
  @Nonnull
  public Resource read(
      @Nonnull FhirResourceType type,
      @CheckForNull String id,
      @Nonnull HttpServletRequest request) {
    requireEventDerived(type);
    Objects.requireNonNull(request, "request");
    return reader.withinDeadline(() -> readWithinDeadline(type, id, request));
  }

  /** Searches one event-derived type within one operation deadline into a paged searchset. */
  @Nonnull
  public Bundle search(@Nonnull FhirResourceType type, @Nonnull HttpServletRequest request) {
    requireEventDerived(type);
    Objects.requireNonNull(request, "request");
    return reader.withinDeadline(() -> searchWithinDeadline(type, request));
  }

  /** Returns the event-derived resources of one tracked entity, omitting forbidden programs. */
  @Nonnull
  public List<Resource> forPatient(
      @Nonnull String trackedEntity,
      @Nonnull String trackedEntityType,
      @Nonnull List<ResolvedMapping> mappings,
      @Nonnull HttpServletRequest request) {
    Objects.requireNonNull(trackedEntity, "trackedEntity");
    Objects.requireNonNull(trackedEntityType, "trackedEntityType");
    Objects.requireNonNull(mappings, "mappings");
    Objects.requireNonNull(request, "request");
    Map<FhirResourceType, List<ResolvedMapping>> mappingsByType =
        new EnumMap<>(FhirResourceType.class);
    for (FhirResourceType type : EVENT_TYPES) {
      mappingsByType.put(
          type,
          ofType(mappings, type).stream()
              .filter(mapping -> trackedEntityType.equals(mapping.trackedEntityType()))
              .toList());
    }
    Set<String> encounterStages = stagesOf(ofType(mappings, FhirResourceType.ENCOUNTER));
    SortedSet<String> programs = new TreeSet<>();
    mappingsByType.values().forEach(typed -> programs.addAll(candidatePrograms(typed)));
    List<Resource> resources = new ArrayList<>();
    for (String program : programs) {
      Map<FhirResourceType, List<ResolvedMapping>> programMappings =
          restrictToProgram(mappingsByType, program);
      FhirResourceType firstType =
          EVENT_TYPES.stream()
              .filter(type -> !programMappings.get(type).isEmpty())
              .findFirst()
              .orElseThrow();
      EnrollmentResult result =
          reader.findEnrollments(
              translator.everythingParams(trackedEntity, program), request, firstType);
      if (result.forbidden()) {
        log.debug("Omitting a forbidden program from the event resources of a Patient");
        continue;
      }
      flatten(result.enrollments(), programMappings, encounterStages, event -> true)
          .forEach(flattened -> resources.add(flattened.resource()));
    }
    return Collections.unmodifiableList(resources);
  }

  static String fhirBase(HttpServletRequest request) {
    return ServletUriComponentsBuilder.fromContextPath(request)
        .path(FHIR_BASE_PATH)
        .build()
        .toUriString();
  }

  static Bundle searchset() {
    return new Bundle().setType(Bundle.BundleType.SEARCHSET);
  }

  static void addEntries(
      Bundle bundle,
      HttpServletRequest request,
      List<? extends Resource> resources,
      Runnable checkpoint) {
    String base = fhirBase(request) + PATH_SEPARATOR;
    for (Resource resource : resources) {
      bundle
          .addEntry()
          .setFullUrl(
              base + resource.fhirType() + PATH_SEPARATOR + resource.getIdElement().getIdPart())
          .setResource(resource)
          .getSearch()
          .setMode(Bundle.SearchEntryMode.MATCH);
      checkpoint.run();
    }
  }

  static UriComponentsBuilder addSelfLink(Bundle bundle, HttpServletRequest request, String path) {
    UriComponentsBuilder base =
        ServletUriComponentsBuilder.fromContextPath(request)
            .path(FHIR_BASE_PATH + PATH_SEPARATOR + path)
            .query(request.getQueryString());
    bundle.addLink().setRelation(LINK_SELF).setUrl(base.build().toUriString());
    return base;
  }

  static void addPagingLinks(
      Bundle bundle, HttpServletRequest request, String resourceType, int page, boolean hasNext) {
    UriComponentsBuilder base = addSelfLink(bundle, request, resourceType);
    if (hasNext) {
      bundle.addLink().setRelation(LINK_NEXT).setUrl(pageUrl(base, page + 1));
    }
    if (page > 1) {
      bundle.addLink().setRelation(LINK_PREVIOUS).setUrl(pageUrl(base, page - 1));
    }
  }

  private static String pageUrl(UriComponentsBuilder base, int page) {
    return base.cloneBuilder()
        .replaceQueryParam(FhirSearchParameters.PAGE, page)
        .build()
        .toUriString();
  }

  private Resource readWithinDeadline(
      FhirResourceType type, @CheckForNull String id, HttpServletRequest request) {
    List<ResolvedMapping> mappings = requireMappings(type);
    Set<String> encounterStages = encounterStages(type);
    reader.checkpoint();
    parameters.checkFormatOnly(Operation.READ, request);
    FhirLogicalId logicalId = FhirLogicalId.parse(type, id).orElseThrow(FhirApiException::notFound);
    List<String> programs = candidatePrograms(mappings);
    int forbidden = 0;
    for (String program : programs) {
      EnrollmentResult result =
          reader.findEnrollments(
              translator.eventReadParams(logicalId.enrollment(), program), request, type);
      if (result.forbidden()) {
        forbidden++;
        continue;
      }
      List<Enrollment> enrollments =
          result.enrollments().stream()
              .filter(
                  enrollment ->
                      enrollment != null
                          && logicalId.enrollment().equals(value(enrollment.getEnrollment())))
              .toList();
      Map<FhirResourceType, List<ResolvedMapping>> programMappings =
          Map.of(type, mappingsOfProgram(mappings, program));
      for (FlattenedResource flattened :
          flatten(
              enrollments,
              programMappings,
              encounterStages,
              event -> Objects.equals(logicalId.event(), value(event.getEvent())))) {
        if (id.equals(idOf(flattened.resource()))) {
          reader.checkpoint();
          return flattened.resource();
        }
      }
    }
    reader.checkpoint();
    if (!programs.isEmpty() && forbidden == programs.size()) {
      throw FhirApiException.forbidden();
    }
    throw FhirApiException.notFound();
  }

  private Bundle searchWithinDeadline(FhirResourceType type, HttpServletRequest request) {
    List<ResolvedMapping> mappings = requireMappings(type);
    Set<String> encounterStages = encounterStages(type);
    reader.checkpoint();
    ParsedSearch parsed = parameters.parse(Operation.search(type), request, null);
    Set<String> requestedEvents = new HashSet<>();
    parsed.logicalIds().forEach(logicalId -> requestedEvents.add(logicalId.event()));
    Predicate<Event> eventFilter =
        requestedEvents.isEmpty()
            ? event -> true
            : event -> requestedEvents.contains(value(event.getEvent()));
    List<String> programs = candidatePrograms(mappings);
    List<Resource> resources = new ArrayList<>();
    int forbidden = 0;
    for (String program : programs) {
      List<ResolvedMapping> programMappings = mappingsOfProgram(mappings, program);
      TranslatedSearch translated = translator.toEnrollmentParams(parsed, programMappings, program);
      if (translated.empty()) {
        continue;
      }
      EnrollmentResult result =
          reader.findEnrollments(translated.enrollmentParams(), request, type);
      if (result.forbidden()) {
        forbidden++;
        continue;
      }
      for (FlattenedResource flattened :
          flatten(
              result.enrollments(), Map.of(type, programMappings), encounterStages, eventFilter)) {
        if (isSelected(type, flattened, translated)) {
          resources.add(flattened.resource());
        }
      }
    }
    reader.checkpoint();
    if (forbidden > 0 && forbidden == programs.size()) {
      throw FhirApiException.forbidden();
    }
    int size = resources.size();
    int page = parsed.page();
    long first = (long) (page - 1) * parsed.count();
    int from = (int) Math.min(first, size);
    int to = (int) Math.min(first + parsed.count(), size);
    Bundle bundle = searchset();
    bundle.setTotal(size);
    addEntries(bundle, request, resources.subList(from, to), reader::checkpoint);
    addPagingLinks(bundle, request, type.fhirType(), page, to < size);
    reader.checkpoint();
    return bundle;
  }

  private static boolean isSelected(
      FhirResourceType type, FlattenedResource flattened, TranslatedSearch translated) {
    String id = idOf(flattened.resource());
    if (!translated.matchesId(id)) {
      return false;
    }
    if (type != FhirResourceType.OBSERVATION || translated.codes().isEmpty()) {
      return true;
    }
    return translated.matchesCode(observationEntry(flattened.mapping(), id));
  }

  @CheckForNull
  private static FhirFieldMapping observationEntry(ResolvedMapping mapping, String id) {
    String dataElement =
        FhirLogicalId.parse(FhirResourceType.OBSERVATION, id)
            .map(FhirLogicalId::dataElement)
            .orElse(null);
    if (dataElement == null) {
      return null;
    }
    return mapping.entries(FhirTargetField.OBSERVATION_VALUE).stream()
        .filter(entry -> dataElement.equals(entry.getSource()))
        .findFirst()
        .orElse(null);
  }

  private List<FlattenedResource> flatten(
      List<Enrollment> enrollments,
      Map<FhirResourceType, List<ResolvedMapping>> mappingsByType,
      Set<String> encounterStages,
      Predicate<Event> eventFilter) {
    Map<String, Map<FhirResourceType, List<ResolvedMapping>>> mappingsByStage = new HashMap<>();
    mappingsByType.forEach(
        (type, mappings) -> {
          for (ResolvedMapping mapping : mappings) {
            if (mapping.programStage() != null) {
              mappingsByStage
                  .computeIfAbsent(
                      mapping.programStage(), stage -> new EnumMap<>(FhirResourceType.class))
                  .computeIfAbsent(type, t -> new ArrayList<>())
                  .add(mapping);
            }
          }
        });
    List<FlattenedResource> resources = new ArrayList<>();
    for (Enrollment enrollment : enrollments) {
      if (enrollment == null
          || enrollment.getEnrollment() == null
          || enrollment.getTrackedEntity() == null
          || enrollment.getEvents() == null) {
        continue;
      }
      List<Event> events =
          enrollment.getEvents().stream()
              .filter(
                  event ->
                      event != null
                          && event.getEvent() != null
                          && event.getProgramStage() != null
                          && mappingsByStage.containsKey(event.getProgramStage()))
              .filter(eventFilter)
              .sorted(EVENT_ORDER)
              .toList();
      for (Event event : events) {
        mapEvent(
            enrollment,
            event,
            mappingsByStage.get(event.getProgramStage()),
            encounterStages,
            resources);
        reader.checkpoint();
      }
    }
    return resources;
  }

  private void mapEvent(
      Enrollment enrollment,
      Event event,
      Map<FhirResourceType, List<ResolvedMapping>> stageMappings,
      Set<String> encounterStages,
      List<FlattenedResource> resources) {
    boolean encounterMapped = encounterStages.contains(event.getProgramStage());
    for (FhirResourceType type : EVENT_TYPES) {
      for (ResolvedMapping mapping : stageMappings.getOrDefault(type, List.of())) {
        switch (type) {
          case ENCOUNTER ->
              resources.add(
                  new FlattenedResource(encounterMapper.map(enrollment, event, mapping), mapping));
          case IMMUNIZATION ->
              immunizationMapper
                  .map(enrollment, event, mapping, encounterMapped)
                  .ifPresent(resource -> resources.add(new FlattenedResource(resource, mapping)));
          case OBSERVATION ->
              observationMapper
                  .map(enrollment, event, mapping, encounterMapped)
                  .forEach(resource -> resources.add(new FlattenedResource(resource, mapping)));
          case PATIENT ->
              throw new IllegalStateException("Patient is not an event-derived resource type");
        }
      }
    }
  }

  private List<ResolvedMapping> requireMappings(FhirResourceType type) {
    List<ResolvedMapping> mappings = resolve(type);
    if (mappings.isEmpty()) {
      throw FhirApiException.notSupported(NO_USABLE_MAPPING + type.fhirType());
    }
    return mappings;
  }

  private List<ResolvedMapping> resolve(FhirResourceType type) {
    List<ResolvedMapping> mappings = mappingService.resolve(type);
    return mappings == null ? List.of() : mappings;
  }

  private Set<String> encounterStages(FhirResourceType type) {
    return type == FhirResourceType.ENCOUNTER
        ? Set.of()
        : stagesOf(resolve(FhirResourceType.ENCOUNTER));
  }

  private static List<String> candidatePrograms(List<ResolvedMapping> mappings) {
    Set<String> programs = new LinkedHashSet<>();
    for (ResolvedMapping mapping : mappings) {
      if (mapping.program() != null) {
        programs.add(mapping.program());
      }
    }
    return List.copyOf(programs);
  }

  private static List<ResolvedMapping> ofType(
      List<ResolvedMapping> mappings, FhirResourceType type) {
    return mappings.stream()
        .filter(mapping -> mapping != null && mapping.resourceType() == type)
        .toList();
  }

  private static List<ResolvedMapping> mappingsOfProgram(
      List<ResolvedMapping> mappings, String program) {
    return mappings.stream().filter(mapping -> program.equals(mapping.program())).toList();
  }

  private static Map<FhirResourceType, List<ResolvedMapping>> restrictToProgram(
      Map<FhirResourceType, List<ResolvedMapping>> mappingsByType, String program) {
    Map<FhirResourceType, List<ResolvedMapping>> restricted = new EnumMap<>(FhirResourceType.class);
    for (FhirResourceType type : EVENT_TYPES) {
      restricted.put(
          type, mappingsOfProgram(mappingsByType.getOrDefault(type, List.of()), program));
    }
    return restricted;
  }

  private static Set<String> stagesOf(List<ResolvedMapping> mappings) {
    Set<String> stages = new LinkedHashSet<>();
    for (ResolvedMapping mapping : mappings) {
      if (mapping.programStage() != null) {
        stages.add(mapping.programStage());
      }
    }
    return stages;
  }

  private static void requireEventDerived(FhirResourceType type) {
    Objects.requireNonNull(type, "type");
    if (!type.isEventDerived()) {
      throw new IllegalArgumentException(
          type.fhirType() + " resources are not served by this service");
    }
  }

  private static String idOf(Resource resource) {
    return resource.getIdElement().getIdPart();
  }

  @CheckForNull
  private static String value(@CheckForNull UID uid) {
    return uid == null ? null : uid.getValue();
  }

  private record FlattenedResource(Resource resource, ResolvedMapping mapping) {}
}
