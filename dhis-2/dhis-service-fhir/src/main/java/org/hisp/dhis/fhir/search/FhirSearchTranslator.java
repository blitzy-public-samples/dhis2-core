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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.QueryFilter;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapper.FhirLogicalId;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.mapping.FhirTargetField;
import org.hisp.dhis.fhir.search.FhirSearchParameters.DateCriterion;
import org.hisp.dhis.fhir.search.FhirSearchParameters.IdentifierCriterion;
import org.hisp.dhis.fhir.search.FhirSearchParameters.Operation;
import org.hisp.dhis.fhir.search.FhirSearchParameters.ParsedSearch;
import org.hisp.dhis.fhir.search.FhirSearchParameters.PatientParameter;
import org.hisp.dhis.fhir.search.FhirSearchParameters.Token;
import org.hisp.dhis.fhir.service.FhirTrackerReader.FhirSearchOrigin;
import org.hisp.dhis.tracker.export.fieldfiltering.Fields;
import org.hisp.dhis.tracker.export.fieldfiltering.FieldsParser;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.EnrollmentRequestParams;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.TrackedEntityRequestParams;
import org.springframework.stereotype.Component;

/**
 * Translates validated FHIR queries ({@link ParsedSearch}) and read ids into the request parameters
 * of the Tracker export controllers: {@link TrackedEntityRequestParams} for {@code Patient} and
 * {@link EnrollmentRequestParams} for {@code Encounter}, {@code Immunization} and {@code
 * Observation}.
 *
 * <p>The translator makes no Tracker call and looks up no metadata: everything it needs comes from
 * the {@link ParsedSearch} and the {@link ResolvedMapping}s. Request parameters are populated only
 * through their setters, and only these are ever set:
 *
 * <ul>
 *   <li>Tracked entities: {@code trackedEntities}, exactly one of {@code program} (when the Patient
 *       mapping names a program) and {@code trackedEntityType} (otherwise), {@code filter}, {@code
 *       page}, {@code pageSize}, {@code totalPages=false} and {@code fields} ({@link
 *       #PATIENT_FIELDS}). {@code paging}, {@code orgUnits} and {@code orgUnitMode} keep their
 *       defaults.
 *   <li>Enrollments: {@code trackedEntity}, {@code enrollments}, {@code program}, {@code
 *       paging=false} and {@code fields} ({@link #EVENT_FIELDS}). {@code page}, {@code pageSize}
 *       and {@code totalPages} keep their defaults.
 * </ul>
 *
 * <p>Patient search criteria become Tracker attribute filters {@code
 * {teaUid}:{operator}:{escapedValue}}, joined by {@code ,}, built and validated in {@link
 * PatientParameter} order:
 *
 * <ul>
 *   <li>{@code identifier}: {@code eq} on the selected {@link FhirTargetField#PATIENT_IDENTIFIER}
 *       entry's attribute.
 *   <li>{@code family}, {@code given}: {@code sw} on the {@link
 *       FhirTargetField#PATIENT_FAMILY_NAME} or {@link FhirTargetField#PATIENT_GIVEN_NAME}
 *       attribute.
 *   <li>{@code birthdate}: the prefix operator ({@code eq}, {@code ge}, {@code le}, {@code gt},
 *       {@code lt}) on the {@link FhirTargetField#PATIENT_BIRTH_DATE} attribute.
 *   <li>{@code gender}: the attribute values the {@link FhirTargetField#PATIENT_GENDER} value map
 *       translates to a requested code, {@code eq} for one value and {@code in} with values joined
 *       by {@link QueryFilter#OPTION_SEP} for several. When no value maps to a requested code, no
 *       gender filter is built and the search is {@link TranslatedSearch#empty() empty}.
 * </ul>
 *
 * <p>Each filter is checked with {@link FhirSearchParameters#checkAttributeFilter} on its unescaped
 * value before it is added. That check's {@code 400 invalid}, naming the FHIR parameter, is the
 * only {@link FhirApiException} this class raises.
 *
 * <pre>{@code
 * ParsedSearch parsed = parameters.parse(Operation.PATIENT_SEARCH, request, patientMapping);
 * TranslatedSearch search = translator.toTrackedEntityParams(parsed, patientMapping);
 * if (!search.empty()) {
 *   reader.findTrackedEntities(search.trackedEntityParams(), request, search.origin());
 * }
 * }</pre>
 */
@Component
@RequiredArgsConstructor
public class FhirSearchTranslator {
  /** The Tracker {@code fields} selection of every tracked entity request. */
  public static final String PATIENT_FIELDS_PARAM =
      "trackedEntity,trackedEntityType,updatedAt,attributes";

