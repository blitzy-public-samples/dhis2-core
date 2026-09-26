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

import static java.util.stream.Collectors.joining;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.*;
import org.hisp.dhis.fhir.mapping.FhirResourceType;

/**
 * Logical id of an event-derived FHIR resource: DHIS2 UIDs joined by {@code -}, {@code
 * {enrollmentUid}-{eventUid}} for {@code Encounter} and {@code
 * {enrollmentUid}-{eventUid}-{dataElementUid}} for {@code Immunization} and {@code Observation}.
 */
public record FhirLogicalId(
    @Nonnull String enrollment, @CheckForNull String event, @CheckForNull String dataElement) {
  private static final Pattern UID_SEGMENT = Pattern.compile("^[A-Za-z][A-Za-z0-9]{10}$");
  private static final String SEPARATOR = "-";
  private static final int UID_LENGTH = 11;
  private static final int ENCOUNTER_SEGMENTS = 2;
  private static final int PER_DATA_ELEMENT_SEGMENTS = 3;

  /** Rejects a {@code dataElement} segment without an {@code event} segment. */
  public FhirLogicalId {
    Objects.requireNonNull(enrollment, "enrollment must not be null");
    if (dataElement != null && event == null) {
      throw new IllegalArgumentException("A data element segment requires an event segment");
    }
  }

  @Nonnull
  public static FhirLogicalId encounter(@Nonnull String enrollment, @Nonnull String event) {
    return new FhirLogicalId(enrollment, event, null);
  }

  @Nonnull
  public static FhirLogicalId perDataElement(
      @Nonnull String enrollment, @Nonnull String event, @Nonnull String dataElement) {
    return new FhirLogicalId(enrollment, event, dataElement);
  }

  /** Returns the present segments joined by {@code -}. */
  @Nonnull
  public String compose() {
    return Stream.of(enrollment, event, dataElement)
        .filter(Objects::nonNull)
        .collect(joining(SEPARATOR));
  }

  /**
   * Parses an id of the given type; empty, without throwing, when the type is {@code null} or
   * {@code PATIENT}, or the id is not exactly 2 UID segments for {@code ENCOUNTER} and 3 otherwise.
   */
  @Nonnull
  public static Optional<FhirLogicalId> parse(
      @CheckForNull FhirResourceType type, @CheckForNull String id) {
    if (type == null || id == null || id.isEmpty()) {
      return Optional.empty();
    }
    int expectedSegments =
        switch (type) {
          case PATIENT -> 0;
          case ENCOUNTER -> ENCOUNTER_SEGMENTS;
          case IMMUNIZATION, OBSERVATION -> PER_DATA_ELEMENT_SEGMENTS;
        };
    if (expectedSegments == 0
        || id.length() != expectedSegments * UID_LENGTH + (expectedSegments - 1)) {
      return Optional.empty();
    }
    String[] segments = id.split(SEPARATOR, -1);
    if (segments.length != expectedSegments) {
      return Optional.empty();
    }
    for (String segment : segments) {
      if (!UID_SEGMENT.matcher(segment).matches()) {
        return Optional.empty();
      }
    }
    return Optional.of(
        expectedSegments == ENCOUNTER_SEGMENTS
            ? encounter(segments[0], segments[1])
            : perDataElement(segments[0], segments[1], segments[2]));
  }
}
