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
package org.hisp.dhis.fhir.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.hisp.dhis.common.ValueType;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.TimeType;
import org.hl7.fhir.r4.model.Type;
import org.springframework.stereotype.Component;

/**
 * Converts DHIS2 Tracker attribute and data values, which the view DTOs carry as strings, to HAPI
 * FHIR R4 datatypes. The value type of a value is always supplied by the caller.
 *
 * <p>{@link #toFhir(ValueType, String, String)} converts by value type:
 *
 * <ul>
 *   <li>{@code NUMBER}, {@code INTEGER}, {@code INTEGER_POSITIVE}, {@code INTEGER_NEGATIVE}, {@code
 *       INTEGER_ZERO_OR_POSITIVE}, {@code PERCENTAGE}, {@code UNIT_INTERVAL}: {@link Quantity}
 *       whose value is the decimal number and whose unit is the given unit, when that unit is not
 *       blank.
 *   <li>{@code BOOLEAN}, {@code TRUE_ONLY}: {@link BooleanType} for exactly {@code true} or {@code
 *       false}.
 *   <li>{@code DATE}, {@code AGE}: {@link DateTimeType} at day precision ({@code yyyy-MM-dd}),
 *       taken from an ISO date or from the date part of an ISO date-time.
 *   <li>{@code DATETIME}: {@link DateTimeType}; an ISO date gives day precision, an ISO date-time
 *       with an offset or {@code Z} gives that instant, and an ISO date-time without an offset is
 *       read in the system default time zone. Every result with a time of day carries a time zone.
 *   <li>{@code TIME}: {@link TimeType} rendered as {@code HH:mm:ss}; {@code HH:mm} becomes {@code
 *       HH:mm:00}.
 *   <li>Every other value type: {@link StringType} holding the value verbatim.
 * </ul>
 *
 * <p>Surrounding whitespace is ignored for every typed conversion. A value is unparseable when it
 * does not have the syntax its value type requires, or when a date or date-time has a year outside
 * {@code 0001} to {@code 9999}. An unparseable, {@code null} or blank value, or a {@code null}
 * value type, yields {@link Optional#empty()}, and the caller omits the FHIR element. No value is
 * ever coerced to another datatype or to a default.
 *
 * <p>Instances are stateless and thread-safe.
 */
@Component
public class FhirValueConverter {
  private static final int ISO_DATE_LENGTH = 10;

  private static final char DATE_TIME_SEPARATOR = 'T';

  private static final int MIN_YEAR = 1;

  private static final int MAX_YEAR = 9999;

  private static final String TRUE = "true";

  private static final String FALSE = "false";

  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

  /**
   * Converts a Tracker value to the FHIR datatype of its value type.
   *
   * @param type the value type of the attribute or data element the value belongs to
   * @param value the value as stored in Tracker
   * @param unit the unit of a numeric value; ignored for other value types and when blank
   * @return the FHIR datatype, or empty when the type is {@code null} or the value is {@code null},
   *     blank or unparseable
   */
  @Nonnull
  public Optional<Type> toFhir(
      @CheckForNull ValueType type, @CheckForNull String value, @CheckForNull String unit) {
    if (type == null || value == null || value.isBlank()) {
      return Optional.empty();
    }

    return switch (type) {
      case NUMBER,
          INTEGER,
          INTEGER_POSITIVE,
          INTEGER_NEGATIVE,
          INTEGER_ZERO_OR_POSITIVE,
          PERCENTAGE,
          UNIT_INTERVAL ->
          quantityValue(value, unit);
      case BOOLEAN, TRUE_ONLY -> booleanValue(value);
      case DATE, AGE -> datePart(value).map(date -> new DateTimeType(date.toString()));
      case DATETIME -> dateTimeValue(value).map(Type.class::cast);
      case TIME -> timeValue(value);
      default -> Optional.of(new StringType(value));
    };
  }

  /**
   * Converts a {@code DATE} or {@code AGE} value to a FHIR {@code date}, as used by {@code
   * Patient.birthDate}. The value is an ISO date ({@code yyyy-MM-dd}) or an ISO date-time whose
   * first ten characters are an ISO date followed by {@code T}; only the date part is kept.
   *
   * @param type the value type of the attribute the value belongs to
   * @param value the value as stored in Tracker
   * @return the date, or empty when the type is not {@code DATE} or {@code AGE}, or the value is
   *     {@code null}, blank or unparseable
   */
  @Nonnull
  public Optional<DateType> toDate(@CheckForNull ValueType type, @CheckForNull String value) {
    if ((type != ValueType.DATE && type != ValueType.AGE) || value == null || value.isBlank()) {
      return Optional.empty();
    }

    return datePart(value).map(date -> new DateType(date.toString()));
  }