  /** {@link #PATIENT_FIELDS_PARAM} parsed. */
  public static final Fields PATIENT_FIELDS = FieldsParser.parse(PATIENT_FIELDS_PARAM);

  /** The Tracker {@code fields} selection of every enrollment request. */
  public static final String EVENT_FIELDS_PARAM =
      "enrollment,trackedEntity,program,updatedAt,events[event,programStage,status,occurredAt,scheduledAt,updatedAt,dataValues[dataElement,value]]";

  /** {@link #EVENT_FIELDS_PARAM} parsed. */
  public static final Fields EVENT_FIELDS = FieldsParser.parse(EVENT_FIELDS_PARAM);

  /** The separator between the filters of the Tracker {@code filter} parameter. */
  static final String FILTER_SEPARATOR = ",";

  /** The separator between the attribute UID, the operator and the value of one filter. */
  static final String FILTER_SEGMENT_SEPARATOR = ":";

  private static final String ESCAPE = "/";

  private static final int PATIENT_READ_PAGE_SIZE = 1;

  private final FhirSearchParameters parameters;

  /**
   * Translates a Patient search into tracked entity request parameters.
   *
   * <p>Sets {@code trackedEntities} from {@code _id} when given, the program or else the tracked
   * entity type of the mapping, the attribute filters of {@code identifier}, {@code family}, {@code
   * given}, {@code birthdate} and {@code gender} when given, {@code page} and {@code pageSize} from
   * {@code _page} and {@code _count}, {@code totalPages=false} and {@link #PATIENT_FIELDS}.
   *
   * <p>The returned {@link TranslatedSearch#origin()} maps each filtered attribute UID to its FHIR
   * parameter in filter order, lists the attribute-backed parameters present in the query in {@link
   * PatientParameter} order, and lists {@link
   * FhirSearchParameters#configuredAttributeParameters(ResolvedMapping)} of the mapping. The search
   * is {@link TranslatedSearch#empty() empty} when {@code gender} was requested but no attribute
   * value maps to a requested code.
   *
   * @param parsed the validated query of a {@link Operation#PATIENT_SEARCH}
   * @param mapping the resolved Patient mapping the query was parsed with
   * @return the translated search, without logical ids or codes
   * @throws FhirApiException {@code 400 invalid} naming the first parameter, in {@link
   *     PatientParameter} order, whose filter the mapped attribute rejects
   * @throws IllegalArgumentException if {@code parsed} is not a Patient search, or a criterion's
   *     target has no attribute entry in {@code mapping}
   * @throws NullPointerException if an argument is {@code null}
   */
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

  /**
   * Translates an {@code Encounter}, {@code Immunization} or {@code Observation} search into the
   * enrollment request parameters of one candidate program.
   *
   * <p>Sets {@code trackedEntity} from {@code patient} or {@code subject} when given, {@code
   * enrollments} from the distinct enrollment segments of the {@code _id} logical ids when given,
   * {@code program}, {@code paging=false} and {@link #EVENT_FIELDS}. The returned search carries
   * the composed {@code _id} logical ids, the {@code code} tokens, {@code _count}, {@code _page}
   * and {@link FhirSearchOrigin#empty()}. An Observation search with {@code code} tokens is {@link
   * TranslatedSearch#empty() empty} when no {@link FhirTargetField#OBSERVATION_VALUE} entry of any
   * of {@code mappings} matches a token.
   *
   * @param parsed the validated query of an event search
   * @param mappings the usable mappings of the searched resource type
   * @param program the UID of the candidate program to read
   * @return the translated search, with an empty origin
   * @throws IllegalArgumentException if {@code parsed} is not an event search, or {@code program}
   *     is not a UID
   * @throws NullPointerException if an argument or a mapping is {@code null}
   */
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

  /**
   * Builds the tracked entity request parameters of a Patient read and of the Patient step of
   * {@code $everything}: {@code trackedEntities} holding the id, the program or else the tracked
   * entity type of the mapping, {@code pageSize=1}, {@code totalPages=false} and {@link
   * #PATIENT_FIELDS}.
   *
   * @param trackedEntityUid the validated tracked entity UID of the read
   * @param mapping the resolved Patient mapping
   * @return new request parameters
   * @throws IllegalArgumentException if {@code trackedEntityUid} is not a UID
   * @throws NullPointerException if an argument is {@code null}
   */
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

  /**
   * Builds the enrollment request parameters of an {@code Encounter}, {@code Immunization} or
   * {@code Observation} read in one candidate program: {@code enrollments} holding the enrollment
   * UID of the logical id, {@code program}, {@code paging=false} and {@link #EVENT_FIELDS}.
   *
   * @param enrollmentUid the validated enrollment UID of the logical id
   * @param program the UID of the candidate program
   * @return new request parameters
   * @throws IllegalArgumentException if an argument is not a UID
   * @throws NullPointerException if an argument is {@code null}
   */
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

