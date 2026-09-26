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

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.QueryFilter;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapper.FhirLogicalId;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.mapping.FhirTargetField;
import org.hisp.dhis.setting.SystemSettings;
import org.hisp.dhis.setting.SystemSettingsProvider;
import org.hl7.fhir.r4.model.Enumerations;
import org.springframework.stereotype.Component;

/**
 * Parses and validates the query parameters of every FHIR R4 operation under {@code /api/fhir}.
 *
 * <p>The query is read from {@link HttpServletRequest#getParameterMap()} only. Every rejection is a
 * {@link FhirApiException#invalidParameter(String, String) 400 invalid} naming exactly one
 * parameter. {@link #parse} checks, in this order: parameter names and repeats in parameter-map
 * order; values in {@link Operation#allowed()} order; the Patient search page size against the
 * {@code KeyTrackedEntityMaxLimit} system setting; and, for event searches, the presence of {@code
 * patient}, {@code subject} or {@code _id}. {@link #checkAttributeFilter} validates each Tracker
 * attribute filter derived from a Patient search against the attribute's search constraints.
 *
 * <pre>{@code
 * ParsedSearch parsed = parameters.parse(Operation.PATIENT_SEARCH, request, patientMapping);
 * parameters.checkFormatOnly(Operation.READ, request);
 * }</pre>
 */
@Component
@RequiredArgsConstructor
public class FhirSearchParameters {
  /** The parameter names; the values each accepts are listed on {@link #parse}. */
  public static final String ID = "_id";

  public static final String COUNT = "_count";
  public static final String PAGE = "_page";
  public static final String FORMAT = "_format";
  public static final String IDENTIFIER = "identifier";
  public static final String FAMILY = "family";
  public static final String GIVEN = "given";
  public static final String BIRTHDATE = "birthdate";
  public static final String GENDER = "gender";
  public static final String PATIENT = "patient";
  public static final String SUBJECT = "subject";
  public static final String CODE = "code";

  /** The page size and page number used when {@code _count} or {@code _page} is absent. */
  public static final int DEFAULT_COUNT = 50;

  public static final int DEFAULT_PAGE = 1;

  /** The accepted {@code _format} values, matched exactly. */
  public static final Set<String> FORMATS =
      Set.of("json", "application/json", "application/fhir+json");

  /** The accepted {@code gender} values: the FHIR administrative-gender codes. */
  public static final Set<String> GENDER_CODES = Set.of("male", "female", "other", "unknown");

  private static final Set<String> OR_PARAMETERS = Set.of(ID, GENDER, CODE);
  private static final String OR_SEPARATOR = ",";
  private static final char TOKEN_SEPARATOR = '|';
  private static final String PATIENT_REFERENCE_PREFIX = FhirResourceType.PATIENT.fhirType() + "/";
  private static final Pattern POSITIVE_INTEGER = Pattern.compile("^[1-9][0-9]*$");
  private static final Pattern BIRTHDATE_VALUE =
      Pattern.compile("^(eq|ge|le|gt|lt)?([0-9]{4}-[0-9]{2}-[0-9]{2})$");
  private static final Pattern ISO_DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
  private static final String TRUE = "true";
  private static final String FALSE = "false";

  private final SystemSettingsProvider settingsProvider;

  /**
   * A FHIR operation and the query parameters it accepts: {@link #READ} ({@code GET /{Type}/{id}}),
   * {@link #EVERYTHING} ({@code GET /Patient/{id}/$everything}), {@link #METADATA} ({@code GET
   * /metadata}) and one search-type operation per resource type.
   */
  public enum Operation {
    READ(null, List.of(FORMAT)),
    EVERYTHING(null, List.of(FORMAT)),
    METADATA(null, List.of(FORMAT)),
    PATIENT_SEARCH(
        FhirResourceType.PATIENT,
        List.of(ID, IDENTIFIER, FAMILY, GIVEN, BIRTHDATE, GENDER, COUNT, PAGE, FORMAT)),
    ENCOUNTER_SEARCH(
        FhirResourceType.ENCOUNTER, List.of(PATIENT, SUBJECT, ID, COUNT, PAGE, FORMAT)),
    IMMUNIZATION_SEARCH(FhirResourceType.IMMUNIZATION, List.of(PATIENT, ID, COUNT, PAGE, FORMAT)),
    OBSERVATION_SEARCH(
        FhirResourceType.OBSERVATION, List.of(PATIENT, SUBJECT, ID, CODE, COUNT, PAGE, FORMAT));

