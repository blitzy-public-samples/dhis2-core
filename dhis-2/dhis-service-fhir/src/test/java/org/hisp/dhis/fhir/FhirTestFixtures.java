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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.hisp.dhis.common.CodeGenerator;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.mapping.FhirFieldMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.mapping.FhirSourceType;
import org.hisp.dhis.fhir.mapping.FhirTargetField;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.program.ProgramStageDataElement;
import org.hisp.dhis.program.ProgramTrackedEntityAttribute;
import org.hisp.dhis.program.ProgramType;
import org.hisp.dhis.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.trackedentity.TrackedEntityType;
import org.hisp.dhis.trackedentity.TrackedEntityTypeAttribute;
import org.hisp.dhis.webapi.controller.tracker.view.Attribute;
import org.hisp.dhis.webapi.controller.tracker.view.DataValue;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.Event;
import org.hisp.dhis.webapi.controller.tracker.view.TrackedEntity;

/**
 * Deterministic builders for the unit tests of the FHIR module: Tracker view DTOs as the export
 * controllers return them, field mapping entries ({@link Entry}), {@link ResolvedMapping} records,
 * stored {@link FhirResourceMapping} objects, the Tracker metadata they reference, and a UID {@link
 * #lookup} over that metadata.
 *
 * <p>Every collection placed into a built object is a new mutable collection. Timestamps default to
 * {@link #UPDATED}, and {@code null} arguments are stored as given.
 */
public final class FhirTestFixtures {
  public static final Instant UPDATED = Instant.parse("2024-03-15T10:15:30Z");

  public static final Instant OCCURRED = Instant.parse("2024-03-10T09:00:00Z");

  public static final Instant SCHEDULED = Instant.parse("2024-04-10T09:00:00Z");

  /** HL7 v3 ActCode system with the ambulatory {@code Encounter.class} coding. */
  public static final String ENCOUNTER_CLASS_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/v3-ActCode";

  public static final String ENCOUNTER_CLASS_CODE = "AMB";

  public static final String ENCOUNTER_CLASS_DISPLAY = "ambulatory";

  /** CDC CVX vaccine code system with the measles, mumps and rubella vaccine coding. */
  public static final String CVX_SYSTEM = "http://hl7.org/fhir/sid/cvx";

  public static final String CVX_CODE = "03";

  public static final String CVX_DISPLAY = "MMR";

  /** LOINC code system with body height and body weight codings and their UCUM units. */
  public static final String LOINC_SYSTEM = "http://loinc.org";

  public static final String LOINC_BODY_HEIGHT_CODE = "8302-2";

  public static final String LOINC_BODY_HEIGHT_DISPLAY = "Body height";

  public static final String BODY_HEIGHT_UNIT = "cm";

  public static final String LOINC_BODY_WEIGHT_CODE = "29463-7";

  public static final String LOINC_BODY_WEIGHT_DISPLAY = "Body weight";

  public static final String BODY_WEIGHT_UNIT = "kg";

  /** A Patient identifier system. */
  public static final String IDENTIFIER_SYSTEM = "urn:dhis2:fhir-test:national-id";

  private FhirTestFixtures() {}

  /** Returns a new random, valid DHIS2 UID. */
  public static String uid() {
    return CodeGenerator.generateUid();
  }

  /** Builds a tracked entity DTO; a {@code null} UID stays {@code null}. */
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

  /** Builds an enrollment DTO updated at {@link #UPDATED}; {@code null} UIDs stay {@code null}. */
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

  /** Builds an event DTO with the data values in a mutable set; a {@code null} UID stays so. */
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

  /** Builds each entry and returns them in a mutable list, in order. */
  public static List<FhirFieldMapping> entries(Entry... entries) {
    return Arrays.stream(entries)
        .map(Entry::build)
        .collect(Collectors.toCollection(ArrayList::new));
  }

  /**
   * Builds a resolved mapping with a new UID, last updated at {@link #UPDATED}, without blocked
   * search operators or minimum search characters.
   */
  public static ResolvedMapping resolved(
      FhirResourceType type,
      String trackedEntityType,
      String program,
      String stage,
      List<FhirFieldMapping> entries,
      Map<String, ValueType> valueTypes) {
    return resolved(
        type, trackedEntityType, program, stage, entries, valueTypes, Map.of(), Map.of());
  }

  /** Builds a resolved mapping with a new UID, last updated at {@link #UPDATED}. */
  public static ResolvedMapping resolved(
      FhirResourceType type,
      String trackedEntityType,
      String program,
      String stage,
      List<FhirFieldMapping> entries,
      Map<String, ValueType> valueTypes,
      Map<String, Set<QueryOperator>> blockedSearchOperators,
      Map<String, Integer> minCharactersToSearch) {
    return resolved(
        uid(),
        UPDATED,
        type,
        trackedEntityType,
        program,
        stage,
        entries,
        valueTypes,
        blockedSearchOperators,
        minCharactersToSearch);
  }

  /** Builds a resolved mapping; the record stores unmodifiable copies of every collection. */
  public static ResolvedMapping resolved(
      String uid,
      Instant lastUpdated,
      FhirResourceType type,
      String trackedEntityType,
      String program,
      String stage,
      List<FhirFieldMapping> entries,
      Map<String, ValueType> valueTypes,
      Map<String, Set<QueryOperator>> blockedSearchOperators,
      Map<String, Integer> minCharactersToSearch) {
    return new ResolvedMapping(
        uid,
        type,
        trackedEntityType,
        program,
        stage,
        entries,
        valueTypes,
        blockedSearchOperators,
        minCharactersToSearch,
        lastUpdated);
  }

