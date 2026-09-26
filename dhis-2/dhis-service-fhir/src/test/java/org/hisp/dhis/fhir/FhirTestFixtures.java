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
package org.hisp.dhis.fhir;

import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.hisp.dhis.common.*;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.program.*;
import org.hisp.dhis.trackedentity.*;
import org.hisp.dhis.webapi.controller.tracker.view.*;

/**
 * FHIR unit-test builders of a fixed data shape with timestamps {@link #UPDATED}; {@link #uid()}
 * and {@code resolved(...)} without {@code uid} use a new random UID. Builders other than {@code
 * resolved(...)} store new mutable collections and {@code null} scalars as given, and reject a
 * {@code null} varargs array.
 */
public final class FhirTestFixtures {
  public static final Instant UPDATED = Instant.parse("2024-03-15T10:15:30Z");
  public static final Instant OCCURRED = Instant.parse("2024-03-10T09:00:00Z");
  public static final Instant SCHEDULED = Instant.parse("2024-04-10T09:00:00Z");
  public static final String ENCOUNTER_CLASS_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/v3-ActCode";
  public static final String ENCOUNTER_CLASS_CODE = "AMB";
  public static final String ENCOUNTER_CLASS_DISPLAY = "ambulatory";
  public static final String CVX_SYSTEM = "http://hl7.org/fhir/sid/cvx";
  public static final String CVX_CODE = "03";
  public static final String CVX_DISPLAY = "MMR";
  public static final String LOINC_SYSTEM = "http://loinc.org";
  public static final String LOINC_BODY_HEIGHT_CODE = "8302-2";
  public static final String LOINC_BODY_HEIGHT_DISPLAY = "Body height";
  public static final String BODY_HEIGHT_UNIT = "cm";
  public static final String LOINC_BODY_WEIGHT_CODE = "29463-7";
  public static final String LOINC_BODY_WEIGHT_DISPLAY = "Body weight";
  public static final String BODY_WEIGHT_UNIT = "kg";
  public static final String IDENTIFIER_SYSTEM = "urn:dhis2:fhir-test:national-id";

  private FhirTestFixtures() {}

  /** Returns a new random UID. */
  public static String uid() {
    return CodeGenerator.generateUid();
  }

  public static TrackedEntity trackedEntity(
      String uid, String type, Instant updatedAt, Attribute... attributes) {
    return TrackedEntity.builder()
        .trackedEntity(UID.ofNullable(uid))
        .trackedEntityType(type)
        .updatedAt(updatedAt)
        .attributes(new ArrayList<>(Arrays.asList(attributes)))
        .build();
  }

  public static Attribute attribute(String uid, ValueType valueType, String value) {
    return Attribute.builder().attribute(uid).valueType(valueType).value(value).build();
  }

  public static Enrollment enrollment(
      String enrollment, String trackedEntity, String program, Event... events) {
    return Enrollment.builder()
        .enrollment(UID.ofNullable(enrollment))
        .trackedEntity(UID.ofNullable(trackedEntity))
        .program(program)
        .updatedAt(UPDATED)
        .events(new ArrayList<>(Arrays.asList(events)))
        .build();
  }

  public static Event event(
      String event,
      String stage,
      EventStatus status,
      Instant occurredAt,
      Instant scheduledAt,
      Instant updatedAt,
      DataValue... values) {
    return Event.builder()
        .event(UID.ofNullable(event))
        .programStage(stage)
        .status(status)
        .occurredAt(occurredAt)
        .scheduledAt(scheduledAt)
        .updatedAt(updatedAt)
        .dataValues(new HashSet<>(Arrays.asList(values)))
        .build();
  }

  public static DataValue dataValue(String dataElement, String value) {
    return DataValue.builder().dataElement(dataElement).value(value).build();
  }

  /** Builds each entry into a new mutable list, in order. */
  public static List<FhirFieldMapping> entries(Entry... entries) {
    return new ArrayList<>(Arrays.stream(entries).map(Entry::build).toList());
  }

  /** Builds a resolved mapping with a new UID and no search constraints. */
  public static ResolvedMapping resolved(
      FhirResourceType type,
      String entityType,
      String program,
      String stage,
      List<FhirFieldMapping> entries,
      Map<String, ValueType> valueTypes) {
    return resolved(type, entityType, program, stage, entries, valueTypes, Map.of(), Map.of());
  }

  /** Builds a resolved mapping with a new UID. */
  public static ResolvedMapping resolved(
      FhirResourceType type,
      String entityType,
      String program,
      String stage,
      List<FhirFieldMapping> entries,
      Map<String, ValueType> valueTypes,
      Map<String, Set<QueryOperator>> blocked,
      Map<String, Integer> minChars) {
    return resolved(
        uid(), UPDATED, type, entityType, program, stage, entries, valueTypes, blocked, minChars);
  }

  /** Builds a resolved mapping of unmodifiable entries and collections, empty for {@code null}. */
  public static ResolvedMapping resolved(
      String uid,
      Instant lastUpdated,
      FhirResourceType type,
      String entityType,
      String program,
      String stage,
      List<FhirFieldMapping> entries,
      Map<String, ValueType> valueTypes,
      Map<String, Set<QueryOperator>> blocked,
      Map<String, Integer> minChars) {
    return new ResolvedMapping(
        uid, type, entityType, program, stage, entries, valueTypes, blocked, minChars, lastUpdated);
  }

