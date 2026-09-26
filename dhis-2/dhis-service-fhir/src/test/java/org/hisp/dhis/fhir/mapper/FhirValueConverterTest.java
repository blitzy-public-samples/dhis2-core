/*
 * Copyright (c) 2004-2022, University of Oslo
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

import static org.hisp.dhis.common.ValueType.AGE;
import static org.hisp.dhis.common.ValueType.BOOLEAN;
import static org.hisp.dhis.common.ValueType.COORDINATE;
import static org.hisp.dhis.common.ValueType.DATE;
import static org.hisp.dhis.common.ValueType.DATETIME;
import static org.hisp.dhis.common.ValueType.EMAIL;
import static org.hisp.dhis.common.ValueType.FILE_RESOURCE;
import static org.hisp.dhis.common.ValueType.GEOJSON;
import static org.hisp.dhis.common.ValueType.IMAGE;
import static org.hisp.dhis.common.ValueType.INTEGER;
import static org.hisp.dhis.common.ValueType.INTEGER_NEGATIVE;
import static org.hisp.dhis.common.ValueType.INTEGER_POSITIVE;
import static org.hisp.dhis.common.ValueType.INTEGER_ZERO_OR_POSITIVE;
import static org.hisp.dhis.common.ValueType.LETTER;
import static org.hisp.dhis.common.ValueType.LONG_TEXT;
import static org.hisp.dhis.common.ValueType.MULTI_TEXT;
import static org.hisp.dhis.common.ValueType.NUMBER;
import static org.hisp.dhis.common.ValueType.ORGANISATION_UNIT;
import static org.hisp.dhis.common.ValueType.PERCENTAGE;
import static org.hisp.dhis.common.ValueType.PHONE_NUMBER;
import static org.hisp.dhis.common.ValueType.REFERENCE;
import static org.hisp.dhis.common.ValueType.TEXT;
import static org.hisp.dhis.common.ValueType.TIME;
import static org.hisp.dhis.common.ValueType.TRUE_ONLY;
import static org.hisp.dhis.common.ValueType.UNIT_INTERVAL;
import static org.hisp.dhis.common.ValueType.URL;
import static org.hisp.dhis.common.ValueType.USERNAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import org.hisp.dhis.common.ValueType;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.DateType;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.TimeType;
import org.hl7.fhir.r4.model.Type;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link FhirValueConverter}: every row of the value-typing table, the {@code date}
 * conversion for {@code Patient.birthDate}, the structural timestamps, and the empty result for
 * unusable input.
 */
class FhirValueConverterTest {
  private static final Set<ValueType> NUMERIC =
      EnumSet.of(
          NUMBER,
          INTEGER,
          INTEGER_POSITIVE,
          INTEGER_NEGATIVE,
          INTEGER_ZERO_OR_POSITIVE,
          PERCENTAGE,
          UNIT_INTERVAL);

  private static final Set<ValueType> BOOLEANS = EnumSet.of(BOOLEAN, TRUE_ONLY);

  private static final Set<ValueType> DATES = EnumSet.of(DATE, DATETIME, AGE);

  private static final Set<ValueType> STRINGS =
      EnumSet.of(
          TEXT,
          LONG_TEXT,
          MULTI_TEXT,
          LETTER,
          PHONE_NUMBER,
          EMAIL,
          USERNAME,
          COORDINATE,
          ORGANISATION_UNIT,
          REFERENCE,
          URL,
          FILE_RESOURCE,
          IMAGE,
          GEOJSON);

  private final FhirValueConverter converter = new FhirValueConverter();

  @Test
  void numericTypesBecomeQuantity() {
    Map<ValueType, String> values =
        Map.of(
            NUMBER, "12.5",
            INTEGER, "42",
            INTEGER_POSITIVE, "7",
            INTEGER_NEGATIVE, "-3",
            INTEGER_ZERO_OR_POSITIVE, "0",
            PERCENTAGE, "0.25",
            UNIT_INTERVAL, "0.25");
    assertEquals(NUMERIC, EnumSet.copyOf(values.keySet()));

    for (Map.Entry<ValueType, String> entry : values.entrySet()) {
      ValueType type = entry.getKey();
      BigDecimal expected = new BigDecimal(entry.getValue());

      Quantity withUnit =
          assertInstanceOf(Quantity.class, convert(type, entry.getValue(), "mmHg"), type.name());
      assertEquals(0, expected.compareTo(withUnit.getValue()), type.name());
      assertEquals("mmHg", withUnit.getUnit(), type.name());

      Quantity withoutUnit =
          assertInstanceOf(Quantity.class, convert(type, entry.getValue(), null), type.name());
      assertEquals(0, expected.compareTo(withoutUnit.getValue()), type.name());
      assertFalse(withoutUnit.hasUnit(), type.name());

      Quantity blankUnit =
          assertInstanceOf(Quantity.class, convert(type, entry.getValue(), "  "), type.name());
      assertFalse(blankUnit.hasUnit(), type.name());
    }
  }

