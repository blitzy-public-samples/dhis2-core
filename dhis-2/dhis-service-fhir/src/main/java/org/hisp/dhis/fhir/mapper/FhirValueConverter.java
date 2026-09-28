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
import java.time.temporal.*;
import java.util.*;
import javax.annotation.*;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.webapi.controller.tracker.view.DataValue;
import org.hl7.fhir.r4.model.*;
import org.springframework.stereotype.Component;

/** Converts Tracker attribute values, data values and timestamps to FHIR R4 datatypes. */
@Component
public class FhirValueConverter {
  private static final int ISO_DATE_LENGTH = 10;
  private static final char DATE_TIME_SEPARATOR = 'T';
  private static final int MIN_YEAR = 1;
  private static final int MAX_YEAR = 9999;
  private static final String TRUE = "true";
  private static final String FALSE = "false";
  private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

  private static final DateTimeFormatter DATE_TIME_FORMAT =
      new DateTimeFormatterBuilder()
          .parseCaseInsensitive()
          .appendPattern("uuuu-MM-dd['T'HH[:mm[:ss[")
          .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
          .appendPattern("]]][")
          .parseLenient()
          .appendOffset("+HH", "Z")
          .toFormatter(Locale.ROOT)
          .withResolverStyle(ResolverStyle.STRICT);

  /** Converts a value to the FHIR datatype of its value type; empty when it cannot convert. */
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
      case BOOLEAN, TRUE_ONLY -> toBoolean(value).map(BooleanType::new);
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

  /** Reads {@code true} or {@code false} after trimming, in any letter case; empty otherwise. */
  @Nonnull
  public Optional<Boolean> toBoolean(@CheckForNull String value) {
    return switch (value == null ? "" : value.trim().toLowerCase(Locale.ROOT)) {
      case TRUE -> Optional.of(true);
      case FALSE -> Optional.of(false);
      default -> Optional.empty();
    };
  }

  /** Converts a timestamp to a FHIR {@code instant} at millisecond precision. */
  @Nonnull
  public InstantType instant(@Nonnull Instant instant) {
    return new InstantType(Date.from(Objects.requireNonNull(instant, "instant")));
  }

  /** Converts a timestamp to a FHIR {@code dateTime} in the system time zone with its fraction. */
  @Nonnull
  public DateTimeType dateTime(@Nonnull Instant instant) {
    return timestamp(Objects.requireNonNull(instant, "instant"));
  }

  @Nonnull
  static Map<String, String> dataValues(@CheckForNull Collection<DataValue> dataValues) {
    Map<String, String> values = new LinkedHashMap<>();
    if (dataValues != null) {
      for (DataValue dataValue : dataValues) {
        if (dataValue != null
            && dataValue.getDataElement() != null
            && dataValue.getValue() != null
            && !dataValue.getValue().isBlank()) {
          values.putIfAbsent(dataValue.getDataElement(), dataValue.getValue());
        }
      }
    }
    return values;
  }

  @Nonnull
  static String uid(@CheckForNull UID uid, @Nonnull String message) {
    return Objects.requireNonNull(uid, message).getValue();
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

  private static Optional<Type> timeValue(String value) {
    String trimmed = value.trim();
    try {
      String iso = trimmed.indexOf(':') == 1 ? "0" + trimmed : trimmed;
      LocalTime time = LocalTime.parse(iso, DateTimeFormatter.ISO_LOCAL_TIME);
      return Optional.of(new TimeType(time.format(TIME_FORMAT)));
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
  }

  private static Optional<LocalDate> datePart(String value) {
    String trimmed = value.trim();
    if (trimmed.length() > ISO_DATE_LENGTH
        && trimmed.charAt(ISO_DATE_LENGTH) != DATE_TIME_SEPARATOR) {
      return Optional.empty();
    }
    return parse(trimmed, DATE_TIME_FORMAT).map(LocalDate::from);
  }

  private static Optional<DateTimeType> dateTimeValue(String value) {
    String trimmed = value.trim();
    if (trimmed.length() == ISO_DATE_LENGTH) {
      return datePart(trimmed).map(date -> new DateTimeType(date.toString()));
    }
    DateTimeFormatter format = DATE_TIME_FORMAT.withZone(ZoneId.systemDefault());
    return parse(trimmed.replace(' ', DATE_TIME_SEPARATOR), format)
        .map(Instant::from)
        .map(FhirValueConverter::timestamp);
  }

  private static DateTimeType timestamp(Instant instant) {
    TemporalPrecisionEnum precision =
        instant.getNano() == 0 ? TemporalPrecisionEnum.SECOND : TemporalPrecisionEnum.MILLI;
    DateTimeType dateTime = new DateTimeType(Date.from(instant), precision, TimeZone.getDefault());
    dateTime.setNanos(instant.getNano());
    return dateTime;
  }

  private static Optional<TemporalAccessor> parse(String value, DateTimeFormatter format) {
    try {
      TemporalAccessor parsed = format.parse(value);
      return isSupportedYear(parsed.get(ChronoField.YEAR)) ? Optional.of(parsed) : Optional.empty();
    } catch (DateTimeParseException ex) {
      return Optional.empty();
    }
  }

  private static boolean isSupportedYear(int year) {
    return year >= MIN_YEAR && year <= MAX_YEAR;
  }
}
