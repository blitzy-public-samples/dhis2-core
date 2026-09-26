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
package org.hisp.dhis.fhir.mapping;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.hisp.dhis.common.ValueType;

/**
 * The closed catalog of FHIR R4 elements that a field mapping entry of a {@code
 * FhirResourceMapping} may target. A field mapping carries the constant name as its {@code target}
 * in JSON.
 *
 * <p>Each target belongs to exactly one {@link FhirResourceType} and declares:
 *
 * <ul>
 *   <li>the {@link FhirSourceType}s an entry for it may use;
 *   <li>its {@link Cardinality} within one mapping;
 *   <li>whether a mapping of its resource type must contain an entry for it;
 *   <li>the {@link ValueType}s a tracked entity attribute or data element source must have. A
 *       target that only takes {@link FhirSourceType#CONSTANT} entries accepts no value type.
 * </ul>
 *
 * <p>Example: {@code FhirTargetField.PATIENT_BIRTH_DATE.accepts(ValueType.DATE)} is {@code true},
 * while {@code FhirTargetField.PATIENT_BIRTH_DATE.accepts(ValueType.TEXT)} is {@code false}.
 */
public enum FhirTargetField {
  /** Fills one {@code Patient.identifier} per entry. */
  PATIENT_IDENTIFIER(
      FhirResourceType.PATIENT,
      EnumSet.of(FhirSourceType.ATTRIBUTE),
      Cardinality.MANY,
      false,
      ValueTypes.IDENTIFIER),

  /** Fills {@code Patient.name.family}. */
  PATIENT_FAMILY_NAME(
      FhirResourceType.PATIENT,
      EnumSet.of(FhirSourceType.ATTRIBUTE),
      Cardinality.ONE,
      false,
      ValueTypes.TEXT),

  /** Fills {@code Patient.name.given}. */
  PATIENT_GIVEN_NAME(
      FhirResourceType.PATIENT,
      EnumSet.of(FhirSourceType.ATTRIBUTE),
      Cardinality.ONE,
      false,
      ValueTypes.TEXT),

  /** Fills {@code Patient.gender}. */
  PATIENT_GENDER(
      FhirResourceType.PATIENT,
      EnumSet.of(FhirSourceType.ATTRIBUTE),
      Cardinality.ONE,
      false,
      ValueTypes.TEXT),

  /** Fills {@code Patient.birthDate}. */
  PATIENT_BIRTH_DATE(
      FhirResourceType.PATIENT,
      EnumSet.of(FhirSourceType.ATTRIBUTE),
      Cardinality.ONE,
      false,
      ValueTypes.BIRTH_DATE),

  /** Fills one {@code Patient.telecom} with system {@code phone} per entry. */
  PATIENT_PHONE(
      FhirResourceType.PATIENT,
      EnumSet.of(FhirSourceType.ATTRIBUTE),
      Cardinality.MANY,
      false,
      ValueTypes.PHONE),

  /** Fills one {@code Patient.telecom} with system {@code email} per entry. */
  PATIENT_EMAIL(
      FhirResourceType.PATIENT,
      EnumSet.of(FhirSourceType.ATTRIBUTE),
      Cardinality.MANY,
      false,
      ValueTypes.EMAIL),

  /** Fills {@code Patient.address.text}. */
  PATIENT_ADDRESS_TEXT(
      FhirResourceType.PATIENT,
      EnumSet.of(FhirSourceType.ATTRIBUTE),
      Cardinality.ONE,
      false,
      ValueTypes.TEXT),

  /** Fills {@code Encounter.class}. */
  ENCOUNTER_CLASS(
      FhirResourceType.ENCOUNTER,
      EnumSet.of(FhirSourceType.CONSTANT),
      Cardinality.ONE,
      true,
      ValueTypes.NONE),

  /** Fills one {@code Encounter.type} per entry. */
  ENCOUNTER_TYPE(
      FhirResourceType.ENCOUNTER,
      EnumSet.of(FhirSourceType.DATA_ELEMENT, FhirSourceType.CONSTANT),
      Cardinality.MANY,
      false,
      ValueTypes.TEXT),

  /** Fills one {@code Encounter.reasonCode} per entry. */
  ENCOUNTER_REASON(
      FhirResourceType.ENCOUNTER,
      EnumSet.of(FhirSourceType.DATA_ELEMENT),
      Cardinality.MANY,
      false,
      ValueTypes.TEXT),

  /** Decides whether an Immunization exists and fills {@code Immunization.status}. */
  IMMUNIZATION_ADMINISTERED(
      FhirResourceType.IMMUNIZATION,
      EnumSet.of(FhirSourceType.DATA_ELEMENT),
      Cardinality.ONE,
      true,
      ValueTypes.ADMINISTERED),

  /** Fills {@code Immunization.vaccineCode}. */
  IMMUNIZATION_VACCINE_CODE(
      FhirResourceType.IMMUNIZATION,
      EnumSet.of(FhirSourceType.CONSTANT),
      Cardinality.ONE,
      true,
      ValueTypes.NONE),

  /** Fills {@code Immunization.lotNumber}. */
  IMMUNIZATION_LOT_NUMBER(
      FhirResourceType.IMMUNIZATION,
      EnumSet.of(FhirSourceType.DATA_ELEMENT),
      Cardinality.ONE,
      false,
      ValueTypes.TEXT),