  /** Builds a stored mapping with the entries, {@code null} ones included, in a mutable list. */
  public static FhirResourceMapping mapping(
      String uid,
      FhirResourceType type,
      TrackedEntityType trackedEntityType,
      Program program,
      ProgramStage stage,
      FhirFieldMapping... entries) {
    FhirResourceMapping mapping = identified(new FhirResourceMapping(), uid, "FHIR " + type + " ");
    mapping.setResourceType(type);
    mapping.setTrackedEntityType(trackedEntityType);
    mapping.setProgram(program);
    mapping.setProgramStage(stage);
    mapping.setFieldMappings(new ArrayList<>(Arrays.asList(entries)));
    return mapping;
  }

  public static TrackedEntityAttribute trackedEntityAttribute(String uid, ValueType valueType) {
    TrackedEntityAttribute attribute = identified(new TrackedEntityAttribute(), uid, "Attribute ");
    attribute.setShortName(attribute.getName());
    attribute.setValueType(valueType);
    return attribute;
  }

  /** Builds a tracked entity type with the attributes as a mutable type-attribute list. */
  public static TrackedEntityType trackedEntityType(String uid, TrackedEntityAttribute... attrs) {
    TrackedEntityType type = identified(new TrackedEntityType(), uid, "Type ");
    type.setShortName(type.getName());
    type.setTrackedEntityTypeAttributes(
        Arrays.stream(attrs)
            .map(attribute -> new TrackedEntityTypeAttribute(type, attribute))
            .collect(Collectors.toCollection(ArrayList::new)));
    return type;
  }

  public static DataElement dataElement(String uid, ValueType valueType) {
    DataElement dataElement = identified(new DataElement(), uid, "Data element ");
    dataElement.setShortName(dataElement.getName());
    dataElement.setValueType(valueType);
    return dataElement;
  }

  /** Builds a program with a mutable program-attribute list and an empty mutable stage set. */
  public static Program program(
      String uid, ProgramType kind, TrackedEntityType type, TrackedEntityAttribute... attributes) {
    Program program = identified(new Program(), uid, "Program ");
    program.setShortName(program.getName());
    program.setProgramType(kind);
    program.setTrackedEntityType(type);
    program.setProgramAttributes(
        Arrays.stream(attributes)
            .map(attribute -> new ProgramTrackedEntityAttribute(program, attribute))
            .collect(Collectors.toCollection(ArrayList::new)));
    return program;
  }

  /** Builds a program stage and adds it to the stages of {@code program} unless it is null. */
  public static ProgramStage programStage(String uid, Program program, DataElement... elements) {
    ProgramStage stage = identified(new ProgramStage(), uid, "Stage ");
    stage.setShortName(stage.getName());
    stage.setProgram(program);
    stage.setProgramStageDataElements(
        Arrays.stream(elements)
            .map(dataElement -> new ProgramStageDataElement(stage, dataElement))
            .collect(Collectors.toCollection(HashSet::new)));
    if (program != null) {
      program.getProgramStages().add(stage);
    }
    return stage;
  }

  private static <T extends IdentifiableObject> T identified(T object, String uid, String prefix) {
    object.setUid(uid);
    object.setName(prefix + uid);
    object.setCreated(Date.from(UPDATED));
    object.setLastUpdated(Date.from(UPDATED));
    return object;
  }

  /** Finds the first instance of the class with the given valid UID, else {@code null}. */
  public static BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup(
      IdentifiableObject... objects) {
    List<IdentifiableObject> registered = new ArrayList<>(Arrays.asList(objects));
    return (klass, uid) ->
        registered.stream()
            .filter(o -> klass != null && klass.isInstance(o))
            .filter(o -> uid != null && uid.equals(uidValue(o)))
            .findFirst()
            .orElse(null);
  }

  private static String uidValue(IdentifiableObject object) {
    try {
      return object.getUID().getValue();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Fluent builder of a {@link FhirFieldMapping}; {@link #build()} returns a new copy per call. */
  public static final class Entry {
    private final FhirFieldMapping entry = new FhirFieldMapping();

    private Entry() {}

    public static Entry field(FhirTargetField target, FhirSourceType sourceType, String source) {
      Entry builder = new Entry();
      builder.entry.setTarget(target);
      builder.entry.setSourceType(sourceType);
      builder.entry.setSource(source);
      return builder;
    }

    /** Starts a {@link FhirSourceType#CONSTANT} entry with the coding and no source. */
    public static Entry constant(FhirTargetField t, String system, String code, String display) {
      return field(t, FhirSourceType.CONSTANT, null).system(system).code(code).display(display);
    }

    public Entry system(String system) {
      entry.setSystem(system);
      return this;
    }

    public Entry code(String code) {
      entry.setCode(code);
      return this;
    }

    public Entry display(String display) {
      entry.setDisplay(display);
      return this;
    }

    public Entry unit(String unit) {
      entry.setUnit(unit);
      return this;
    }

    /** Sets a copy of the value map in its iteration order, or {@code null}. */
    public Entry valueMap(Map<String, String> valueMap) {
      entry.setValueMap(valueMap == null ? null : new LinkedHashMap<>(valueMap));
      return this;
    }

    public FhirFieldMapping build() {
      return new FhirFieldMapping(entry);
    }
  }
}
