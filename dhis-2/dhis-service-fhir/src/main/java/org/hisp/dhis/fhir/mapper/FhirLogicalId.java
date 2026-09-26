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

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.hisp.dhis.fhir.mapping.FhirResourceType;

/**
 * Logical id of a FHIR resource derived from a Tracker event. Every segment is a DHIS2 UID, an
 * alphanumeric string of 11 characters starting with a letter, and segments are joined by {@code
 * -}:
 *
 * <ul>
 *   <li>{@code Encounter}: {@code {enrollmentUid}-{eventUid}}, 2 segments and 23 characters.
 *   <li>{@code Immunization} and {@code Observation}: {@code
 *       {enrollmentUid}-{eventUid}-{dataElementUid}}, 3 segments and 35 characters.
 * </ul>
 *
 * <p>{@code Patient} ids are plain tracked entity UIDs and are not represented by this type.
 *
 * <p>Example:
 *
 * <pre>{@code
 * String id = FhirLogicalId.encounter(enrollmentUid, eventUid).compose();
 * Optional<FhirLogicalId> parsed = FhirLogicalId.parse(FhirResourceType.ENCOUNTER, id);
 * }</pre>
 *
 * @param enrollment the enrollment UID, always present
 * @param event the event UID, present for every event-derived resource
 * @param dataElement the data element UID, present for {@code Immunization} and {@code Observation}
 *     ids only
 */
public record FhirLogicalId(
    @Nonnull String enrollment, @CheckForNull String event, @CheckForNull String dataElement) {

  private static final Pattern UID_SEGMENT = Pattern.compile("^[A-Za-z][A-Za-z0-9]{10}$");

  private static final String SEPARATOR = "-";

  private static final int UID_LENGTH = 11;

  private static final int ENCOUNTER_SEGMENTS = 2;

  private static final int PER_DATA_ELEMENT_SEGMENTS = 3;

  /**
   * Creates a logical id.
   *
   * @throws NullPointerException if {@code enrollment} is {@code null}
   * @throws IllegalArgumentException if {@code dataElement} is given without {@code event}
   */
  public FhirLogicalId {
    Objects.requireNonNull(enrollment, "enrollment must not be null");
    if (dataElement != null && event == null) {
      throw new IllegalArgumentException("A data element segment requires an event segment");
    }
  }

  /**
   * Creates the logical id of an {@code Encounter}.
   *
   * @param enrollment the enrollment UID
   * @param event the event UID
   * @return the id {@code {enrollment}-{event}}
   */
  @Nonnull
  public static FhirLogicalId encounter(@Nonnull String enrollment, @Nonnull String event) {
    return new FhirLogicalId(enrollment, event, null);
  }

  /**
   * Creates the logical id of an {@code Immunization} or {@code Observation}.
   *
   * @param enrollment the enrollment UID
   * @param event the event UID
   * @param dataElement the data element UID
   * @return the id {@code {enrollment}-{event}-{dataElement}}
   */
  @Nonnull
  public static FhirLogicalId perDataElement(
      @Nonnull String enrollment, @Nonnull String event, @Nonnull String dataElement) {
    return new FhirLogicalId(enrollment, event, dataElement);
  }

  /**
   * Returns the FHIR logical id string: the present segments in the order {@code enrollment},
   * {@code event}, {@code dataElement}, joined by {@code -}.
   *
   * @return the composed id
   */
  @Nonnull
  public String compose() {
    return Stream.of(enrollment, event, dataElement)
        .filter(Objects::nonNull)
        .collect(joining(SEPARATOR));
  }

  /**
   * Parses a logical id of the given resource type.
   *
   * <p>{@code ENCOUNTER} ids must have exactly 2 segments, {@code IMMUNIZATION} and {@code
   * OBSERVATION} ids exactly 3. Each segment must be a UID, matched exactly as given, without
   * trimming or case folding. The result is empty when the type is {@code null} or {@code PATIENT},
   * when the id is {@code null} or empty, when the segment count differs, and when any segment,
   * including an empty one from a leading, trailing or doubled {@code -}, is not a UID. This method
   * never throws.
   *
   * @param type the resource type the id belongs to
   * @param id the logical id from the request
   * @return the parsed id, or empty when the id is not a well-formed id of that type
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
