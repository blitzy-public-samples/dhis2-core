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
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.*;
import javax.annotation.CheckForNull;
import lombok.*;
import lombok.experimental.Accessors;
import org.hisp.dhis.common.*;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapper.FhirLogicalId;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.setting.*;
import org.hl7.fhir.r4.model.Enumerations;
import org.springframework.security.web.firewall.RequestRejectedException;
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
  public static final Set<String> FORMATS =
      Set.of("json", "application/json", "application/fhir+json");
  public static final Set<String> GENDER_CODES = Set.of("male", "female", "other", "unknown");
  private static final String UNREADABLE_QUERY = FhirSearchParameters.class.getName() + ".query";
  private static final int MAX_PATIENT_COUNT = Integer.MAX_VALUE - 1;
  private static final Set<String> OR_PARAMETERS = Set.of(ID, GENDER, CODE);
  private static final String FHIR_JSON_FORMAT = "application/fhir+json";
  private static final String FHIR_JSON_FORM_DECODED = "application/fhir json";
  private static final char OR_SEPARATOR = ',';
  private static final char TOKEN_SEPARATOR = '|';
  private static final char ESCAPE = '\\';
  private static final String ESCAPED = ",|$\\";
  private static final String PATIENT_REFERENCE_PREFIX = FhirResourceType.PATIENT.fhirType() + "/";
  private static final Pattern POSITIVE_INTEGER = Pattern.compile("^[1-9][0-9]*$");
  private static final Pattern BIRTHDATE_VALUE =
      Pattern.compile("^(eq|ge|le|gt|lt)?([0-9]{4}-[0-9]{2}-[0-9]{2})$");
  private static final Pattern ISO_DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
  private static final HexFormat UPPER_HEX = HexFormat.of().withUpperCase();
  private final SystemSettingsProvider settingsProvider;

  /** A FHIR operation and the query parameters it accepts, unmodifiable, in validation order. */
  @Getter
  @Accessors(fluent = true)
  @RequiredArgsConstructor
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
  @Getter
  @Accessors(fluent = true)
  @RequiredArgsConstructor
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
  }

  /** A FHIR token's unescaped parts; {@code system} is {@code null} when it has no {@code |}. */
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

  /** A query as {@code parse} returns it: validated, with absent values empty, null or default. */
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
    Map<String, String[]> parameterMap;
    try {
      parameterMap = request.getParameterMap();
    } catch (IllegalStateException | RequestRejectedException e) {
      throw rejectedQuery(operation, request.getQueryString(), e);
    }
    if (parameterMap == null) {
      return query;
    }
    for (Map.Entry<String, String[]> parameter : parameterMap.entrySet()) {
      String name = String.valueOf(parameter.getKey());
      if (!operation.allowed().contains(name)) {
        throw unsupported(operation, name);
      }
      String[] values = parameter.getValue();
      if (values != null && values.length > 1) {
        throw FhirApiException.invalidParameter(name, "must not be repeated");
      }
      query.put(name, values == null || values.length == 0 || values[0] == null ? "" : values[0]);
    }
    return query;
  }

  private static RuntimeException rejectedQuery(
      @CheckForNull Operation operation, @CheckForNull String query, RuntimeException failure) {
    Set<String> names = new HashSet<>();
    for (String pair : Objects.toString(query, "").split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int separator = pair.indexOf('=');
      String rawName = separator < 0 ? pair : pair.substring(0, separator);
      String name = decodeQueryComponent(rawName);
      if (name == null) {
        return FhirApiException.invalidParameter(
            display(rawName), "name is not valid percent-encoded UTF-8");
      }
      if (operation != null && !operation.allowed().contains(name)) {
        return unsupported(operation, name);
      }
      if (separator >= 0 && decodeQueryComponent(pair.substring(separator + 1)) == null) {
        return FhirApiException.invalidParameter(
            display(name), "value is not valid percent-encoded UTF-8");
      }
      if (!names.add(name)) {
        return FhirApiException.invalidParameter(display(name), "must not be repeated");
      }
    }
    return failure;
  }

  /** Returns, and keeps per request, the 400 naming an undecodable query parameter, or null. */
  @CheckForNull
  public static FhirApiException unreadableQuery(HttpServletRequest request) {
    try {
      request.getParameterMap();
    } catch (IllegalStateException | RequestRejectedException e) {
      RuntimeException rejection = rejectedQuery(null, request.getQueryString(), e);
      request.setAttribute(UNREADABLE_QUERY, rejection instanceof FhirApiException x ? x : null);
    }
    return (FhirApiException) request.getAttribute(UNREADABLE_QUERY);
  }

  private static FhirApiException unsupported(Operation operation, String name) {
    return FhirApiException.invalidParameter(
        display(name),
        operation.isSearch()
            ? "is not a supported search parameter"
            : "is not a supported parameter");
  }

  @CheckForNull
  private static String decodeQueryComponent(String raw) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(raw.length());
    for (int i = 0; i < raw.length(); ) {
      int codePoint = raw.codePointAt(i);
      if (codePoint == '%') {
        if (i + 2 >= raw.length()
            || !HexFormat.isHexDigit(raw.charAt(i + 1))
            || !HexFormat.isHexDigit(raw.charAt(i + 2))) {
          return null;
        }
        bytes.write(HexFormat.fromHexDigits(raw, i + 1, i + 3));
        i += 3;
      } else {
        String text = codePoint == '+' ? " " : Character.toString(codePoint);
        bytes.writeBytes(text.getBytes(StandardCharsets.UTF_8));
        i += Character.charCount(codePoint);
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .decode(ByteBuffer.wrap(bytes.toByteArray()))
          .toString();
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  private static String display(String name) {
    StringBuilder text = new StringBuilder(name.length());
    for (char c : name.toCharArray()) {
      if (Character.isISOControl(c)) {
        for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
          text.append('%').append(UPPER_HEX.toHexDigits(b));
        }
      } else {
        text.append(c);
      }
    }
    return text.toString();
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
    if (value.indexOf('\u0000') >= 0) {
      throw FhirApiException.invalidParameter(name, "must not contain NUL characters");
    }
    List<String> segments = elements(name, value);
    List<String> elements = segments.stream().map(FhirSearchParameters::unescape).toList();
    switch (name) {
      case FORMAT -> {
        if (!FORMATS.contains(FHIR_JSON_FORM_DECODED.equals(value) ? FHIR_JSON_FORMAT : value)) {
          throw FhirApiException.invalidParameter(
              FORMAT, "must be json, application/json or application/fhir+json");
        }
      }
      case ID -> parseId(operation, elements, state);
      case PATIENT -> state.patient = patientReference(PATIENT, unescape(value));
      case SUBJECT -> {
        if (query.containsKey(PATIENT)) {
          throw FhirApiException.invalidParameter(
              SUBJECT, "patient and subject cannot be combined");
        }
        state.patient = patientReference(SUBJECT, unescape(value));
      }
      case IDENTIFIER -> state.identifier = identifier(value, requireMapping(patientMapping));
      case FAMILY -> {
        requireConfigured(PatientParameter.FAMILY, requireMapping(patientMapping));
        state.family = unescape(value);
      }
      case GIVEN -> {
        requireConfigured(PatientParameter.GIVEN, requireMapping(patientMapping));
        state.given = unescape(value);
      }
      case BIRTHDATE ->
          state.birthdate = birthdate(unescape(value), requireMapping(patientMapping));
      case GENDER -> state.genders = genders(elements, requireMapping(patientMapping));
      case CODE -> state.codes = codes(segments);
      case COUNT -> {
        state.count = positiveInteger(COUNT, value);
        if (operation == Operation.PATIENT_SEARCH) {
          checkPatientPageSize(state.count, true);
          state.count = Math.min(state.count, MAX_PATIENT_COUNT);
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
    List<String> segments = splitUnescaped(value, OR_SEPARATOR);
    if (OR_PARAMETERS.contains(name)) {
      if (segments.stream().anyMatch(String::isBlank)) {
        throw FhirApiException.invalidParameter(name, "must not contain empty values");
      }
    } else if (segments.size() > 1) {
      throw FhirApiException.invalidParameter(name, "does not support multiple values");
    }
    return segments;
  }

  private static List<String> splitUnescaped(String value, char separator) {
    List<String> parts = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < value.length(); i++) {
      if (isEscape(value, i)) {
        i++;
      } else if (value.charAt(i) == separator) {
        parts.add(value.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(value.substring(start));
    return parts;
  }

  private static String unescape(String value) {
    StringBuilder text = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      if (isEscape(value, i)) {
        i++;
      }
      text.append(value.charAt(i));
    }
    return text.toString();
  }

  private static boolean isEscape(String value, int index) {
    return value.charAt(index) == ESCAPE
        && index + 1 < value.length()
        && ESCAPED.indexOf(value.charAt(index + 1)) >= 0;
  }

  private static void parseId(Operation operation, List<String> elements, ParseState state) {
    FhirResourceType type = operation.resourceType();
    if (type == null || !type.isEventDerived()) {
      if (!elements.stream().allMatch(UID::isValid)) {
        throw FhirApiException.invalidParameter(ID, "must contain only UIDs");
      }
      state.trackedEntityIds = elements;
      return;
    }
    String invalid = "must contain only " + type.fhirType() + " logical ids";
    List<FhirLogicalId> logicalIds = new ArrayList<>(elements.size());
    for (String element : elements) {
      logicalIds.add(
          FhirLogicalId.parse(type, element)
              .orElseThrow(() -> FhirApiException.invalidParameter(ID, invalid)));
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
    if (!GENDER_CODES.containsAll(elements)) {
      throw FhirApiException.invalidParameter(
          GENDER, "must contain only the codes male, female, other and unknown");
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
    List<String> parts = splitUnescaped(value, TOKEN_SEPARATOR);
    if (parts.size() > 2) {
      throw FhirApiException.invalidParameter(name, "must contain at most one |");
    }
    return parts.size() == 1
        ? new Token(null, unescape(value))
        : new Token(unescape(parts.get(0)), unescape(parts.get(1)));
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
    if (limit > 0 && count > limit) {
      throw FhirApiException.invalidParameter(
          COUNT,
          explicit ? "must not exceed " + limit : "must be given and must not exceed " + limit);
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
    if (!values.stream().allMatch(element -> matchesValueType(valueType, element))) {
      throw FhirApiException.invalidParameter(
          parameter, "value does not match the attribute value type");
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
      return "true".equals(value) || "false".equals(value);
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
