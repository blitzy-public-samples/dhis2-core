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

import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirSourceType.*;
import static org.hisp.dhis.fhir.mapping.FhirTargetField.Cardinality.*;

import java.util.*;
import org.hisp.dhis.common.ValueType;

/** The closed catalog of FHIR R4 elements a {@link FhirFieldMapping} may target. */
public enum FhirTargetField {
  /** A {@code Patient.identifier} under the entry's {@code system} per readable, nonblank value. */
  PATIENT_IDENTIFIER(PATIENT, EnumSet.of(ATTRIBUTE), MANY, false, ValueTypes.IDENTIFIER),
  /** {@code Patient.name[0].family}: the attribute value. */
  PATIENT_FAMILY_NAME(PATIENT, EnumSet.of(ATTRIBUTE), ONE, false, ValueTypes.TEXT),
  /** {@code Patient.name[0].given[0]}: the attribute value. */
  PATIENT_GIVEN_NAME(PATIENT, EnumSet.of(ATTRIBUTE), ONE, false, ValueTypes.TEXT),
  /** {@code Patient.gender}: the attribute value translated by the entry's {@code valueMap}. */
  PATIENT_GENDER(PATIENT, EnumSet.of(ATTRIBUTE), ONE, false, ValueTypes.TEXT),
  /** {@code Patient.birthDate}: the attribute's date or age value. */
  PATIENT_BIRTH_DATE(PATIENT, EnumSet.of(ATTRIBUTE), ONE, false, ValueTypes.BIRTH_DATE),
  /** A {@code Patient.telecom} with system {@code phone} per readable, nonblank attribute value. */
  PATIENT_PHONE(PATIENT, EnumSet.of(ATTRIBUTE), MANY, false, ValueTypes.PHONE),
  /** A {@code Patient.telecom} with system {@code email} per readable, nonblank attribute value. */
  PATIENT_EMAIL(PATIENT, EnumSet.of(ATTRIBUTE), MANY, false, ValueTypes.EMAIL),
  /** {@code Patient.address[0].text}: the attribute value. */
  PATIENT_ADDRESS_TEXT(PATIENT, EnumSet.of(ATTRIBUTE), ONE, false, ValueTypes.TEXT),
  /** {@code Encounter.class}: the entry's constant coding. */
  ENCOUNTER_CLASS(ENCOUNTER, EnumSet.of(CONSTANT), ONE, true, ValueTypes.NONE),
  /** {@code Encounter.type}: the constant coding, or the value as a code under {@code system}. */
  ENCOUNTER_TYPE(ENCOUNTER, EnumSet.of(DATA_ELEMENT, CONSTANT), MANY, false, ValueTypes.TEXT),
  /** An {@code Encounter.reasonCode.text} per readable, nonblank data element value of an entry. */
  ENCOUNTER_REASON(ENCOUNTER, EnumSet.of(DATA_ELEMENT), MANY, false, ValueTypes.TEXT),
  /** {@code Immunization.status}: not-done when the value is false, otherwise completed. */
  IMMUNIZATION_ADMINISTERED(
      IMMUNIZATION, EnumSet.of(DATA_ELEMENT), ONE, true, ValueTypes.ADMINISTERED),
  /** {@code Immunization.vaccineCode}: the entry's constant coding. */
  IMMUNIZATION_VACCINE_CODE(IMMUNIZATION, EnumSet.of(CONSTANT), ONE, true, ValueTypes.NONE),
  /** {@code Immunization.lotNumber}: the data element value. */
  IMMUNIZATION_LOT_NUMBER(IMMUNIZATION, EnumSet.of(DATA_ELEMENT), ONE, false, ValueTypes.TEXT),
  /** {@code Immunization.protocolApplied[0].doseNumber[x]}: the data element value. */
  IMMUNIZATION_DOSE_NUMBER(
      IMMUNIZATION, EnumSet.of(DATA_ELEMENT), ONE, false, ValueTypes.DOSE_NUMBER),
  /** An entry-coded Observation per readable, nonblank value; {@code value[x]} if it converts. */
  OBSERVATION_VALUE(OBSERVATION, EnumSet.of(DATA_ELEMENT), MANY, true, ValueTypes.OBSERVATION);

  /** How many entries of one target a single mapping may contain. */
  public enum Cardinality {
    ONE,
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

  public FhirResourceType resourceType() {
    return resourceType;
  }

  public Set<FhirSourceType> allowedSources() {
    return allowedSources;
  }

  public Cardinality cardinality() {
    return cardinality;
  }

  public boolean isRequired() {
    return required;
  }

  /** Returns the accepted source value types; empty for a target that takes only constants. */
  public Set<ValueType> acceptedValueTypes() {
    return acceptedValueTypes;
  }

  /** Returns whether a source of the given value type may back this target; false for null. */
  public boolean accepts(ValueType valueType) {
    return valueType != null && acceptedValueTypes.contains(valueType);
  }

  /** Returns the targets of the given resource type in declaration order; empty for null. */
  public static List<FhirTargetField> forResourceType(FhirResourceType type) {
    return Arrays.stream(values()).filter(target -> target.resourceType == type).toList();
  }

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
