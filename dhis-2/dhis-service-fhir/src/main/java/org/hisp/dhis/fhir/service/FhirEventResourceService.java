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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapper.FhirEncounterMapper;
import org.hisp.dhis.fhir.mapper.FhirImmunizationMapper;
import org.hisp.dhis.fhir.mapper.FhirLogicalId;
import org.hisp.dhis.fhir.mapper.FhirObservationMapper;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.mapping.FhirTargetField;
import org.hisp.dhis.fhir.search.FhirSearchParameters;
import org.hisp.dhis.fhir.search.FhirSearchParameters.Operation;
import org.hisp.dhis.fhir.search.FhirSearchParameters.ParsedSearch;
import org.hisp.dhis.fhir.search.FhirSearchTranslator;
import org.hisp.dhis.fhir.search.FhirSearchTranslator.TranslatedSearch;
import org.hisp.dhis.fhir.service.FhirTrackerReader.EnrollmentResult;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.Event;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Serves read and search-type for the event-derived FHIR resource types {@code Encounter}, {@code
 * Immunization} and {@code Observation}, and the event resources of {@code Patient/$everything}.
 *
 * <p>Every resource is built from the events nested in the enrollments that {@link
 * FhirTrackerReader#findEnrollments} returns, with one enrollment request per candidate program.
 * The candidate programs of a resource type are the distinct programs of its usable mappings, in
 * mapping UID order. The service performs no access check of its own; access denials come from the
 * export path as forbidden {@link EnrollmentResult}s.
 *
 * <p>Reads and searches check, in this order, stopping at the first failure:
 *
 * <ol>
 *   <li>a usable mapping of the type exists, otherwise {@code 501 not-supported};
 *   <li>the query parameters, otherwise {@code 400 invalid};
 *   <li>for reads, the syntax of the logical id, otherwise {@code 404 not-found};
 *   <li>access to the candidate programs, then the data the export path returns.
 * </ol>
 *
 * <p>Across several candidate programs:
 *
 * <ul>
 *   <li>a read answers {@code 403 forbidden} only when every candidate program is forbidden,
 *       otherwise the resource from the first readable program that holds it, or {@code 404
 *       not-found};
 *   <li>a search answers {@code 403 forbidden} only when every candidate program is forbidden,
 *       otherwise a {@code searchset} Bundle of the resources of the readable programs;
 *   <li>{@link #forPatient} omits forbidden programs.
 * </ul>
 *
 * <p>Within one enrollment response, resources are ordered by enrollment as returned, then by event
 * {@code occurredAt} ascending with missing dates last, then by event UID; the resources of one
 * event follow the type order {@code Encounter}, {@code Immunization}, {@code Observation} and,
 * within a type, mapping UID order.
 *
 * <p>Each read and search runs within one {@link FhirTrackerReader#withinDeadline deadline} that
 * starts before mapping resolution; {@link FhirTrackerReader#checkpoint()} runs after mapping
 * resolution, before every enrollment request and after flattening. {@link
 * org.hisp.dhis.deadline.DeadlineExceededException} and every exception other than {@link
 * FhirApiException} propagate unchanged.
 */
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

  /**
   * Creates the service.
   *
   * @param mappingService resolves the usable mappings of each resource type
   * @param parameters validates the query parameters of reads and searches
   * @param translator builds the enrollment request parameters of each candidate program
   * @param reader performs every enrollment request and holds the operation deadline
   * @param encounterMapper maps events to {@code Encounter}s
   * @param immunizationMapper maps events to {@code Immunization}s
   * @param observationMapper maps event data values to {@code Observation}s
   * @throws NullPointerException if an argument is {@code null}
   */
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

  /**
   * Reads one {@code Encounter}, {@code Immunization} or {@code Observation} by its logical id,
   * within one operation deadline.
   *
   * <p>Steps, in order: resolve the usable mappings of {@code type} (and, for {@code Immunization}
   * and {@code Observation}, the program stages of the usable {@code Encounter} mappings);
   * checkpoint; accept only {@code _format}; parse {@code id} with {@link FhirLogicalId#parse};
   * request the id's enrollment in each candidate program in turn, and return the first resource
   * whose id equals {@code id} exactly.
   *
   * @param type the event-derived resource type
   * @param id the logical id from the request path
   * @param request the current HTTP request
   * @return the resource, of the FHIR type of {@code type}
   * @throws FhirApiException {@code 501 not-supported} when {@code type} has no usable mapping;
   *     {@code 400 invalid} for any parameter other than an accepted {@code _format}; {@code 403
   *     forbidden} when every candidate program is forbidden; {@code 404 not-found} when {@code id}
   *     is malformed or no readable program holds the resource
   * @throws IllegalArgumentException if {@code type} is {@link FhirResourceType#PATIENT}
   * @throws NullPointerException if {@code type} or {@code request} is {@code null}
   */
  @Nonnull
  public Resource read(
      @Nonnull FhirResourceType type,
      @CheckForNull String id,
      @Nonnull HttpServletRequest request) {
    requireEventDerived(type);
    Objects.requireNonNull(request, "request");
    return reader.withinDeadline(() -> readWithinDeadline(type, id, request));
  }

  /**
   * Searches {@code Encounter}s, {@code Immunization}s or {@code Observation}s, within one
   * operation deadline.
   *
   * <p>Steps, in order: resolve the usable mappings of {@code type} (and, for {@code Immunization}
   * and {@code Observation}, the program stages of the usable {@code Encounter} mappings);
   * checkpoint; parse the query with {@link FhirSearchParameters#parse}; for each candidate program
   * translate the query with that program's mappings, skip the program without a request when the
   * translation is {@link TranslatedSearch#empty() empty}, otherwise request its enrollments and
   * keep the resources that match {@code _id} and, for {@code Observation}, {@code code};
   * checkpoint; page the kept resources in memory.
   *
   * <p>The Bundle is a {@code searchset} whose {@code total} is the number of kept resources, whose
   * entries are the resources of page {@code _page} of size {@code _count}, and whose links are
   * {@code self}, {@code next} when a further page exists and {@code previous} when {@code _page >
   * 1}.
   *
   * @param type the event-derived resource type
   * @param request the current HTTP request
   * @return the {@code searchset} Bundle
   * @throws FhirApiException {@code 501 not-supported} when {@code type} has no usable mapping;
   *     {@code 400 invalid} naming the offending parameter; {@code 403 forbidden} when every
   *     candidate program is forbidden
   * @throws IllegalArgumentException if {@code type} is {@link FhirResourceType#PATIENT}
   * @throws NullPointerException if {@code type} or {@code request} is {@code null}
   */
  @Nonnull
  public Bundle search(@Nonnull FhirResourceType type, @Nonnull HttpServletRequest request) {
    requireEventDerived(type);
    Objects.requireNonNull(request, "request");
    return reader.withinDeadline(() -> searchWithinDeadline(type, request));
  }

  /**
   * Returns the {@code Encounter}s, {@code Immunization}s and {@code Observation}s of one tracked
   * entity for {@code Patient/$everything}. Runs within the caller's deadline and opens none.
   *
   * <p>The mappings used are the usable event-derived mappings on {@code trackedEntityType}. Their
   * distinct programs are requested in program UID order, one enrollment request each; a forbidden
   * program contributes nothing and raises no error. {@code Immunization}s and {@code Observation}s
   * reference their {@code Encounter} when any usable {@code Encounter} mapping has the event's
   * program stage.
   *
   * @param trackedEntity the UID of the tracked entity the Patient was read for
   * @param trackedEntityType the UID of the Patient mapping's tracked entity type
   * @param request the current HTTP request
   * @return an unmodifiable list in program, enrollment and event order; empty when no
   *     event-derived mapping applies
   * @throws FhirApiException {@code 501 not-supported} when the export path rejects a request
   * @throws NullPointerException if an argument is {@code null}
   */
  @Nonnull
  public List<Resource> forPatient(
      @Nonnull String trackedEntity,
      @Nonnull String trackedEntityType,
      @Nonnull HttpServletRequest request) {
    Objects.requireNonNull(trackedEntity, "trackedEntity");
    Objects.requireNonNull(trackedEntityType, "trackedEntityType");
    Objects.requireNonNull(request, "request");

    Map<FhirResourceType, List<ResolvedMapping>> mappingsByType =
        new EnumMap<>(FhirResourceType.class);
    Set<String> encounterStages = Set.of();
    for (FhirResourceType type : EVENT_TYPES) {
      List<ResolvedMapping> resolved = resolve(type);
      if (type == FhirResourceType.ENCOUNTER) {
        encounterStages = stagesOf(resolved);
      }
      mappingsByType.put(
          type,
          resolved.stream()
              .filter(mapping -> trackedEntityType.equals(mapping.trackedEntityType()))
              .toList());
    }
    reader.checkpoint();

    SortedSet<String> programs = new TreeSet<>();
    mappingsByType.values().forEach(mappings -> programs.addAll(candidatePrograms(mappings)));

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

  /**
   * Returns the base URL of the FHIR API: the request's scheme, host, port and context path
   * followed by {@code /api/fhir}.
   *
   * @param request the current HTTP request
   * @return the base URL, without a trailing {@code /}
   */
  static String fhirBase(HttpServletRequest request) {
    return ServletUriComponentsBuilder.fromContextPath(request)
        .path(FHIR_BASE_PATH)
        .build()
        .toUriString();
  }

  /**
   * Creates an empty Bundle of type {@code searchset}.
   *
   * @return a new Bundle
   */
  static Bundle searchset() {
    return new Bundle().setType(Bundle.BundleType.SEARCHSET);
  }

  /**
   * Appends an entry holding {@code resource} with {@code fullUrl} {@code
   * {fhirBase}/{resourceType}/{id}} and {@code search.mode} {@code match}.
   *
   * @param bundle the Bundle to append to
   * @param request the current HTTP request, which supplies the base URL
   * @param resource the resource, with its logical id set
   */
  static void addEntry(Bundle bundle, HttpServletRequest request, Resource resource) {
    bundle
        .addEntry()
        .setFullUrl(
            fhirBase(request)
                + PATH_SEPARATOR
                + resource.fhirType()
                + PATH_SEPARATOR
                + resource.getIdElement().getIdPart())
        .setResource(resource)
        .getSearch()
        .setMode(Bundle.SearchEntryMode.MATCH);
  }

  /**
   * Appends the paging links of a search Bundle, each built on {@code {fhirBase}/{resourceType}}
   * with the request's query string as sent: {@code self} with the query unchanged; {@code next}
   * with {@code _page} set to {@code page + 1} when {@code hasNext}; {@code previous} with {@code
   * _page} set to {@code page - 1} when {@code page > 1}.
   *
   * @param bundle the Bundle to append to
   * @param request the current HTTP request
   * @param resourceType the FHIR resource type name of the search, for example {@code Encounter}
   * @param page the current page number
   * @param hasNext whether a page after {@code page} exists
   */
  static void addPagingLinks(
      Bundle bundle, HttpServletRequest request, String resourceType, int page, boolean hasNext) {
    UriComponentsBuilder base =
        ServletUriComponentsBuilder.fromContextPath(request)
            .path(FHIR_BASE_PATH + PATH_SEPARATOR + resourceType)
            .query(request.getQueryString());
    bundle.addLink().setRelation(LINK_SELF).setUrl(base.build().toUriString());
    if (hasNext) {
      bundle.addLink().setRelation(LINK_NEXT).setUrl(pageUrl(base, page + 1));
    }
    if (page > 1) {
      bundle.addLink().setRelation(LINK_PREVIOUS).setUrl(pageUrl(base, page - 1));
    }
  }

  /** Returns the URL of {@code base} with {@code _page} replaced by {@code page}. */
  private static String pageUrl(UriComponentsBuilder base, int page) {
    return base.cloneBuilder()
        .replaceQueryParam(FhirSearchParameters.PAGE, page)
        .build()
        .toUriString();
  }

  /** The read steps that run within the operation deadline; see {@link #read}. */
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
          return flattened.resource();
        }
      }
    }

    if (!programs.isEmpty() && forbidden == programs.size()) {
      throw FhirApiException.forbidden();
    }
    throw FhirApiException.notFound();
  }

  /** The search steps that run within the operation deadline; see {@link #search}. */
  private Bundle searchWithinDeadline(FhirResourceType type, HttpServletRequest request) {
    List<ResolvedMapping> mappings = requireMappings(type);
    Set<String> encounterStages = encounterStages(type);
    reader.checkpoint();

    ParsedSearch parsed = parameters.parse(Operation.search(type), request, null);

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
              result.enrollments(), Map.of(type, programMappings), encounterStages, e -> true)) {
        if (isSelected(type, flattened, translated)) {
          resources.add(flattened.resource());
        }
      }
    }

    if (forbidden > 0 && forbidden == programs.size()) {
      throw FhirApiException.forbidden();
    }
    reader.checkpoint();

    int size = resources.size();
    int page = parsed.page();
    long first = (long) (page - 1) * parsed.count();
    int from = (int) Math.min(first, size);
    int to = (int) Math.min(first + parsed.count(), size);

    Bundle bundle = searchset();
    bundle.setTotal(size);
    for (Resource resource : resources.subList(from, to)) {
      addEntry(bundle, request, resource);
    }
    addPagingLinks(bundle, request, type.fhirType(), page, to < size);
    return bundle;
  }

  /**
   * Returns whether a flattened resource matches the {@code _id} and, for {@code Observation}, the
   * {@code code} selection of a search.
   */
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

  /**
   * Returns the {@link FhirTargetField#OBSERVATION_VALUE} entry of {@code mapping} whose source is
   * the data element segment of the Observation id, or {@code null} when there is none.
   */
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

  /**
   * Maps the events of the given enrollments to resources through the given mappings.
   *
   * <p>Enrollments keep their order. Enrollments without an enrollment or tracked entity UID,
   * events without an event UID, events rejected by {@code eventFilter} and events whose program
   * stage is the stage of none of the given mappings produce nothing. The remaining events of an
   * enrollment are ordered by {@code occurredAt} ascending, missing dates last, then by event UID.
   * Each event yields, in type order {@code ENCOUNTER}, {@code IMMUNIZATION}, {@code OBSERVATION}
   * and within a type in list order, the resources of every mapping whose program stage is the
   * event's.
   *
   * @param enrollments the enrollments of one program as the export path returned them
   * @param mappingsByType the mappings of that program, by resource type
   * @param encounterStages the program stages that have a usable {@code ENCOUNTER} mapping
   * @param eventFilter selects the events to map
   * @return a new list of the resources with their producing mappings
   */
  private List<FlattenedResource> flatten(
      List<Enrollment> enrollments,
      Map<FhirResourceType, List<ResolvedMapping>> mappingsByType,
      Set<String> encounterStages,
      Predicate<Event> eventFilter) {
    Set<String> stages = new LinkedHashSet<>();
    mappingsByType.values().forEach(mappings -> stages.addAll(stagesOf(mappings)));

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
                          && stages.contains(event.getProgramStage()))
              .filter(eventFilter)
              .sorted(EVENT_ORDER)
              .toList();
      for (Event event : events) {
        mapEvent(enrollment, event, mappingsByType, encounterStages, resources);
      }
    }
    return resources;
  }

  /** Appends the resources of one event, in type order and within a type in list order. */
  private void mapEvent(
      Enrollment enrollment,
      Event event,
      Map<FhirResourceType, List<ResolvedMapping>> mappingsByType,
      Set<String> encounterStages,
      List<FlattenedResource> resources) {
    String stage = event.getProgramStage();
    boolean encounterMapped = encounterStages.contains(stage);
    for (FhirResourceType type : EVENT_TYPES) {
      for (ResolvedMapping mapping : mappingsByType.getOrDefault(type, List.of())) {
        if (!stage.equals(mapping.programStage())) {
          continue;
        }
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

  /**
   * Returns the usable mappings of the type.
   *
   * @throws FhirApiException {@code 501 not-supported} when there is none
   */
  private List<ResolvedMapping> requireMappings(FhirResourceType type) {
    List<ResolvedMapping> mappings = resolve(type);
    if (mappings.isEmpty()) {
      throw FhirApiException.notSupported(NO_USABLE_MAPPING + type.fhirType());
    }
    return mappings;
  }

  /** Returns the usable mappings of the type; a {@code null} result becomes empty. */
  private List<ResolvedMapping> resolve(FhirResourceType type) {
    List<ResolvedMapping> mappings = mappingService.resolve(type);
    return mappings == null ? List.of() : mappings;
  }

  /**
   * Returns the program stages of the usable {@code ENCOUNTER} mappings when serving {@code
   * IMMUNIZATION} or {@code OBSERVATION}, and an empty set when serving {@code ENCOUNTER}.
   */
  private Set<String> encounterStages(FhirResourceType type) {
    return type == FhirResourceType.ENCOUNTER
        ? Set.of()
        : stagesOf(resolve(FhirResourceType.ENCOUNTER));
  }

  /** Returns the distinct non-null programs of the mappings, in list order. */
  private static List<String> candidatePrograms(List<ResolvedMapping> mappings) {
    Set<String> programs = new LinkedHashSet<>();
    for (ResolvedMapping mapping : mappings) {
      if (mapping.program() != null) {
        programs.add(mapping.program());
      }
    }
    return List.copyOf(programs);
  }

  /** Returns the mappings of the program, in list order. */
  private static List<ResolvedMapping> mappingsOfProgram(
      List<ResolvedMapping> mappings, String program) {
    return mappings.stream().filter(mapping -> program.equals(mapping.program())).toList();
  }

  /** Returns, for every event-derived type, the mappings of the program in list order. */
  private static Map<FhirResourceType, List<ResolvedMapping>> restrictToProgram(
      Map<FhirResourceType, List<ResolvedMapping>> mappingsByType, String program) {
    Map<FhirResourceType, List<ResolvedMapping>> restricted = new EnumMap<>(FhirResourceType.class);
    for (FhirResourceType type : EVENT_TYPES) {
      restricted.put(
          type, mappingsOfProgram(mappingsByType.getOrDefault(type, List.of()), program));
    }
    return restricted;
  }

  /** Returns the distinct non-null program stages of the mappings. */
  private static Set<String> stagesOf(List<ResolvedMapping> mappings) {
    Set<String> stages = new LinkedHashSet<>();
    for (ResolvedMapping mapping : mappings) {
      if (mapping.programStage() != null) {
        stages.add(mapping.programStage());
      }
    }
    return stages;
  }

  /**
   * Checks that the type is event-derived.
   *
   * @throws IllegalArgumentException if {@code type} is {@link FhirResourceType#PATIENT}
   */
  private static void requireEventDerived(FhirResourceType type) {
    Objects.requireNonNull(type, "type");
    if (!type.isEventDerived()) {
      throw new IllegalArgumentException(
          type.fhirType() + " resources are not served by this service");
    }
  }

  /** Returns the logical id of a resource. */
  private static String idOf(Resource resource) {
    return resource.getIdElement().getIdPart();
  }

  /** Returns the string value of a UID, or {@code null}. */
  @CheckForNull
  private static String value(@CheckForNull UID uid) {
    return uid == null ? null : uid.getValue();
  }

  /**
   * One resource produced by flattening, with the mapping that produced it.
   *
   * @param resource the resource
   * @param mapping the mapping whose entries filled the resource
   */
  private record FlattenedResource(Resource resource, ResolvedMapping mapping) {}
}