    @CheckForNull private final FhirResourceType resourceType;

    private final List<String> allowed;

    Operation(@CheckForNull FhirResourceType resourceType, List<String> allowed) {
      this.resourceType = resourceType;
      this.allowed = allowed;
    }

    /**
     * @return the accepted parameter names, unmodifiable, in the order their values are validated
     */
    public List<String> allowed() {
      return allowed;
    }

    /**
     * @return the searched resource type, or {@code null} for {@link #READ}, {@link #EVERYTHING}
     *     and {@link #METADATA}
     */
    @CheckForNull
    public FhirResourceType resourceType() {
      return resourceType;
    }

    /**
     * @return {@code true} for the {@code *_SEARCH} operations
     */
    public boolean isSearch() {
      return resourceType != null;
    }

    /**
     * Returns the search operation of a resource type.
     *
     * @param type the searched resource type
     * @return the matching {@code *_SEARCH} operation
     * @throws NullPointerException if {@code type} is {@code null}
     */
    public static Operation search(FhirResourceType type) {
      Objects.requireNonNull(type, "type");
      return switch (type) {
        case PATIENT -> PATIENT_SEARCH;
        case ENCOUNTER -> ENCOUNTER_SEARCH;
        case IMMUNIZATION -> IMMUNIZATION_SEARCH;
        case OBSERVATION -> OBSERVATION_SEARCH;
      };
    }
  }

  /**
   * The attribute-backed Patient search parameters, each with the Patient target it searches and
   * the Tracker filter operator it uses by default. Declaration order is the order in which Tracker
   * filters are built and validated.
   */
  public enum PatientParameter {
    IDENTIFIER(
        FhirSearchParameters.IDENTIFIER, FhirTargetField.PATIENT_IDENTIFIER, QueryOperator.EQ),
    FAMILY(FhirSearchParameters.FAMILY, FhirTargetField.PATIENT_FAMILY_NAME, QueryOperator.SW),
    GIVEN(FhirSearchParameters.GIVEN, FhirTargetField.PATIENT_GIVEN_NAME, QueryOperator.SW),
    BIRTHDATE(FhirSearchParameters.BIRTHDATE, FhirTargetField.PATIENT_BIRTH_DATE, QueryOperator.EQ),
    GENDER(FhirSearchParameters.GENDER, FhirTargetField.PATIENT_GENDER, QueryOperator.EQ);

    private final String parameter;

    private final FhirTargetField target;

    private final QueryOperator defaultOperator;

    PatientParameter(String parameter, FhirTargetField target, QueryOperator defaultOperator) {
      this.parameter = parameter;
      this.target = target;
      this.defaultOperator = defaultOperator;
    }

    /**
     * @return the FHIR search parameter name, for example {@code family}
     */
    public String parameter() {
      return parameter;
    }

    /**
     * @return the Patient target whose attribute the parameter searches
     */
    public FhirTargetField target() {
      return target;
    }

    /**
     * @return the Tracker filter operator of the parameter; {@link #BIRTHDATE} replaces it with the
     *     operator of its prefix
     */
    public QueryOperator defaultOperator() {
      return defaultOperator;
    }
  }