  /**
   * Converts a Tracker timestamp to a FHIR {@code instant}, as used by {@code meta.lastUpdated}.
   *
   * @param instant the timestamp; must not be {@code null}
   * @return the instant at millisecond precision
   */
  @Nonnull
  public InstantType instant(@Nonnull Instant instant) {
    return new InstantType(Date.from(Objects.requireNonNull(instant, "instant")));
  }

  /**
   * Converts a Tracker timestamp to a FHIR {@code dateTime} carrying a time zone, as used by {@code
   * Encounter.period.start}, {@code Immunization.occurrenceDateTime} and {@code
   * Observation.effectiveDateTime}.
   *
   * @param instant the timestamp; must not be {@code null}
   * @return the date-time at second precision in the system default time zone
   */
  @Nonnull
  public DateTimeType dateTime(@Nonnull Instant instant) {
    return new DateTimeType(Date.from(Objects.requireNonNull(instant, "instant")));
  }

  /**
   * Reads a decimal number as a {@link Quantity}. The unit is set only when it is not blank.
   *
   * @return the quantity, or empty when the value is not a decimal number
   */
  private static Optional<Type> quantityValue(String value, @CheckForNull String unit) {
    BigDecimal number;
    try {
      number = new BigDecimal(value.trim());
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }

    Quantity quantity = new Quantity().setValue(number);
    if (unit != null && !unit.isBlank()) {
      quantity.setUnit(unit);
    }
    return Optional.of(quantity);
  }

  /**
   * Reads exactly {@code true} or {@code false} as a {@link BooleanType}.
   *
   * @return the boolean, or empty for any other value
   */
  private static Optional<Type> booleanValue(String value) {
    String trimmed = value.trim();
    if (TRUE.equals(trimmed)) {
      return Optional.of(new BooleanType(true));
    }
    if (FALSE.equals(trimmed)) {
      return Optional.of(new BooleanType(false));
    }
    return Optional.empty();
  }

  /**
   * Reads an ISO local time ({@code HH:mm}, {@code HH:mm:ss} or with a fraction of a second) as a
   * {@link TimeType} rendered as {@code HH:mm:ss}.
   *
   * @return the time, or empty when the value is not an ISO local time
   */
  private static Optional<Type> timeValue(String value) {
    try {
      return Optional.of(new TimeType(LocalTime.parse(value.trim()).format(TIME_FORMAT)));
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
  }

  /**
   * Reads the date part of a value: the whole value when it is ten characters long, or its first
   * ten characters when the eleventh is {@code T}. The date part must be an ISO local date with a
   * year in {@code 0001} to {@code 9999}.
   *
   * @return the date, or empty when the value has no valid date part
   */
  private static Optional<LocalDate> datePart(String value) {
    String trimmed = value.trim();
    String candidate;
    if (trimmed.length() == ISO_DATE_LENGTH) {
      candidate = trimmed;
    } else if (trimmed.length() > ISO_DATE_LENGTH
        && trimmed.charAt(ISO_DATE_LENGTH) == DATE_TIME_SEPARATOR) {
      candidate = trimmed.substring(0, ISO_DATE_LENGTH);
    } else {
      return Optional.empty();
    }

    try {
      LocalDate date = LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE);
      return isSupportedYear(date.getYear()) ? Optional.of(date) : Optional.empty();
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
  }

  /**
   * Reads a {@code DATETIME} value. A ten-character value is read as an ISO date at day precision.
   * Otherwise the value is read as an ISO date-time with an offset or {@code Z}, and failing that
   * as an ISO local date-time in the system default time zone.
   *
   * @return the date-time, or empty when the value matches none of these forms
   */
  private static Optional<DateTimeType> dateTimeValue(String value) {
    String trimmed = value.trim();
    if (trimmed.length() == ISO_DATE_LENGTH) {
      return datePart(trimmed).map(date -> new DateTimeType(date.toString()));
    }

    return offsetDateTime(trimmed)
        .or(() -> localDateTime(trimmed))
        .map(instant -> new DateTimeType(Date.from(instant)));
  }

  /**
   * Reads an ISO date-time with an offset or {@code Z}.
   *
   * @return the instant it denotes, or empty when the value is not such a date-time
   */
  private static Optional<Instant> offsetDateTime(String value) {
    try {
      OffsetDateTime dateTime = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      return isSupportedYear(dateTime.getYear())
          ? Optional.of(dateTime.toInstant())
          : Optional.empty();
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
  }

  /**
   * Reads an ISO local date-time in the system default time zone.
   *
   * @return the instant it denotes, or empty when the value is not such a date-time
   */
  private static Optional<Instant> localDateTime(String value) {
    try {
      LocalDateTime dateTime = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      return isSupportedYear(dateTime.getYear())
          ? Optional.of(dateTime.atZone(ZoneId.systemDefault()).toInstant())
          : Optional.empty();
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
  }

  private static boolean isSupportedYear(int year) {
    return year >= MIN_YEAR && year <= MAX_YEAR;
  }
}
