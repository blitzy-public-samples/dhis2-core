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
package org.hisp.dhis.fhir.search;

import java.util.*;
import javax.annotation.*;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.QueryFilter;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapper.FhirLogicalId;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.search.FhirSearchParameters.*;
import org.hisp.dhis.fhir.service.FhirTrackerReader.FhirSearchOrigin;
import org.hisp.dhis.tracker.export.fieldfiltering.Fields;
import org.hisp.dhis.tracker.export.fieldfiltering.FieldsParser;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.EnrollmentRequestParams;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.TrackedEntityRequestParams;
import org.springframework.stereotype.Component;

/** Translates validated FHIR queries and read ids into Tracker export request parameters. */
@Component
@RequiredArgsConstructor
public class FhirSearchTranslator {
  public static final String PATIENT_FIELDS_PARAM =
      "trackedEntity,trackedEntityType,updatedAt,attributes";
  public static final Fields PATIENT_FIELDS = FieldsParser.parse(PATIENT_FIELDS_PARAM);
  public static final String EVENT_FIELDS_PARAM =
      "enrollment,trackedEntity,program,updatedAt,events[event,programStage,status,occurredAt,scheduledAt,updatedAt,dataValues[dataElement,value]]";
  public static final Fields EVENT_FIELDS = FieldsParser.parse(EVENT_FIELDS_PARAM);
  static final String FILTER_SEPARATOR = ",";
  static final String FILTER_SEGMENT_SEPARATOR = ":";
  private static final String ESCAPE = "/";
  private static final int PATIENT_READ_PAGE_SIZE = 1;
  private final FhirSearchParameters parameters;

  /** Translates a Patient search, throwing {@link FhirApiException} for a rejected filter. */
  @Nonnull
  public TranslatedSearch toTrackedEntityParams(
      @Nonnull ParsedSearch parsed, @Nonnull ResolvedMapping mapping) {
    Objects.requireNonNull(parsed, "parsed");
    Objects.requireNonNull(mapping, "mapping");
    if (parsed.operation() != Operation.PATIENT_SEARCH) {
      throw new IllegalArgumentException("A Patient search is required, not " + parsed.operation());
    }
    TrackedEntityRequestParams params = new TrackedEntityRequestParams();
    if (!parsed.trackedEntityIds().isEmpty()) {
      params.setTrackedEntities(UID.of(parsed.trackedEntityIds()));
    }
    applyPatientSelector(params, mapping);
    params.setPage(parsed.page());
    params.setPageSize(parsed.count());
    params.setTotalPages(false);
    params.setFields(PATIENT_FIELDS);
    List<String> filters = new ArrayList<>();
    Map<String, String> attributeToParameter = new LinkedHashMap<>();
    List<String> suppliedAttributeParameters = new ArrayList<>();
    boolean empty = false;
    for (PatientParameter parameter : PatientParameter.values()) {
      if (!isRequested(parameter, parsed)) {
        continue;
      }
      suppliedAttributeParameters.add(parameter.parameter());
      AttributeFilter filter =
          switch (parameter) {
            case IDENTIFIER -> identifierFilter(parsed.identifier());
            case FAMILY -> textFilter(parameter, parsed.family(), mapping);
            case GIVEN -> textFilter(parameter, parsed.given(), mapping);
            case BIRTHDATE -> birthdateFilter(parsed.birthdate(), mapping);
            case GENDER -> genderFilter(parsed.genders(), mapping);
          };
      if (filter == null) {
        empty = true;
        continue;
      }
      parameters.checkAttributeFilter(
          parameter.parameter(), filter.teaUid(), filter.operator(), filter.value(), mapping);
      filters.add(filter.toFilterString());
      attributeToParameter.put(filter.teaUid(), parameter.parameter());
    }
    if (!filters.isEmpty()) {
      params.setFilter(String.join(FILTER_SEPARATOR, filters));
    }
    FhirSearchOrigin origin =
        new FhirSearchOrigin(
            attributeToParameter,
            suppliedAttributeParameters,
            parameters.configuredAttributeParameters(mapping));
    return new TranslatedSearch(
        params, origin, Set.of(), List.of(), parsed.count(), parsed.page(), empty);
  }