  @Test
  void booleanTypesBecomeBoolean() {
    for (ValueType type : BOOLEANS) {
      BooleanType value =
          assertInstanceOf(BooleanType.class, convert(type, "true", null), type.name());
      assertTrue(value.booleanValue(), type.name());
    }

    BooleanType value = assertInstanceOf(BooleanType.class, convert(BOOLEAN, "false", null));
    assertFalse(value.booleanValue(), BOOLEAN.name());
  }

  @Test
  void dateTypesBecomeDateTime() {
    assertDayPrecisionDateTime("2024-03-01", convert(DATE, "2024-03-01", null));
    assertDayPrecisionDateTime("2019-05-17", convert(AGE, "2019-05-17", null));
    assertDayPrecisionDateTime("2024-03-01", convert(DATE, "2024-03-01T10:15:30.000", null));
    assertDayPrecisionDateTime("2024-03-01", convert(DATETIME, "2024-03-01", null));

    DateTimeType utc =
        assertInstanceOf(DateTimeType.class, convert(DATETIME, "2024-03-01T10:15:30Z", null));
    assertEquals(Instant.parse("2024-03-01T10:15:30Z"), utc.getValue().toInstant());
    assertNotNull(utc.getTimeZone(), "DATETIME with Z has no time zone");

    DateTimeType offset =
        assertInstanceOf(DateTimeType.class, convert(DATETIME, "2024-03-01T10:15:30+02:00", null));
    assertEquals(Instant.parse("2024-03-01T08:15:30Z"), offset.getValue().toInstant());
    assertNotNull(offset.getTimeZone(), "DATETIME with offset has no time zone");

    DateTimeType local =
        assertInstanceOf(DateTimeType.class, convert(DATETIME, "2024-03-01T10:15:30", null));
    assertEquals(
        LocalDateTime.parse("2024-03-01T10:15:30").atZone(ZoneId.systemDefault()).toInstant(),
        local.getValue().toInstant());
    assertTrue(
        local.getValueAsString().startsWith("2024-03-01T10:15:30"), local.getValueAsString());
    assertNotNull(local.getTimeZone(), "local DATETIME has no time zone");
  }

  @Test
  void timeBecomesTime() {
    assertEquals(
        "08:30:00", assertInstanceOf(TimeType.class, convert(TIME, "08:30", null)).getValue());
    assertEquals(
        "08:30:15", assertInstanceOf(TimeType.class, convert(TIME, "08:30:15", null)).getValue());
    assertEquals(
        "08:30:15",
        assertInstanceOf(TimeType.class, convert(TIME, "08:30:15.250", null)).getValue());
  }

  @Test
  void otherTypesBecomeString() {
    Set<ValueType> others = EnumSet.allOf(ValueType.class);
    others.removeAll(NUMERIC);
    others.removeAll(BOOLEANS);
    others.removeAll(DATES);
    others.remove(TIME);
    assertEquals(STRINGS, others);

    for (ValueType type : others) {
      StringType value =
          assertInstanceOf(StringType.class, convert(type, "some value", "mmHg"), type.name());
      assertEquals("some value", value.getValue(), type.name());

      StringType padded =
          assertInstanceOf(StringType.class, convert(type, " some value ", null), type.name());
      assertEquals(" some value ", padded.getValue(), type.name());
    }
  }