  /**
   * Builds the enrollment request parameters of one program step of {@code Patient/$everything}:
   * {@code trackedEntity}, {@code program}, {@code paging=false} and {@link #EVENT_FIELDS}.
   *
   * @param trackedEntityUid the validated tracked entity UID of the Patient
   * @param program the UID of the candidate program
   * @return new request parameters
   * @throws IllegalArgumentException if an argument is not a UID
   * @throws NullPointerException if an argument is {@code null}
   */
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

  /**
   * Escapes a filter value for the Tracker filter grammar: {@code /} becomes {@code //}, then
   * {@code ,} becomes {@code /,} and {@code :} becomes {@code /:}.
   *
   * @param value the unescaped value
   * @return the escaped value
   */
  @Nonnull
  static String escape(@Nonnull String value) {
    return value
        .replace(ESCAPE, ESCAPE + ESCAPE)
        .replace(FILTER_SEPARATOR, ESCAPE + FILTER_SEPARATOR)
        .replace(FILTER_SEGMENT_SEPARATOR, ESCAPE + FILTER_SEGMENT_SEPARATOR);
  }

  /** Sets {@code program} when the mapping names one, otherwise {@code trackedEntityType}. */
  private static void applyPatientSelector(
      TrackedEntityRequestParams params, ResolvedMapping mapping) {
    if (mapping.program() != null) {
      params.setProgram(UID.of(mapping.program()));
    } else {
      params.setTrackedEntityType(
          UID.of(Objects.requireNonNull(mapping.trackedEntityType(), "trackedEntityType")));
    }
  }

  /** Sets {@code program}, {@code paging=false} and {@link #EVENT_FIELDS}. */
  private static void applyEventSelection(EnrollmentRequestParams params, String program) {
    params.setProgram(UID.of(program));
    params.setPaging(false);
    params.setFields(EVENT_FIELDS);
  }

  /** Returns whether the query holds a criterion for the parameter. */
  private static boolean isRequested(PatientParameter parameter, ParsedSearch parsed) {
    return switch (parameter) {
      case IDENTIFIER -> parsed.identifier() != null;
      case FAMILY -> parsed.family() != null;
      case GIVEN -> parsed.given() != null;
      case BIRTHDATE -> parsed.birthdate() != null;
      case GENDER -> !parsed.genders().isEmpty();
    };
  }

  /** Returns the {@code eq} filter on the selected identifier entry's attribute. */
  private static AttributeFilter identifierFilter(IdentifierCriterion identifier) {
    return new AttributeFilter(
        requireSource(PatientParameter.IDENTIFIER, identifier.entry()),
        PatientParameter.IDENTIFIER.defaultOperator(),
        identifier.value());
  }

  /** Returns the {@code sw} filter of {@code family} or {@code given}. */
  private static AttributeFilter textFilter(
      PatientParameter parameter, String value, ResolvedMapping mapping) {
    return new AttributeFilter(attributeOf(parameter, mapping), parameter.defaultOperator(), value);
  }

  /** Returns the filter of {@code birthdate} with the operator of its prefix. */
  private static AttributeFilter birthdateFilter(DateCriterion birthdate, ResolvedMapping mapping) {
    return new AttributeFilter(
        attributeOf(PatientParameter.BIRTHDATE, mapping), birthdate.operator(), birthdate.date());
  }

  /**
   * Returns the {@code gender} filter over the attribute values whose mapped code is requested,
   * distinct and sorted: {@code eq} for one value, {@code in} for several; {@code null} when no
   * value maps to a requested code.
   */
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

  /** Returns the attribute UID of the parameter's single target entry. */
  private static String attributeOf(PatientParameter parameter, ResolvedMapping mapping) {
    return requireSource(parameter, entryOf(parameter, mapping));
  }