  /** Translates an event search into the enrollment request parameters of one program. */
  @Nonnull
  public TranslatedSearch toEnrollmentParams(
      @Nonnull ParsedSearch parsed,
      @Nonnull List<ResolvedMapping> mappings,
      @Nonnull String program) {
    Objects.requireNonNull(parsed, "parsed");
    Objects.requireNonNull(mappings, "mappings");
    Objects.requireNonNull(program, "program");
    mappings.forEach(mapping -> Objects.requireNonNull(mapping, "mapping"));
    FhirResourceType type = parsed.operation().resourceType();
    if (type == null || !type.isEventDerived()) {
      throw new IllegalArgumentException("An event search is required, not " + parsed.operation());
    }
    EnrollmentRequestParams params = new EnrollmentRequestParams();
    if (parsed.patient() != null) {
      params.setTrackedEntity(UID.of(parsed.patient()));
    }
    if (!parsed.logicalIds().isEmpty()) {
      params.setEnrollments(
          UID.of(parsed.logicalIds().stream().map(FhirLogicalId::enrollment).distinct().toList()));
    }
    applyEventSelection(params, program);
    Set<String> logicalIds = new LinkedHashSet<>();
    parsed.logicalIds().forEach(id -> logicalIds.add(id.compose()));
    boolean empty =
        parsed.operation() == Operation.OBSERVATION_SEARCH
            && !parsed.codes().isEmpty()
            && mappings.stream()
                .flatMap(mapping -> mapping.entries(FhirTargetField.OBSERVATION_VALUE).stream())
                .noneMatch(entry -> matchesAnyToken(parsed.codes(), entry));
    return new TranslatedSearch(
        params,
        FhirSearchOrigin.empty(),
        logicalIds,
        parsed.codes(),
        parsed.count(),
        parsed.page(),
        empty);
  }

  @Nonnull
  public TrackedEntityRequestParams patientReadParams(
      @Nonnull String trackedEntityUid, @Nonnull ResolvedMapping mapping) {
    Objects.requireNonNull(trackedEntityUid, "trackedEntityUid");
    Objects.requireNonNull(mapping, "mapping");
    TrackedEntityRequestParams params = new TrackedEntityRequestParams();
    params.setTrackedEntities(Set.of(UID.of(trackedEntityUid)));
    applyPatientSelector(params, mapping);
    params.setPageSize(PATIENT_READ_PAGE_SIZE);
    params.setTotalPages(false);
    params.setFields(PATIENT_FIELDS);
    return params;
  }

  @Nonnull
  public EnrollmentRequestParams eventReadParams(
      @Nonnull String enrollmentUid, @Nonnull String program) {
    Objects.requireNonNull(enrollmentUid, "enrollmentUid");
    Objects.requireNonNull(program, "program");
    EnrollmentRequestParams params = new EnrollmentRequestParams();
    params.setEnrollments(Set.of(UID.of(enrollmentUid)));
    applyEventSelection(params, program);
    return params;
  }

  @Nonnull
  public EnrollmentRequestParams everythingParams(
      @Nonnull String trackedEntityUid, @Nonnull String program) {
    Objects.requireNonNull(trackedEntityUid, "trackedEntityUid");
    Objects.requireNonNull(program, "program");
    EnrollmentRequestParams params = new EnrollmentRequestParams();
    params.setTrackedEntity(UID.of(trackedEntityUid));
    applyEventSelection(params, program);
    return params;
  }

  @Nonnull
  static String escape(@Nonnull String value) {
    return value
        .replace(ESCAPE, ESCAPE + ESCAPE)
        .replace(FILTER_SEPARATOR, ESCAPE + FILTER_SEPARATOR)
        .replace(FILTER_SEGMENT_SEPARATOR, ESCAPE + FILTER_SEGMENT_SEPARATOR);
  }

  private static void applyPatientSelector(
      TrackedEntityRequestParams params, ResolvedMapping mapping) {
    if (mapping.program() != null) {
      params.setProgram(UID.of(mapping.program()));
    } else {
      params.setTrackedEntityType(
          UID.of(Objects.requireNonNull(mapping.trackedEntityType(), "trackedEntityType")));
    }
  }

  private static void applyEventSelection(EnrollmentRequestParams params, String program) {
    params.setProgram(UID.of(program));
    params.setPaging(false);
    params.setFields(EVENT_FIELDS);
  }

  private static boolean isRequested(PatientParameter parameter, ParsedSearch parsed) {
    return switch (parameter) {
      case IDENTIFIER -> parsed.identifier() != null;
      case FAMILY -> parsed.family() != null;
      case GIVEN -> parsed.given() != null;
      case BIRTHDATE -> parsed.birthdate() != null;
      case GENDER -> !parsed.genders().isEmpty();
    };
  }

  private static AttributeFilter identifierFilter(IdentifierCriterion identifier) {
    return new AttributeFilter(
        requireSource(PatientParameter.IDENTIFIER, identifier.entry()),
        PatientParameter.IDENTIFIER.defaultOperator(),
        identifier.value());
  }

  private static AttributeFilter textFilter(
      PatientParameter parameter, String value, ResolvedMapping mapping) {
    return new AttributeFilter(attributeOf(parameter, mapping), parameter.defaultOperator(), value);
  }

  private static AttributeFilter birthdateFilter(DateCriterion birthdate, ResolvedMapping mapping) {
    return new AttributeFilter(
        attributeOf(PatientParameter.BIRTHDATE, mapping), birthdate.operator(), birthdate.date());
  }