  /** Fills {@code Immunization.protocolApplied.doseNumber[x]}. */
  IMMUNIZATION_DOSE_NUMBER(
      FhirResourceType.IMMUNIZATION,
      EnumSet.of(FhirSourceType.DATA_ELEMENT),
      Cardinality.ONE,
      false,
      ValueTypes.DOSE_NUMBER),

  /** Fills {@code Observation.value[x]}; each entry yields one Observation per event. */
  OBSERVATION_VALUE(
      FhirResourceType.OBSERVATION,
      EnumSet.of(FhirSourceType.DATA_ELEMENT),
      Cardinality.MANY,
      true,
      ValueTypes.OBSERVATION);

  /** How many entries of one target a single mapping may contain. */
  public enum Cardinality {
    /** At most one entry per mapping. */
    ONE,

    /** Any number of entries per mapping. */
    MANY
  }

  private final FhirResourceType resourceType;

  private final Set<FhirSourceType> allowedSources;

  private final Cardinality cardinality;

  private final boolean required;

  private final Set<ValueType> acceptedValueTypes;

  FhirTargetField(
      FhirResourceType resourceType,
      EnumSet<FhirSourceType> allowedSources,
      Cardinality cardinality,
      boolean required,
      EnumSet<ValueType> acceptedValueTypes) {
    this.resourceType = resourceType;
    this.allowedSources = Collections.unmodifiableSet(EnumSet.copyOf(allowedSources));
    this.cardinality = cardinality;
    this.required = required;
    this.acceptedValueTypes = Collections.unmodifiableSet(EnumSet.copyOf(acceptedValueTypes));
  }

  /**
   * Returns the resource type this target belongs to.
   *
   * @return the resource type, never {@code null}
   */
  public FhirResourceType resourceType() {
    return resourceType;
  }

  /**
   * Returns the source types an entry for this target may use.
   *
   * @return an unmodifiable, non-empty set
   */
  public Set<FhirSourceType> allowedSources() {
    return allowedSources;
  }

  /**
   * Returns how many entries for this target one mapping may contain.
   *
   * @return the cardinality, never {@code null}
   */
  public Cardinality cardinality() {
    return cardinality;
  }

  /**
   * Returns whether every mapping of this target's resource type must contain at least one entry
   * for it.
   *
   * @return {@code true} for a required target
   */
  public boolean isRequired() {
    return required;
  }

  /**
   * Returns the value types a tracked entity attribute or data element source of this target may
   * have.
   *
   * @return an unmodifiable set, empty for a target that only takes constant entries
   */
  public Set<ValueType> acceptedValueTypes() {
    return acceptedValueTypes;
  }

  /**
   * Returns whether a source of the given value type may back this target.
   *
   * @param valueType the value type of the source attribute or data element, may be {@code null}
   * @return {@code true} when the value type is not {@code null} and is accepted
   */
  public boolean accepts(ValueType valueType) {
    return valueType != null && acceptedValueTypes.contains(valueType);
  }

  /**
   * Returns the targets of the given resource type in declaration order.
   *
   * @param type the resource type, may be {@code null}
   * @return an unmodifiable list, empty when {@code type} is {@code null}
   */
  public static List<FhirTargetField> forResourceType(FhirResourceType type) {
    return Arrays.stream(values()).filter(target -> target.resourceType == type).toList();
  }

  /** The value type sets the targets accept. */
  private static final class ValueTypes {
    static final EnumSet<ValueType> NONE = EnumSet.noneOf(ValueType.class);

    static final EnumSet<ValueType> TEXT =
        EnumSet.of(ValueType.TEXT, ValueType.LONG_TEXT, ValueType.LETTER);

    static final EnumSet<ValueType> INTEGER =
        EnumSet.of(
            ValueType.INTEGER,
            ValueType.INTEGER_POSITIVE,
            ValueType.INTEGER_NEGATIVE,
            ValueType.INTEGER_ZERO_OR_POSITIVE);

    static final EnumSet<ValueType> IDENTIFIER =
        union(
            TEXT,
            INTEGER,
            EnumSet.of(ValueType.USERNAME, ValueType.EMAIL, ValueType.PHONE_NUMBER, ValueType.URL));

    static final EnumSet<ValueType> BIRTH_DATE = EnumSet.of(ValueType.DATE, ValueType.AGE);

    static final EnumSet<ValueType> PHONE = EnumSet.of(ValueType.PHONE_NUMBER, ValueType.TEXT);

    static final EnumSet<ValueType> EMAIL = EnumSet.of(ValueType.EMAIL, ValueType.TEXT);

    static final EnumSet<ValueType> ADMINISTERED =
        EnumSet.of(ValueType.BOOLEAN, ValueType.TRUE_ONLY, ValueType.TEXT);

    static final EnumSet<ValueType> DOSE_NUMBER = union(INTEGER, EnumSet.of(ValueType.TEXT));

    static final EnumSet<ValueType> OBSERVATION =
        EnumSet.complementOf(
            EnumSet.of(
                ValueType.FILE_RESOURCE,
                ValueType.IMAGE,
                ValueType.COORDINATE,
                ValueType.GEOJSON,
                ValueType.ORGANISATION_UNIT,
                ValueType.REFERENCE));

    private ValueTypes() {}

    @SafeVarargs
    private static EnumSet<ValueType> union(EnumSet<ValueType>... sets) {
      EnumSet<ValueType> union = EnumSet.noneOf(ValueType.class);
      Arrays.stream(sets).forEach(union::addAll);
      return union;
    }
  }
}
