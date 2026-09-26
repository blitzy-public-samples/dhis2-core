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

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.*;
import java.util.*;
import javax.annotation.*;
import org.hisp.dhis.common.ValueType;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

/** Converts Tracker attribute values, data values and timestamps to FHIR R4 datatypes. */
@Component
public class FhirValueConverter {
  private static final int ISO_DATE_LENGTH = 10;
  private static final int ISO_TIME_SECONDS_LENGTH = 8;
  private static final char DATE_TIME_SEPARATOR = 'T';
  private static final int MIN_YEAR = 1;
  private static final int MAX_YEAR = 9999;
  private static final int NANOS_PER_MILLI = 1_000_000;
  private static final String TRUE = "true";
  private static final String FALSE = "false";
  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

  /**
   * Converts a trimmed value to the FHIR datatype of its value type; empty for a {@code null} type
   * or a {@code null}, blank or unparseable value.
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

  /** Converts a {@code DATE} or {@code AGE} value to a FHIR {@code date}; empty otherwise. */
  @Nonnull
  public Optional<DateType> toDate(@CheckForNull ValueType type, @CheckForNull String value) {
    if ((type != ValueType.DATE && type != ValueType.AGE) || value == null || value.isBlank()) {
      return Optional.empty();
    }
    return datePart(value).map(date -> new DateType(date.toString()));
  }

  /** Converts a timestamp to a FHIR {@code instant} at millisecond precision. */
  @Nonnull
  public InstantType instant(@Nonnull Instant instant) {
    return new InstantType(Date.from(Objects.requireNonNull(instant, "instant")));
  }

  /** Converts a timestamp to a FHIR {@code dateTime} in the system default time zone. */
  @Nonnull
  public DateTimeType dateTime(@Nonnull Instant instant) {
    return timestamp(Objects.requireNonNull(instant, "instant"));
  }

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

  private static Optional<Type> timeValue(String value) {
    String trimmed = value.trim();
    LocalTime time;
    try {
      time = LocalTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_TIME);
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
    String fraction =
        trimmed.length() > ISO_TIME_SECONDS_LENGTH + 1
            ? trimmed.substring(ISO_TIME_SECONDS_LENGTH)
            : "";
    return Optional.of(new TimeType(time.format(TIME_FORMAT) + fraction));
  }

  private static Optional<LocalDate> datePart(String value) {
    String trimmed = value.trim();
    if (trimmed.length() == ISO_DATE_LENGTH) {
      try {
        LocalDate date = LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE);
        return isSupportedYear(date.getYear()) ? Optional.of(date) : Optional.empty();
      } catch (DateTimeParseException ex) {
        return Optional.empty();
      }
    }
    if (trimmed.length() > ISO_DATE_LENGTH
        && trimmed.charAt(ISO_DATE_LENGTH) == DATE_TIME_SEPARATOR) {
      return localDateTime(trimmed)
          .map(LocalDateTime::toLocalDate)
          .or(() -> offsetDateTime(trimmed).map(OffsetDateTime::toLocalDate));
    }
    return Optional.empty();
  }

  private static Optional<DateTimeType> dateTimeValue(String value) {
    String trimmed = value.trim();
    if (trimmed.length() == ISO_DATE_LENGTH) {
      return datePart(trimmed).map(date -> new DateTimeType(date.toString()));
    }
    return offsetDateTime(trimmed)
        .map(OffsetDateTime::toInstant)
        .or(
            () ->
                localDateTime(trimmed)
                    .map(local -> local.atZone(ZoneId.systemDefault()).toInstant()))
        .map(FhirValueConverter::timestamp);
  }

  private static DateTimeType timestamp(Instant instant) {
    TemporalPrecisionEnum precision =
        instant.getNano() >= NANOS_PER_MILLI
            ? TemporalPrecisionEnum.MILLI
            : TemporalPrecisionEnum.SECOND;
    return new DateTimeType(Date.from(instant), precision, TimeZone.getDefault());
  }

  private static Optional<OffsetDateTime> offsetDateTime(String value) {
    try {
      OffsetDateTime dateTime = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
      return isSupportedYear(dateTime.getYear()) ? Optional.of(dateTime) : Optional.empty();
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
  }

  private static Optional<LocalDateTime> localDateTime(String value) {
    try {
      LocalDateTime dateTime = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      return isSupportedYear(dateTime.getYear()) ? Optional.of(dateTime) : Optional.empty();
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
  }

  private static boolean isSupportedYear(int year) {
    return year >= MIN_YEAR && year <= MAX_YEAR;
  }
}