  /** Returns the parameter's single target entry. */
  private static FhirFieldMapping entryOf(PatientParameter parameter, ResolvedMapping mapping) {
    return mapping
        .entry(parameter.target())
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "The mapping has no " + parameter.target() + " entry for " + parameter));
  }

  /** Returns the attribute UID of an entry. */
  private static String requireSource(PatientParameter parameter, FhirFieldMapping entry) {
    String source = entry.getSource();
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException(
          "The " + parameter.target() + " entry for " + parameter + " has no source attribute");
    }
    return source;
  }

  /**
   * Returns whether any token matches the entry's coding, as described on {@link
   * TranslatedSearch#matchesCode(FhirFieldMapping)}.
   */
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

  /**
   * Returns whether a token system matches an entry system: the empty token system matches a {@code
   * null} or blank entry system, any other token system an equal entry system.
   */
  private static boolean systemMatches(String system, @CheckForNull String entrySystem) {
    if (system.isEmpty()) {
      return entrySystem == null || entrySystem.isBlank();
    }
    return system.equals(entrySystem);
  }

  /**
   * One Tracker attribute filter.
   *
   * @param teaUid the filtered tracked entity attribute UID
   * @param operator the filter operator
   * @param value the unescaped filter value
   */
  private record AttributeFilter(String teaUid, QueryOperator operator, String value) {
    /** Returns {@code {teaUid}:{operator}:{escapedValue}} with the operator in lower case. */
    String toFilterString() {
      return teaUid
          + FILTER_SEGMENT_SEPARATOR
          + operator.name().toLowerCase(Locale.ROOT)
          + FILTER_SEGMENT_SEPARATOR
          + escape(value);
    }
  }

  /**
   * The Tracker request of one FHIR search, together with what the caller applies to the export
   * result.
   *
   * @param params the {@link TrackedEntityRequestParams} of a Patient search or the {@link
   *     EnrollmentRequestParams} of an event search; fully built even when {@code empty}
   * @param origin the FHIR parameter behind each attribute filter of a Patient search; {@link
   *     FhirSearchOrigin#empty()} for event searches; {@code null} becomes {@link
   *     FhirSearchOrigin#empty()}
   * @param logicalIds the composed logical ids of an event search {@code _id}; empty when every id
   *     matches; {@code null} becomes empty
   * @param codes the Observation search {@code code} tokens; empty when every entry matches; {@code
   *     null} becomes empty
   * @param count the requested page size
   * @param page the requested page number
   * @param empty {@code true} when the search cannot match anything, in which case the caller
   *     returns an empty Bundle without a Tracker call
   */
  public record TranslatedSearch(
      Object params,
      FhirSearchOrigin origin,
      Set<String> logicalIds,
      List<Token> codes,
      int count,
      int page,
      boolean empty) {

    /**
     * Replaces {@code logicalIds} and {@code codes} with unmodifiable copies that keep their
     * iteration order.
     *
     * @throws NullPointerException if {@code params}, an id or a token is {@code null}
     */
    public TranslatedSearch {
      Objects.requireNonNull(params, "params");
      origin = origin == null ? FhirSearchOrigin.empty() : origin;
      logicalIds =
          logicalIds == null || logicalIds.isEmpty()
              ? Set.of()
              : Collections.unmodifiableSet(new LinkedHashSet<>(requireElements(logicalIds)));
      codes = codes == null ? List.of() : List.copyOf(codes);
    }

    /**
     * Returns the request of a Patient search.
     *
     * @return {@link #params()} as tracked entity request parameters
     * @throws IllegalStateException if the search is an event search
     */
    public TrackedEntityRequestParams trackedEntityParams() {
      if (params instanceof TrackedEntityRequestParams trackedEntityParams) {
        return trackedEntityParams;
      }
      throw new IllegalStateException(
          "The translated search holds " + params.getClass().getSimpleName() + " parameters");
    }

    /**
     * Returns the request of an event search.
     *
     * @return {@link #params()} as enrollment request parameters
     * @throws IllegalStateException if the search is a Patient search
     */
    public EnrollmentRequestParams enrollmentParams() {
      if (params instanceof EnrollmentRequestParams enrollmentParams) {
        return enrollmentParams;
      }
      throw new IllegalStateException(
          "The translated search holds " + params.getClass().getSimpleName() + " parameters");
    }

    /**
     * Returns whether a resource with the given logical id is requested.
     *
     * @param logicalId the composed logical id of a resource
     * @return {@code true} when no {@code _id} was requested or {@code logicalId} is one of them
     */
    public boolean matchesId(@CheckForNull String logicalId) {
      return logicalIds.isEmpty() || (logicalId != null && logicalIds.contains(logicalId));
    }

    /**
     * Returns whether a field mapping entry's coding matches a requested {@code code} token. Codes
     * and systems are compared exactly and case-sensitively:
     *
     * <ul>
     *   <li>a token without a system matches an entry with the same code;
     *   <li>a token with the empty system ({@code |code}) matches an entry with the same code and a
     *       {@code null} or blank system;
     *   <li>any other token matches an entry with the same system and the same code.
     * </ul>
     *
     * @param entry the field mapping entry, typically an {@link FhirTargetField#OBSERVATION_VALUE}
     * @return {@code true} when no code was requested or any token matches {@code entry}
     */
    public boolean matchesCode(@CheckForNull FhirFieldMapping entry) {
      return codes.isEmpty() || matchesAnyToken(codes, entry);
    }

    private static <T> Collection<T> requireElements(Collection<T> values) {
      values.forEach(value -> Objects.requireNonNull(value, "logicalId"));
      return values;
    }
  }
}
