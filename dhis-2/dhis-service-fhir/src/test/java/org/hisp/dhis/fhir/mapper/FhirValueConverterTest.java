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

import static ca.uhn.fhir.model.api.TemporalPrecisionEnum.*;
import static org.hisp.dhis.common.ValueType.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.hisp.dhis.common.ValueType;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.Test;

class FhirValueConverterTest {
  private static final Map<ValueType, String> NUMERIC =
      rows("NUMBER=12.5; INTEGER=42; INTEGER_POSITIVE=7; INTEGER_NEGATIVE=-3; PERCENTAGE=0.25;"
              + " INTEGER_ZERO_OR_POSITIVE=0; UNIT_INTERVAL=0.25")
          .collect(Collectors.toMap(row -> ValueType.valueOf(row[0]), row -> row[1]));
  private static final Set<ValueType> BOOLEANS = EnumSet.of(BOOLEAN, TRUE_ONLY);
  private final FhirValueConverter converter = new FhirValueConverter();

  @Test
  void everyValueTypeFollowsTheTypingTableAndUnusableInputIsEmpty() {
    for (ValueType type : NUMERIC.keySet()) {
      String value = NUMERIC.get(type);
      for (String unit : Arrays.asList("mmHg", null, "  ")) {
        var quantity = assertInstanceOf(Quantity.class, convert(type, value, unit), type.name());
        assertEquals(0, new BigDecimal(value).compareTo(quantity.getValue()), type.name());
        assertEquals("mmHg".equals(unit) ? "mmHg" : null, quantity.getUnit(), type + " " + unit);
      }
    }
    Quantity number = assertInstanceOf(Quantity.class, convert(NUMBER, " 42 ", null));
    assertEquals(0, new BigDecimal("42").compareTo(number.getValue()));
    for (String value : "true, true ,TRUE,True,tRuE,false,FALSE,False, FALSE ".split(",")) {
      boolean expected = Boolean.parseBoolean(value.trim());
      assertEquals(Optional.of(expected), converter.toBoolean(value), value);
      for (ValueType type : BOOLEANS) {
        var bool = assertInstanceOf(BooleanType.class, convert(type, value, null), value);
        assertEquals(expected, bool.getValue(), type + " " + value);
      }
    }
    var texts = EnumSet.of(TEXT, LONG_TEXT, MULTI_TEXT, LETTER, PHONE_NUMBER, EMAIL, USERNAME, URL);
    texts.addAll(List.of(COORDINATE, ORGANISATION_UNIT, REFERENCE, FILE_RESOURCE, IMAGE, GEOJSON));
    Set<ValueType> others = EnumSet.complementOf(EnumSet.of(DATE, DATETIME, AGE, TIME));
    List.of(NUMERIC.keySet(), BOOLEANS).forEach(others::removeAll);
    assertEquals(texts, others);
    for (ValueType type : others) {
      for (String[] row : new String[][] {{"some value", "mmHg"}, {" some value ", null}}) {
        var value = assertInstanceOf(StringType.class, convert(type, row[0], row[1]), type.name());
        assertEquals(row[0], value.getValue(), type.name());
      }
    }
    for (ValueType type : ValueType.values()) {
      Arrays.asList(null, "", "   ").forEach(blank -> assertNotConverted(type, blank));
    }
    assertTrue(converter.toFhir(null, "42", null).isEmpty());
    String rejected =
        """
        NUMBER=abc; INTEGER=abc; DATE=2020-13-45; DATE=0000-01-01; DATE=2024-03-01 10:15;
        AGE=2020-13-45; DATE=2024-03-01T25:00:00; AGE=2024-03-01T25:00:00; DATE=2024-03-01Tgarbage;
        AGE=2024-03-01Tgarbage; DATETIME=2024-03-01T25:00:00; DATETIME=0000-03-01T10:15:30Z;
        DATETIME=not a date; TIME=25:99; BOOLEAN=yes; TRUE_ONLY=1; DATETIME=2024-06-01T10:30:00+020;
        DATETIME=2024-06-01T10:30:00+02:0; DATETIME=2024-06-01T24:00; DATETIME=2024-02-30T10:00;
        DATETIME=2024-06-01T10:30:00.; AGE=0000-01-01; BOOLEAN=0; BOOLEAN=T\
        """;
    rows(rejected).forEach(row -> assertNotConverted(ValueType.valueOf(row[0]), row[1]));
    assertDayPrecisionDateTime("2024-03-01", convert(DATE, " 2024-03-01 ", null));
    assertDayPrecisionDateTime("2019-05-17", convert(AGE, "2019-05-17", null));
    assertDayPrecisionDateTime("2024-03-01", convert(DATE, "2024-03-01T10:15:30.000", null));
    assertDayPrecisionDateTime("2024-03-01", convert(AGE, "2024-03-01T23:30:00-05:00", null));
    assertDayPrecisionDateTime("2024-03-01", convert(DATETIME, "2024-03-01", null));
    String accepted =
        """
        2024-03-01T10:15:30Z=2024-03-01T10:15:30Z; 2024-03-01T10:15:30+02:00=2024-03-01T08:15:30Z;
        2024-03-01T10:15:30.123Z=2024-03-01T10:15:30.123Z; 2024-03-01T10:15:30=2024-03-01T10:15:30;
        2024-06-01T10:30:00.000+0200=2024-06-01T08:30:00Z; 2024-06-01T10+0200=2024-06-01T08:00:00Z;
        2024-06-01T10:30:00+02=2024-06-01T08:30:00Z; 2024-06-01T10:30+0200=2024-06-01T08:30:00Z;
        2024-06-01T10:30:00.000456Z=2024-06-01T10:30:00.000456Z; 2024-06-01T10=2024-06-01T10:00:00;
        2024-06-01 10:30:00+0200=2024-06-01T08:30:00Z; 2024-06-01t10:30:00z=2024-06-01T10:30:00Z;
        2024-06-01T10:30:00.123456=2024-06-01T10:30:00.123456;
        2024-06-01T10:30:00.123456789-05:30=2024-06-01T16:00:00.123456789Z\
        """;
    rows(accepted).forEach(row -> assertDateTime(row[1], convert(DATETIME, row[0], null), row[0]));
    assertDate("1985-04-12", converter.toDate(DATE, "1985-04-12T00:00:00.000"));
    assertDate("1985-04-12", converter.toDate(DATE, "1985-04-12T00:00:00+02:00"));
    assertDate("1985-04-12", converter.toDate(AGE, "1985-04-12T23:30:00Z"));
    EnumSet.complementOf(EnumSet.of(DATE, AGE))
        .forEach(type -> assertTrue(converter.toDate(type, "1985-04-12").isEmpty(), type.name()));
    assertTrue(converter.toDate(null, "1985-04-12").isEmpty());
    Instant timestamp = Instant.parse("2024-03-01T10:15:30Z");
    Instant fractional = Instant.parse("2024-03-01T10:15:30.123Z");
    InstantType instant = converter.instant(timestamp);
    assertEquals(timestamp, instant.getValue().toInstant());
    assertEquals(MILLI, instant.getPrecision());
    List.of(converter.instant(fractional), converter.dateTime(fractional))
        .forEach(t -> assertTrue(t.getValueAsString().contains(":30.123"), t.getValueAsString()));
    for (Instant value : List.of(timestamp, fractional, fractional.plusNanos(456_000))) {
      assertDateTime(value.toString(), converter.dateTime(value), value.toString());
    }
    var milli = new DateTimeType(Date.from(fractional), MILLI, TimeZone.getDefault());
    assertEquals(milli.getValueAsString(), converter.dateTime(fractional).getValueAsString());
    String times =
        " 08:30 =08:30:00; 08:30=08:30:00; 08:30:15=08:30:15; 08:30:15.250=08:30:15;"
            + " 08:30:15.123456789=08:30:15; 08:30:15.=08:30:15; 8:30=08:30:00";
    for (String[] r : rows(times).toList()) {
      assertEquals(r[1], assertInstanceOf(TimeType.class, convert(TIME, r[0], null)).getValue());
    }
  }

