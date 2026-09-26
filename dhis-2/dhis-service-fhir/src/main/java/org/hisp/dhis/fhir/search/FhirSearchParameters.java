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
import java.util.*;
import java.util.regex.*;
import javax.annotation.CheckForNull;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.QueryFilter;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapper.FhirLogicalId;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.setting.*;
import org.hl7.fhir.r4.model.Enumerations;
import org.springframework.stereotype.Component;

/** Parses and validates FHIR query parameters; each rejection is a 400 naming one parameter. */
@Component
@RequiredArgsConstructor
public class FhirSearchParameters {
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
  public static final int DEFAULT_COUNT = 50;
  public static final int DEFAULT_PAGE = 1;
  public static final int MAX_OR_VALUES = 100;
  public static final int MAX_OR_LENGTH = 4096;
  public static final Set<String> FORMATS =
      Set.of("json", "application/json", "application/fhir+json");
  public static final Set<String> GENDER_CODES = Set.of("male", "female", "other", "unknown");
  private static final int MAX_PATIENT_COUNT = Integer.MAX_VALUE - 1;
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

  /** A FHIR operation and the query parameters it accepts, in validation order. */
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

    /** Returns the accepted parameter names, unmodifiable, in validation order. */
    public List<String> allowed() {
      return allowed;
    }

    /** Returns the searched resource type; {@code null} for read, everything and metadata. */
    @CheckForNull
    public FhirResourceType resourceType() {
      return resourceType;
    }

    public boolean isSearch() {
      return resourceType != null;
    }

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

  /** The attribute-backed Patient search parameters, in Tracker filter build order. */
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

    public String parameter() {
      return parameter;
    }

    public FhirTargetField target() {
      return target;
    }

    /** Returns the Tracker filter operator; {@link #BIRTHDATE} uses the operator of its prefix. */
    public QueryOperator defaultOperator() {
      return defaultOperator;
    }
  }

  /** A FHIR token split at its only {@code |}; {@code system} is {@code null} without one. */
  public record Token(@CheckForNull String system, String value) {
    public Token {
      Objects.requireNonNull(value, "value");
    }
  }

  /** The {@code identifier} criterion of a Patient search and the entry it selects. */
  public record IdentifierCriterion(FhirFieldMapping entry, String value) {
    public IdentifierCriterion {
      Objects.requireNonNull(entry, "entry");
      Objects.requireNonNull(value, "value");
    }
  }

  /** The {@code birthdate} criterion of a Patient search: a prefix operator and a date. */
  public record DateCriterion(QueryOperator operator, String date) {
    public DateCriterion {
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(date, "date");
    }
  }

  /** The validated query of one FHIR operation; absent parameters stay empty, null or default. */
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

  /** Parses and validates the query of an operation; a Patient search requires its mapping. */
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

  /** Validates the query of a non-search operation, which accepts only {@code _format}. */
  public void checkFormatOnly(Operation operation, HttpServletRequest request) {
    Objects.requireNonNull(operation, "operation");
    if (operation.isSearch()) {
      throw new IllegalArgumentException("Search operations are parsed with parse: " + operation);
    }
    parse(operation, request, null);
  }

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

  private static List<String> elements(String name, String value) {
    if (OR_PARAMETERS.contains(name)) {
      if (value.length() > MAX_OR_LENGTH) {
        throw FhirApiException.invalidParameter(
            name, "must not be longer than " + MAX_OR_LENGTH + " characters");
      }
      int count = 1;
      for (int separator = value.indexOf(OR_SEPARATOR);
          separator >= 0;
          separator = value.indexOf(OR_SEPARATOR, separator + 1)) {
        if (++count > MAX_OR_VALUES) {
          throw FhirApiException.invalidParameter(
              name, "must not contain more than " + MAX_OR_VALUES + " values");
        }
      }
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

  private static IdentifierCriterion identifier(String value, ResolvedMapping mapping) {
    List<FhirFieldMapping> entries = mapping.entries(FhirTargetField.PATIENT_IDENTIFIER);
    if (entries.isEmpty()) {
      throw FhirApiException.invalidParameter(IDENTIFIER, "is not configured");
    }
    Token token = token(IDENTIFIER, value);
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

  private static List<Token> codes(List<String> elements) {
    List<Token> tokens = new ArrayList<>(elements.size());
    for (String element : elements) {
      Token token = token(CODE, element);
      if (token.value().isBlank()) {
        throw FhirApiException.invalidParameter(CODE, "must contain only [system|]code tokens");
      }
      tokens.add(token);
    }
    return tokens;
  }

  private static Token token(String name, String value) {
    int separator = value.indexOf(TOKEN_SEPARATOR);
    if (separator < 0) {
      return new Token(null, value);
    }
    if (value.indexOf(TOKEN_SEPARATOR, separator + 1) >= 0) {
      throw FhirApiException.invalidParameter(name, "must contain at most one |");
    }
    return new Token(value.substring(0, separator), value.substring(separator + 1));
  }

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

  private void checkPatientPageSize(int count, boolean explicit) {
    SystemSettings settings = settingsProvider.getCurrentSettings();
    int limit = settings == null ? 0 : settings.getTrackedEntityMaxLimit();
    int ceiling = limit > 0 ? Math.min(limit, MAX_PATIENT_COUNT) : MAX_PATIENT_COUNT;
    if (count > ceiling) {
      throw FhirApiException.invalidParameter(
          COUNT,
          explicit ? "must not exceed " + ceiling : "must be given and must not exceed " + ceiling);
    }
  }

  private static boolean isIsoDate(String value) {
    if (!ISO_DATE.matcher(value).matches()) {
      return false;
    }
    try {
      return LocalDate.parse(value).getYear() >= 1;
    } catch (DateTimeParseException e) {
      return false;
    }
  }

  private static ResolvedMapping requireMapping(@CheckForNull ResolvedMapping patientMapping) {
    return Objects.requireNonNull(patientMapping, "patientMapping");
  }

  private static void requireConfigured(PatientParameter parameter, ResolvedMapping mapping) {
    if (mapping.entries(parameter.target()).isEmpty()) {
      throw FhirApiException.invalidParameter(parameter.parameter(), "is not configured");
    }
  }

  /** Validates a Tracker attribute filter against the attribute's search constraints. */
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