  /**
   * Builds a stored mapping named {@code "FHIR " + type + " " + uid}, created and last updated at
   * {@link #UPDATED}, with the entries in a mutable list; {@code null} entries are kept.
   */
  public static FhirResourceMapping mapping(
      String uid,
      FhirResourceType type,
      TrackedEntityType trackedEntityType,
      Program program,
      ProgramStage stage,
      FhirFieldMapping... entries) {
    FhirResourceMapping mapping = new FhirResourceMapping();
    mapping.setUid(uid);
    mapping.setName("FHIR " + type + " " + uid);
    mapping.setCreated(Date.from(UPDATED));
    mapping.setLastUpdated(Date.from(UPDATED));
    mapping.setResourceType(type);
    mapping.setTrackedEntityType(trackedEntityType);
    mapping.setProgram(program);
    mapping.setProgramStage(stage);
    mapping.setFieldMappings(new ArrayList<>(Arrays.asList(entries)));
    return mapping;
  }

  /** Builds a tracked entity attribute without search constraints. */
  public static TrackedEntityAttribute trackedEntityAttribute(String uid, ValueType valueType) {
    TrackedEntityAttribute attribute = new TrackedEntityAttribute();
    attribute.setUid(uid);
    attribute.setName("Attribute " + uid);
    attribute.setShortName("Attribute " + uid);
    attribute.setCreated(Date.from(UPDATED));
    attribute.setLastUpdated(Date.from(UPDATED));
    attribute.setValueType(valueType);
    return attribute;
  }

  /** Builds a tracked entity type with the attributes as a mutable type-attribute list. */
  public static TrackedEntityType trackedEntityType(
      String uid, TrackedEntityAttribute... attributes) {
    TrackedEntityType type = new TrackedEntityType();
    type.setUid(uid);
    type.setName("Type " + uid);
    type.setShortName("Type " + uid);
    type.setCreated(Date.from(UPDATED));
    type.setLastUpdated(Date.from(UPDATED));
    List<TrackedEntityTypeAttribute> typeAttributes = new ArrayList<>();
    for (TrackedEntityAttribute attribute : attributes) {
      typeAttributes.add(new TrackedEntityTypeAttribute(type, attribute));
    }
    type.setTrackedEntityTypeAttributes(typeAttributes);
    return type;
  }

  public static DataElement dataElement(String uid, ValueType valueType) {
    DataElement dataElement = new DataElement();
    dataElement.setUid(uid);
    dataElement.setName("Data element " + uid);
    dataElement.setShortName("Data element " + uid);
    dataElement.setCreated(Date.from(UPDATED));
    dataElement.setLastUpdated(Date.from(UPDATED));
    dataElement.setValueType(valueType);
    return dataElement;
  }

  /**
   * Builds a program with the attributes as a mutable program-attribute list and an empty mutable
   * set of program stages, which {@link #programStage} adds to.
   */
  public static Program program(
      String uid,
      ProgramType programType,
      TrackedEntityType type,
      TrackedEntityAttribute... programAttributes) {
    Program program = new Program();
    program.setUid(uid);
    program.setName("Program " + uid);
    program.setShortName("Program " + uid);
    program.setCreated(Date.from(UPDATED));
    program.setLastUpdated(Date.from(UPDATED));
    program.setProgramType(programType);
    program.setTrackedEntityType(type);
    List<ProgramTrackedEntityAttribute> attributes = new ArrayList<>();
    for (TrackedEntityAttribute attribute : programAttributes) {
      attributes.add(new ProgramTrackedEntityAttribute(program, attribute));
    }
    program.setProgramAttributes(attributes);
    program.setProgramStages(new HashSet<>());
    return program;
  }

  /**
   * Builds a program stage with the data elements as a mutable stage-data-element set, and adds it
   * to {@code program.getProgramStages()} unless {@code program} is {@code null}.
   */
  public static ProgramStage programStage(
      String uid, Program program, DataElement... dataElements) {
    ProgramStage stage = new ProgramStage();
    stage.setUid(uid);
    stage.setName("Stage " + uid);
    stage.setShortName("Stage " + uid);
    stage.setCreated(Date.from(UPDATED));
    stage.setLastUpdated(Date.from(UPDATED));
    stage.setProgram(program);
    Set<ProgramStageDataElement> stageDataElements = new HashSet<>();
    for (DataElement dataElement : dataElements) {
      stageDataElements.add(new ProgramStageDataElement(stage, dataElement));
    }
    stage.setProgramStageDataElements(stageDataElements);
    if (program != null) {
      if (program.getProgramStages() == null) {
        program.setProgramStages(new HashSet<>());
      }
      program.getProgramStages().add(stage);
    }
    return stage;
  }

  /**
   * Returns a lookup that answers the first given object with the requested UID that is an instance
   * of the requested class, or {@code null} when there is none or either argument is {@code null}.
   */
  public static BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup(
      IdentifiableObject... objects) {
    List<IdentifiableObject> registered = new ArrayList<>(Arrays.asList(objects));
    return (klass, uid) -> {
      if (klass == null || uid == null) {
        return null;
      }
      for (IdentifiableObject object : registered) {
        if (object != null && uid.equals(object.getUid()) && klass.isInstance(object)) {
          return object;
        }
      }
      return null;
    };
  }

  /**
   * Fluent builder of one {@link FhirFieldMapping}. Every property accepts {@code null}, and {@link
   * #build()} returns a new, independent entry on each call:
   *
   * <pre>{@code
   * Entry.field(PATIENT_IDENTIFIER, ATTRIBUTE, teaUid).system(IDENTIFIER_SYSTEM).build();
   * }</pre>
   */
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
    public static Entry constant(
        FhirTargetField target, String system, String code, String display) {
      return field(target, FhirSourceType.CONSTANT, null)
          .system(system)
          .code(code)
          .display(display);
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