  private Type convert(ValueType type, String value, String unit) {
    return converter.toFhir(type, value, unit).orElseThrow(() -> new AssertionError(value));
  }

  private void assertNotConverted(ValueType type, String value) {
    assertTrue(converter.toFhir(type, value, null).isEmpty(), () -> type + " " + value);
    assertTrue(converter.toDate(type, value).isEmpty(), () -> "toDate " + type + " " + value);
    assertTrue(converter.toBoolean(value).isEmpty(), () -> "toBoolean " + value);
  }

  private static Stream<String[]> rows(String table) {
    return Stream.of(table.split(";\\s*")).map(row -> row.split("=", 2));
  }

  private static void assertDateTime(String expected, Type actual, String label) {
    ZoneId zone = expected.endsWith("Z") ? ZoneOffset.UTC : ZoneId.systemDefault();
    Instant instant = LocalDateTime.parse(expected.replace("Z", "")).atZone(zone).toInstant();
    DateTimeType dateTime = assertInstanceOf(DateTimeType.class, actual, label);
    assertEquals(instant, OffsetDateTime.parse(dateTime.getValueAsString()).toInstant(), label);
    assertEquals(Instant.ofEpochMilli(instant.toEpochMilli()), dateTime.getValue().toInstant());
    assertEquals((long) instant.getNano(), dateTime.getNanos(), label);
    assertEquals(instant.getNano() == 0 ? SECOND : MILLI, dateTime.getPrecision(), label);
    assertEquals(TimeZone.getDefault().getID(), dateTime.getTimeZone().getID(), label);
  }

  private static void assertDayPrecisionDateTime(String expected, Type actual) {
    DateTimeType dateTime = assertInstanceOf(DateTimeType.class, actual);
    assertEquals(expected, dateTime.getValueAsString());
    assertEquals(DAY, dateTime.getPrecision());
  }

  private static void assertDate(String expected, Optional<DateType> actual) {
    assertEquals(expected, actual.orElseThrow().getValueAsString());
    assertEquals(DAY, actual.get().getPrecision());
  }
}