  @CheckForNull
  private static AttributeFilter genderFilter(List<String> genders, ResolvedMapping mapping) {
    FhirFieldMapping entry = entryOf(PatientParameter.GENDER, mapping);
    String teaUid = requireSource(PatientParameter.GENDER, entry);
    Set<String> values = new TreeSet<>();
    Map<String, String> valueMap = entry.getValueMap();
    if (valueMap != null) {
      valueMap.forEach(
          (value, code) -> {
            if (value != null && code != null && genders.contains(code)) {
              if (value.isBlank() || value.contains(QueryFilter.OPTION_SEP)) {
                throw new IllegalArgumentException(
                    "The "
                        + PatientParameter.GENDER.target()
                        + " entry for "
                        + PatientParameter.GENDER
                        + " maps an attribute value that is blank or contains '"
                        + QueryFilter.OPTION_SEP
                        + "' to "
                        + code);
              }
              values.add(value);
            }
          });
    }
    if (values.isEmpty()) {
      return null;
    }
    return values.size() == 1
        ? new AttributeFilter(teaUid, QueryOperator.EQ, values.iterator().next())
        : new AttributeFilter(
            teaUid, QueryOperator.IN, String.join(QueryFilter.OPTION_SEP, values));
  }

  private static String attributeOf(PatientParameter parameter, ResolvedMapping mapping) {
    return requireSource(parameter, entryOf(parameter, mapping));
  }

  private static FhirFieldMapping entryOf(PatientParameter parameter, ResolvedMapping mapping) {
    return mapping
        .entry(parameter.target())
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "The mapping has no " + parameter.target() + " entry for " + parameter));
  }

  private static String requireSource(PatientParameter parameter, FhirFieldMapping entry) {
    String source = entry.getSource();
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException(
          "The " + parameter.target() + " entry for " + parameter + " has no source attribute");
    }
    return source;
  }

  private static boolean matchesAnyToken(List<Token> tokens, @CheckForNull FhirFieldMapping entry) {
    if (entry == null || entry.getCode() == null) {
      return false;
    }
    for (Token token : tokens) {
      if (token.value().equals(entry.getCode())
          && (token.system() == null || systemMatches(token.system(), entry.getSystem()))) {
        return true;
      }
    }
    return false;
  }

  private static boolean systemMatches(String system, @CheckForNull String entrySystem) {
    if (system.isEmpty()) {
      return entrySystem == null || entrySystem.isBlank();
    }
    return system.equals(entrySystem);
  }

  private record AttributeFilter(String teaUid, QueryOperator operator, String value) {
    String toFilterString() {
      return teaUid
          + FILTER_SEGMENT_SEPARATOR
          + operator.name().toLowerCase(Locale.ROOT)
          + FILTER_SEGMENT_SEPARATOR
          + escape(value);
    }
  }

  /** The Tracker request of one FHIR search; {@code empty} when it cannot match anything. */
  public record TranslatedSearch(
      Object params,
      FhirSearchOrigin origin,
      Set<String> logicalIds,
      List<Token> codes,
      int count,
      int page,
      boolean empty) {
    public TranslatedSearch {
      Objects.requireNonNull(params, "params");
      origin = origin == null ? FhirSearchOrigin.empty() : origin;
      logicalIds =
          logicalIds == null || logicalIds.isEmpty()
              ? Set.of()
              : Collections.unmodifiableSet(new LinkedHashSet<>(requireElements(logicalIds)));
      codes = codes == null ? List.of() : List.copyOf(codes);
    }

    public TrackedEntityRequestParams trackedEntityParams() {
      if (params instanceof TrackedEntityRequestParams trackedEntityParams) {
        return trackedEntityParams;
      }
      throw new IllegalStateException(
          "The translated search holds " + params.getClass().getSimpleName() + " parameters");
    }

    public EnrollmentRequestParams enrollmentParams() {
      if (params instanceof EnrollmentRequestParams enrollmentParams) {
        return enrollmentParams;
      }
      throw new IllegalStateException(
          "The translated search holds " + params.getClass().getSimpleName() + " parameters");
    }

    /** Returns whether no {@code _id} was requested or {@code logicalId} is one of them. */
    public boolean matchesId(@CheckForNull String logicalId) {
      return logicalIds.isEmpty() || (logicalId != null && logicalIds.contains(logicalId));
    }

    /** Returns whether no {@code code} was requested or a token matches the entry's coding. */
    public boolean matchesCode(@CheckForNull FhirFieldMapping entry) {
      return codes.isEmpty() || matchesAnyToken(codes, entry);
    }

    private static <T> Collection<T> requireElements(Collection<T> values) {
      values.forEach(value -> Objects.requireNonNull(value, "logicalId"));
      return values;
    }
  }
}