  /**
   * A FHIR token value split at its first {@code |}.
   *
   * @param system {@code null} when the input has no {@code |}; the empty string when the input
   *     starts with {@code |}; otherwise the text before the first {@code |}
   * @param value the text after the first {@code |}, or the whole input when it has none
   */
  public record Token(@CheckForNull String system, String value) {
    public Token {
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * The {@code identifier} criterion of a Patient search.
   *
   * @param entry the one {@link FhirTargetField#PATIENT_IDENTIFIER} entry the request selects
   * @param value the identifier value
   */
  public record IdentifierCriterion(FhirFieldMapping entry, String value) {
    public IdentifierCriterion {
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(value, "value");
    }
  }

  /**
   * The {@code birthdate} criterion of a Patient search.
   *
   * @param operator one of {@link QueryOperator#EQ}, {@link QueryOperator#GE}, {@link
   *     QueryOperator#LE}, {@link QueryOperator#GT} and {@link QueryOperator#LT}
   * @param date a valid calendar date formatted {@code yyyy-MM-dd}
   */
  public record DateCriterion(QueryOperator operator, String date) {
    public DateCriterion {
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(date, "date");
    }
  }

  /**
   * The validated query of one FHIR operation. Absent parameters leave their component empty,
   * {@code null} or at its default.
   *
   * @param operation the operation the query was parsed for
   * @param trackedEntityIds the tracked entity UIDs of a Patient search {@code _id}
   * @param logicalIds the logical ids of an event search {@code _id}
   * @param patient the tracked entity UID of an event search {@code patient} or {@code subject}
   * @param identifier the Patient search {@code identifier} criterion
   * @param family the raw Patient search {@code family} value
   * @param given the raw Patient search {@code given} value
   * @param birthdate the Patient search {@code birthdate} criterion
   * @param genders the Patient search {@code gender} codes
   * @param codes the Observation search {@code code} tokens
   * @param count the page size, {@link #DEFAULT_COUNT} when {@code _count} is absent
   * @param page the page number, {@link #DEFAULT_PAGE} when {@code _page} is absent
   */
  public record ParsedSearch(
      Operation operation,
      List<String> trackedEntityIds,
      List<FhirLogicalId> logicalIds,
      @CheckForNull String patient,
      @CheckForNull IdentifierCriterion identifier,
      @CheckForNull String family,
      @CheckForNull String given,
      @CheckForNull DateCriterion birthdate,
      List<String> genders,
      List<Token> codes,
      int count,
      int page) {

    /**
     * Replaces every list with an unmodifiable copy without duplicates, keeping the first
     * occurrence of each element in input order; {@code null} becomes empty.
     *
     * @throws NullPointerException if {@code operation} or a list element is {@code null}
     * @throws IllegalArgumentException if {@code count} or {@code page} is not positive
     */
    public ParsedSearch {
      Objects.requireNonNull(operation, "operation");
      if (count < 1 || page < 1) {
        throw new IllegalArgumentException("count and page must be positive");
      }
      trackedEntityIds = distinct(trackedEntityIds);
      logicalIds = distinct(logicalIds);
      genders = distinct(genders);
      codes = distinct(codes);
    }

    private static <T> List<T> distinct(@CheckForNull List<T> values) {
      return values == null ? List.of() : List.copyOf(new LinkedHashSet<>(values));
    }
  }

  /**
   * Parses and validates the query of an operation.
   *
   * <p>Accepted values:
   *
   * <ul>
   *   <li>{@code _format}: one of {@link #FORMATS}.
   *   <li>{@code _id}: comma-separated UIDs for Patient searches; comma-separated logical ids of
   *       the searched type (see {@link FhirLogicalId#parse}) for event searches.
   *   <li>{@code patient}, {@code subject}: a UID, optionally prefixed with {@code Patient/}; the
   *       two cannot be combined, which is reported on {@code subject}.
   *   <li>{@code identifier}: {@code [system|]value}. With a system, including the empty system of
   *       a leading {@code |}, exactly one configured identifier entry must have that system;
   *       without one, exactly one identifier entry must be configured.
   *   <li>{@code family}, {@code given}: any text, kept as given.
   *   <li>{@code birthdate}: {@code yyyy-MM-dd}, a valid calendar date, with an optional prefix
   *       {@code eq}, {@code ge}, {@code le}, {@code gt} or {@code lt}; no prefix means {@code eq}.
   *   <li>{@code gender}: comma-separated codes of {@link #GENDER_CODES}.
   *   <li>{@code code}: comma-separated {@code [system|]code} tokens with a non-blank code.
   *   <li>{@code _count}, {@code _page}: a positive integer within the {@code int} range, where
   *       {@code (_page - 1) * _count} must also fit. The Patient search page size, given or
   *       defaulted, must not exceed a positive {@code KeyTrackedEntityMaxLimit} system setting.
   * </ul>
   *
   * <p>{@code identifier}, {@code family}, {@code given}, {@code birthdate} and {@code gender} are
   * rejected when the Patient mapping has no entry for their {@link PatientParameter#target()}.
   *
   * @param operation the operation whose parameters are accepted
   * @param request the request whose parameter map is read
   * @param patientMapping the resolved Patient mapping; required for {@link
   *     Operation#PATIENT_SEARCH} and ignored for every other operation
   * @return the validated query
   * @throws FhirApiException {@code 400 invalid} naming the first offending parameter
   * @throws IllegalArgumentException if {@code patientMapping} is {@code null} for a Patient search
   * @throws NullPointerException if {@code operation} or {@code request} is {@code null}
   */
  public ParsedSearch parse(
      Operation operation,
      HttpServletRequest request,
      @CheckForNull ResolvedMapping patientMapping) {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(request, "request");
    if (operation == Operation.PATIENT_SEARCH && patientMapping == null) {
      throw new IllegalArgumentException("A Patient mapping is required to parse a Patient search");
    }

    Map<String, String> query = readQuery(operation, request);
    ParseState state = new ParseState();
    for (String name : operation.allowed()) {
      String value = query.get(name);
      if (value != null) {
        parseValue(operation, name, value, query, patientMapping, state);
      }
    }

    if (operation == Operation.PATIENT_SEARCH && !query.containsKey(COUNT)) {
      checkPatientPageSize(state.count, false);
    }

    FhirResourceType type = operation.resourceType();
    if (type != null
        && type.isEventDerived()
        && !query.containsKey(PATIENT)
        && !query.containsKey(SUBJECT)
        && !query.containsKey(ID)) {
      throw FhirApiException.invalidParameter(
          PATIENT,
          operation.allowed().contains(SUBJECT)
              ? "patient, subject or _id is required"
              : "patient or _id is required");
    }

    return new ParsedSearch(
        operation,
        state.trackedEntityIds,
        state.logicalIds,
        state.patient,
        state.identifier,
        state.family,
        state.given,
        state.birthdate,
        state.genders,
        state.codes,
        state.count,
        state.page);
  }

  /**
   * Validates the query of an operation that accepts only {@code _format}: a missing {@code
   * _format} or one of {@link #FORMATS} is accepted; every other parameter, another {@code _format}
   * value and a repeated {@code _format} are rejected.
   *
   * @param operation {@link Operation#READ}, {@link Operation#EVERYTHING} or {@link
   *     Operation#METADATA}
   * @param request the request whose parameter map is read
   * @throws FhirApiException {@code 400 invalid} naming the offending parameter
   * @throws IllegalArgumentException if {@code operation} is a search operation
   */
  public void checkFormatOnly(Operation operation, HttpServletRequest request) {
    Objects.requireNonNull(operation, "operation");
    if (operation.isSearch()) {
      throw new IllegalArgumentException("Search operations are parsed with parse: " + operation);
    }
    parse(operation, request, null);
  }

  /**
   * Returns the single value of every query parameter, keyed by name in the iteration order of the
   * parameter map. A parameter without a value maps to the empty string.
   *
   * @throws FhirApiException for the first name the operation does not accept or that is repeated
   */
  private static Map<String, String> readQuery(Operation operation, HttpServletRequest request) {
    Map<String, String> query = new LinkedHashMap<>();
    Map<String, String[]> parameterMap = request.getParameterMap();
    if (parameterMap == null) {
      return query;
    }
    for (Map.Entry<String, String[]> parameter : parameterMap.entrySet()) {
      String name = String.valueOf(parameter.getKey());
      if (!operation.allowed().contains(name)) {
        throw FhirApiException.invalidParameter(
            name,
            operation.isSearch()
                ? "is not a supported search parameter"
                : "is not a supported parameter");
      }
      String[] values = parameter.getValue();
      if (values != null && values.length > 1) {
        throw FhirApiException.invalidParameter(name, "must not be repeated");
      }
      query.put(name, values == null || values.length == 0 || values[0] == null ? "" : values[0]);
    }
    return query;
  }

  /** Validates one present parameter value and stores what it contributes to the query. */
  private void parseValue(
      Operation operation,
      String name,
      String value,
      Map<String, String> query,
      @CheckForNull ResolvedMapping patientMapping,
      ParseState state) {
    if (value.isBlank()) {
      throw FhirApiException.invalidParameter(name, "must not be empty");
    }
    List<String> elements = elements(name, value);
    switch (name) {
      case FORMAT -> {
        if (!FORMATS.contains(value)) {
          throw FhirApiException.invalidParameter(
              FORMAT, "must be json, application/json or application/fhir+json");
        }
      }
      case ID -> parseId(operation, elements, state);
      case PATIENT -> state.patient = patientReference(PATIENT, value);
      case SUBJECT -> {
        if (query.containsKey(PATIENT)) {
          throw FhirApiException.invalidParameter(
              SUBJECT, "patient and subject cannot be combined");
        }
        state.patient = patientReference(SUBJECT, value);
      }
      case IDENTIFIER -> state.identifier = identifier(value, requireMapping(patientMapping));
      case FAMILY -> {
        requireConfigured(PatientParameter.FAMILY, requireMapping(patientMapping));
        state.family = value;
      }
      case GIVEN -> {
        requireConfigured(PatientParameter.GIVEN, requireMapping(patientMapping));
        state.given = value;
      }
      case BIRTHDATE -> state.birthdate = birthdate(value, requireMapping(patientMapping));
      case GENDER -> state.genders = genders(elements, requireMapping(patientMapping));
      case CODE -> state.codes = codes(elements);
      case COUNT -> {
        state.count = positiveInteger(COUNT, value);
        if (operation == Operation.PATIENT_SEARCH) {
          checkPatientPageSize(state.count, true);
        }
      }
      case PAGE -> {
        state.page = positiveInteger(PAGE, value);
        if ((long) (state.page - 1) * state.count > Integer.MAX_VALUE) {
          throw FhirApiException.invalidParameter(PAGE, "is too large for the page size");
        }
      }
      default -> throw new IllegalStateException("No value rule for parameter " + name);
    }
  }

  /**
   * Splits a comma-separated OR value into its elements, rejecting blank elements; returns every
   * other value as its single element, rejecting a comma in it.
   */
  private static List<String> elements(String name, String value) {
    if (OR_PARAMETERS.contains(name)) {
      List<String> elements = List.of(value.split(OR_SEPARATOR, -1));
      if (elements.stream().anyMatch(String::isBlank)) {
        throw FhirApiException.invalidParameter(name, "must not contain empty values");
      }
      return elements;
    }
    if (value.contains(OR_SEPARATOR)) {
      throw FhirApiException.invalidParameter(name, "does not support multiple values");
    }
    return List.of(value);
  }

  /** Parses {@code _id}: UIDs for Patient searches, logical ids of the type for event searches. */
  private static void parseId(Operation operation, List<String> elements, ParseState state) {
    FhirResourceType type = operation.resourceType();
    if (type == null || !type.isEventDerived()) {
      for (String element : elements) {
        if (!UID.isValid(element)) {
          throw FhirApiException.invalidParameter(ID, "must contain only UIDs");
        }
      }
      state.trackedEntityIds = elements;
      return;
    }
    List<FhirLogicalId> logicalIds = new ArrayList<>(elements.size());
    for (String element : elements) {
      logicalIds.add(
          FhirLogicalId.parse(type, element)
              .orElseThrow(
                  () ->
                      FhirApiException.invalidParameter(
                          ID, "must contain only " + type.fhirType() + " logical ids")));
    }
    state.logicalIds = logicalIds;
  }

  /** Returns the UID of a {@code {uid}} or {@code Patient/{uid}} reference. */
  private static String patientReference(String name, String value) {
    String uid =
        value.startsWith(PATIENT_REFERENCE_PREFIX)
            ? value.substring(PATIENT_REFERENCE_PREFIX.length())
            : value;
    if (!UID.isValid(uid)) {
      throw FhirApiException.invalidParameter(
          name, "must be a UID or a " + PATIENT_REFERENCE_PREFIX + "{UID} reference");
    }
    return uid;
  }

  /** Selects the identifier entry of an {@code identifier} value. */
  private static IdentifierCriterion identifier(String value, ResolvedMapping mapping) {
    List<FhirFieldMapping> entries = mapping.entries(FhirTargetField.PATIENT_IDENTIFIER);
    if (entries.isEmpty()) {
      throw FhirApiException.invalidParameter(IDENTIFIER, "is not configured");
    }
    Token token = token(value);
    if (token.value().isBlank()) {
      throw FhirApiException.invalidParameter(IDENTIFIER, "must have a value");
    }
    if (token.system() != null) {
      List<FhirFieldMapping> matching =
          entries.stream().filter(entry -> token.system().equals(entry.getSystem())).toList();
      if (matching.size() != 1) {
        throw FhirApiException.invalidParameter(IDENTIFIER, "has an unknown identifier system");
      }
      return new IdentifierCriterion(matching.get(0), token.value());
    }
    if (entries.size() != 1) {
      throw FhirApiException.invalidParameter(
          IDENTIFIER, "must name a system when several identifier systems are configured");
    }
    return new IdentifierCriterion(entries.get(0), token.value());
  }

  /** Parses a {@code birthdate} value into its operator and date. */
  private static DateCriterion birthdate(String value, ResolvedMapping mapping) {
    requireConfigured(PatientParameter.BIRTHDATE, mapping);
    Matcher matcher = BIRTHDATE_VALUE.matcher(value);
    if (!matcher.matches() || !isIsoDate(matcher.group(2))) {
      throw FhirApiException.invalidParameter(
          BIRTHDATE,
          "must be a date formatted yyyy-MM-dd with an optional prefix eq, ge, le, gt or lt");
    }
    String prefix = matcher.group(1);
    QueryOperator operator =
        prefix == null ? QueryOperator.EQ : QueryOperator.valueOf(prefix.toUpperCase(Locale.ROOT));
    return new DateCriterion(operator, matcher.group(2));
  }

  /** Validates the {@code gender} codes. */
  private static List<String> genders(List<String> elements, ResolvedMapping mapping) {
    requireConfigured(PatientParameter.GENDER, mapping);
    for (String element : elements) {
      if (!GENDER_CODES.contains(element)) {
        throw FhirApiException.invalidParameter(
            GENDER, "must contain only the codes male, female, other and unknown");
      }
    }
    return elements;
  }

  /** Parses the {@code code} tokens. */
  private static List<Token> codes(List<String> elements) {
    List<Token> tokens = new ArrayList<>(elements.size());
    for (String element : elements) {
      Token token = token(element);
      if (token.value().isBlank()) {
        throw FhirApiException.invalidParameter(CODE, "must contain only [system|]code tokens");
      }
      tokens.add(token);
    }
    return tokens;
  }

  /** Splits a value at its first {@code |}. */
  private static Token token(String value) {
    int separator = value.indexOf(TOKEN_SEPARATOR);
    return separator < 0
        ? new Token(null, value)
        : new Token(value.substring(0, separator), value.substring(separator + 1));
  }

  /** Parses a positive integer within the {@code int} range. */
  private static int positiveInteger(String name, String value) {
    if (POSITIVE_INTEGER.matcher(value).matches()) {
      try {
        return Integer.parseInt(value);
      } catch (NumberFormatException e) {
        throw FhirApiException.invalidParameter(name, "must not exceed " + Integer.MAX_VALUE);
      }
    }
    throw FhirApiException.invalidParameter(name, "must be a positive integer");
  }

  /**
   * Rejects a Patient search page size above a positive {@code KeyTrackedEntityMaxLimit} system
   * setting.
   *
   * @param count the page size
   * @param explicit whether the page size was given as {@code _count}
   */
  private void checkPatientPageSize(int count, boolean explicit) {
    SystemSettings settings = settingsProvider.getCurrentSettings();
    int limit = settings == null ? 0 : settings.getTrackedEntityMaxLimit();
    if (limit > 0 && count > limit) {
      throw FhirApiException.invalidParameter(
          COUNT,
          explicit ? "must not exceed " + limit : "must be given and must not exceed " + limit);
    }
  }

  /** Returns whether a value is a valid calendar date formatted {@code yyyy-MM-dd}. */
  private static boolean isIsoDate(String value) {
    if (!ISO_DATE.matcher(value).matches()) {
      return false;
    }
    try {
      LocalDate.parse(value);
      return true;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static ResolvedMapping requireMapping(@CheckForNull ResolvedMapping patientMapping) {
    return Objects.requireNonNull(patientMapping, "patientMapping");
  }

  /** Rejects a Patient search parameter whose target the mapping does not configure. */
  private static void requireConfigured(PatientParameter parameter, ResolvedMapping mapping) {
    if (mapping.entries(parameter.target()).isEmpty()) {
      throw FhirApiException.invalidParameter(parameter.parameter(), "is not configured");
    }
  }

  /**
   * Validates one Tracker attribute filter derived from a Patient search, in this order:
   *
   * <ol>
   *   <li>The operator must not be among the attribute's blocked search operators.
   *   <li>For a binary operator, a positive minimum number of characters to search requires {@code
   *       trackerValue} to be at least that long.
   *   <li>For a binary operator, each lowercased value, split on {@link QueryFilter#OPTION_SEP} for
   *       {@link QueryOperator#IN}, must parse for the attribute's value type: an integer for the
   *       integer types, a decimal for {@code NUMBER}, {@code PERCENTAGE} and {@code
   *       UNIT_INTERVAL}, a {@code yyyy-MM-dd} date for {@code DATE} and {@code AGE}, and {@code
   *       true} or {@code false} for {@code BOOLEAN} and {@code TRUE_ONLY}. Other value types, and
   *       attributes without a known value type, accept any value.
   * </ol>
   *
   * <p>The diagnostics name the FHIR parameter only; they contain no attribute UID.
   *
   * @param parameter the FHIR parameter the filter is derived from
   * @param teaUid the UID of the filtered tracked entity attribute
   * @param operator the Tracker filter operator
   * @param trackerValue the unescaped filter value exactly as Tracker receives it; ignored for
   *     unary operators
   * @param mapping the resolved Patient mapping holding the attribute's search constraints
   * @throws FhirApiException {@code 400 invalid} naming {@code parameter}
   * @throws NullPointerException if an argument other than {@code trackerValue} is {@code null}, or
   *     if {@code trackerValue} is {@code null} for a binary operator
   */
  public void checkAttributeFilter(
      String parameter,
      String teaUid,
      QueryOperator operator,
      @CheckForNull String trackerValue,
      ResolvedMapping mapping) {
    Objects.requireNonNull(parameter, "parameter");
    Objects.requireNonNull(teaUid, "teaUid");
    Objects.requireNonNull(operator, "operator");
    Objects.requireNonNull(mapping, "mapping");

    if (blockedOperators(mapping, teaUid).contains(operator)) {
      throw FhirApiException.invalidParameter(
          parameter,
          "search operator "
              + operator.name().toLowerCase(Locale.ROOT)
              + " is not allowed for this parameter");
    }
    if (operator.isUnary()) {
      return;
    }
    Objects.requireNonNull(trackerValue, "trackerValue");

    Integer minCharacters = mapping.minCharactersToSearch().get(teaUid);
    if (minCharacters != null && minCharacters > 0 && trackerValue.length() < minCharacters) {
      throw FhirApiException.invalidParameter(
          parameter, "at least " + minCharacters + " characters are required");
    }

    ValueType valueType = mapping.valueTypes().get(teaUid);
    if (valueType == null) {
      return;
    }
    String value = trackerValue.toLowerCase(Locale.ROOT);
    List<String> values =
        operator.isIn() ? List.of(value.split(QueryFilter.OPTION_SEP)) : List.of(value);
    for (String element : values) {
      if (!matchesValueType(valueType, element)) {
        throw FhirApiException.invalidParameter(
            parameter, "value does not match the attribute value type");
      }
    }
  }

  /**
   * Returns the attribute-backed Patient search parameters the mapping supports: those whose {@link
   * PatientParameter#target()} has at least one entry with a source attribute that does not block
   * the parameter's {@link PatientParameter#defaultOperator()}.
   *
   * @param patientMapping the resolved Patient mapping
   * @return an unmodifiable list of parameter names in {@link PatientParameter} order
   */
  public List<String> configuredAttributeParameters(ResolvedMapping patientMapping) {
    Objects.requireNonNull(patientMapping, "patientMapping");
    List<String> parameters = new ArrayList<>();
    for (PatientParameter parameter : PatientParameter.values()) {
      boolean searchable =
          patientMapping.entries(parameter.target()).stream()
              .map(FhirFieldMapping::getSource)
              .filter(Objects::nonNull)
              .anyMatch(
                  source ->
                      !blockedOperators(patientMapping, source)
                          .contains(parameter.defaultOperator()));
      if (searchable) {
        parameters.add(parameter.parameter());
      }
    }
    return List.copyOf(parameters);
  }

  /**
   * Returns the search parameters a resource type supports with the given mappings, excluding the
   * control parameters {@code _count}, {@code _page} and {@code _format}:
   *
   * <ul>
   *   <li>{@code Patient}: {@code _id}, then every parameter of {@link
   *       #configuredAttributeParameters} of any of the mappings, in {@link PatientParameter}
   *       order.
   *   <li>{@code Encounter}: {@code _id}, {@code patient}, {@code subject}.
   *   <li>{@code Immunization}: {@code _id}, {@code patient}.
   *   <li>{@code Observation}: {@code _id}, {@code patient}, {@code subject}, {@code code}.
   * </ul>
   *
   * @param type the resource type
   * @param mappings the usable mappings of the type; only read for {@code Patient}, {@code null} is
   *     treated as empty
   * @return an unmodifiable list of parameter names without duplicates
   * @throws NullPointerException if {@code type} is {@code null}
   */
  public List<String> supportedParameters(
      FhirResourceType type, @CheckForNull List<ResolvedMapping> mappings) {
    Objects.requireNonNull(type, "type");
    return switch (type) {
      case PATIENT -> patientParameters(mappings);
      case ENCOUNTER -> List.of(ID, PATIENT, SUBJECT);
      case IMMUNIZATION -> List.of(ID, PATIENT);
      case OBSERVATION -> List.of(ID, PATIENT, SUBJECT, CODE);
    };
  }

  /**
   * Returns the FHIR search parameter type of a search parameter.
   *
   * @param parameter a search parameter name: {@code _id}, {@code identifier}, {@code gender} and
   *     {@code code} are tokens; {@code family} and {@code given} strings; {@code birthdate} a
   *     date; {@code patient} and {@code subject} references
   * @return the search parameter type
   * @throws IllegalArgumentException if {@code parameter} is not one of these names
   */
  public static Enumerations.SearchParamType typeOf(String parameter) {
    if (parameter == null) {
      throw new IllegalArgumentException("A search parameter name is required");
    }
    return switch (parameter) {
      case ID, IDENTIFIER, GENDER, CODE -> Enumerations.SearchParamType.TOKEN;
      case FAMILY, GIVEN -> Enumerations.SearchParamType.STRING;
      case BIRTHDATE -> Enumerations.SearchParamType.DATE;
      case PATIENT, SUBJECT -> Enumerations.SearchParamType.REFERENCE;
      default -> throw new IllegalArgumentException("Unknown search parameter: " + parameter);
    };
  }

  /** Returns {@code _id} followed by the attribute parameters any Patient mapping supports. */
  private List<String> patientParameters(@CheckForNull List<ResolvedMapping> mappings) {
    Set<String> configured = new LinkedHashSet<>();
    if (mappings != null) {
      for (ResolvedMapping mapping : mappings) {
        if (mapping != null) {
          configured.addAll(configuredAttributeParameters(mapping));
        }
      }
    }
    List<String> parameters = new ArrayList<>();
    parameters.add(ID);
    for (PatientParameter parameter : PatientParameter.values()) {
      if (configured.contains(parameter.parameter())) {
        parameters.add(parameter.parameter());
      }
    }
    return List.copyOf(parameters);
  }

  private static Set<QueryOperator> blockedOperators(ResolvedMapping mapping, String teaUid) {
    Set<QueryOperator> blocked = mapping.blockedSearchOperators().get(teaUid);
    return blocked == null ? Set.of() : blocked;
  }

  /** Returns whether a lowercased filter value parses for a value type. */
  private static boolean matchesValueType(ValueType valueType, String value) {
    if (valueType.isInteger()) {
      try {
        Integer.parseInt(value);
        return true;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if (valueType.isDecimal()) {
      try {
        new BigDecimal(value);
        return true;
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if (valueType == ValueType.DATE || valueType == ValueType.AGE) {
      return isIsoDate(value);
    }
    if (valueType.isBoolean()) {
      return TRUE.equals(value) || FALSE.equals(value);
    }
    return true;
  }

  /** The values collected while one query is parsed. */
  private static final class ParseState {
    private List<String> trackedEntityIds = List.of();
    private List<FhirLogicalId> logicalIds = List.of();
    @CheckForNull private String patient;
    @CheckForNull private IdentifierCriterion identifier;
    @CheckForNull private String family;
    @CheckForNull private String given;
    @CheckForNull private DateCriterion birthdate;
    private List<String> genders = List.of();
    private List<Token> codes = List.of();
    private int count = DEFAULT_COUNT;
    private int page = DEFAULT_PAGE;
  }
}