  @Test
  void typedValuesIgnoreSurroundingWhitespace() {
    Quantity number = assertInstanceOf(Quantity.class, convert(NUMBER, " 42 ", null));
    assertEquals(0, new BigDecimal("42").compareTo(number.getValue()));
    assertTrue(
        assertInstanceOf(BooleanType.class, convert(BOOLEAN, " true ", null)).booleanValue());
    assertDayPrecisionDateTime("2024-03-01", convert(DATE, " 2024-03-01 ", null));
    assertEquals(
        "08:30:00", assertInstanceOf(TimeType.class, convert(TIME, " 08:30 ", null)).getValue());
  }

  @Test
  void toDateAcceptsOnlyDateAndAge() {
    assertDate("1985-04-12", converter.toDate(DATE, "1985-04-12"));
    assertDate("2019-05-17", converter.toDate(AGE, "2019-05-17"));
    assertDate("1985-04-12", converter.toDate(DATE, "1985-04-12T00:00:00.000"));

    for (ValueType type : EnumSet.complementOf(EnumSet.of(DATE, AGE))) {
      assertTrue(converter.toDate(type, "1985-04-12").isEmpty(), type.name());
    }

    assertTrue(converter.toDate(DATE, "2020-13-45").isEmpty());
    assertTrue(converter.toDate(DATE, "0000-01-01").isEmpty());
    assertTrue(converter.toDate(DATE, null).isEmpty());
    assertTrue(converter.toDate(DATE, "   ").isEmpty());
    assertTrue(converter.toDate(null, "1985-04-12").isEmpty());
  }

  @Test
  void structuralTimestamps() {
    Instant timestamp = Instant.parse("2024-03-01T10:15:30Z");

    InstantType instant = converter.instant(timestamp);
    assertEquals(timestamp, instant.getValue().toInstant());
    assertEquals(TemporalPrecisionEnum.MILLI, instant.getPrecision());
    assertTrue(
        converter
            .instant(Instant.parse("2024-03-01T10:15:30.123Z"))
            .getValueAsString()
            .contains(":30.123"));

    DateTimeType dateTime = converter.dateTime(timestamp);
    assertEquals(timestamp, dateTime.getValue().toInstant());
    assertEquals(TemporalPrecisionEnum.SECOND, dateTime.getPrecision());
    assertEquals(TimeZone.getDefault().getID(), dateTime.getTimeZone().getID());
  }

  @Test
  void unusableInputIsEmpty() {
    for (ValueType type : ValueType.values()) {
      assertNotConverted(type, null);
      assertNotConverted(type, "");
      assertNotConverted(type, "   ");
    }
    assertTrue(converter.toFhir(null, "42", null).isEmpty());

    assertNotConverted(NUMBER, "abc");
    assertNotConverted(INTEGER, "abc");
    assertNotConverted(DATE, "2020-13-45");
    assertNotConverted(DATE, "0000-01-01");
    assertNotConverted(DATE, "2024-03-01 10:15");
    assertNotConverted(AGE, "2020-13-45");
    assertNotConverted(DATETIME, "2024-03-01T25:00:00");
    assertNotConverted(DATETIME, "0000-03-01T10:15:30Z");
    assertNotConverted(DATETIME, "not a date");
    assertNotConverted(TIME, "25:99");
    assertNotConverted(BOOLEAN, "yes");
    assertNotConverted(BOOLEAN, "TRUE");
    assertNotConverted(TRUE_ONLY, "1");
  }

  private Type convert(ValueType type, String value, String unit) {
    Optional<Type> result = converter.toFhir(type, value, unit);
    assertTrue(result.isPresent(), () -> type + " value '" + value + "' was not converted");
    return result.get();
  }

  private void assertNotConverted(ValueType type, String value) {
    assertTrue(
        converter.toFhir(type, value, null).isEmpty(),
        () -> type + " value '" + value + "' was converted");
  }

  private static void assertDayPrecisionDateTime(String expected, Type actual) {
    DateTimeType dateTime = assertInstanceOf(DateTimeType.class, actual);
    assertEquals(expected, dateTime.getValueAsString());
    assertEquals(TemporalPrecisionEnum.DAY, dateTime.getPrecision());
  }

  private static void assertDate(String expected, Optional<DateType> actual) {
    assertTrue(actual.isPresent(), () -> "'" + expected + "' was not converted to a date");
    assertEquals(expected, actual.get().getValueAsString());
    assertEquals(TemporalPrecisionEnum.DAY, actual.get().getPrecision());
  }
}
