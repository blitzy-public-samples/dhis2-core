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

import static org.hisp.dhis.fhir.mapping.FhirTargetField.ENCOUNTER_CLASS;

import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import org.hisp.dhis.common.*;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.event.EventStatus;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.program.*;
import org.hisp.dhis.trackedentity.*;
import org.hisp.dhis.tracker.export.fieldfiltering.Fields;
import org.hisp.dhis.webapi.controller.tracker.view.*;

public final class FhirTestFixtures {
  public static final String TE = "QS6w44flWAf";
  public static final String TE_TYPE = "ja8NY4PW7Xm";
  public static final String PROGRAM = "BFcipDERJnf";
  public static final String STAGE = "NpsdDv6kKSO";
  public static final String ENR = "nxP7UnKhomJ";
  public static final String EVT = "pTzf9KYMk72";
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
  public static final String BODY_HEIGHT_UNIT = "cm";
  public static final String LOINC_BODY_HEIGHT_DISPLAY = "Body height";
  public static final String LOINC_BODY_WEIGHT_CODE = "29463-7";
  public static final String BODY_WEIGHT_UNIT = "kg";
  public static final String LOINC_BODY_WEIGHT_DISPLAY = "Body weight";
  public static final String IDENTIFIER_SYSTEM = "urn:dhis2:fhir-test:national-id";
  public static final Entry AMBULATORY =
      Entry.constant(
          ENCOUNTER_CLASS, ENCOUNTER_CLASS_SYSTEM, ENCOUNTER_CLASS_CODE, ENCOUNTER_CLASS_DISPLAY);

  private FhirTestFixtures() {}

  public static String uid() {
    return CodeGenerator.generateUid();
  }

  public static TrackedEntity trackedEntity(
      String uid, String type, Instant updatedAt, Attribute... attributes) {
    var te = TrackedEntity.builder().trackedEntity(UID.ofNullable(uid)).trackedEntityType(type);
    return te.updatedAt(updatedAt).attributes(new ArrayList<>(Arrays.asList(attributes))).build();
  }

  public static Attribute attribute(String uid, ValueType valueType, String value) {
    return Attribute.builder().attribute(uid).valueType(valueType).value(value).build();
  }

  public static Enrollment enrollment(String uid, String te, String program, Event... events) {
    var builder = Enrollment.builder().enrollment(UID.ofNullable(uid)).program(program);
    builder.trackedEntity(UID.ofNullable(te)).updatedAt(UPDATED);
    return builder.events(new ArrayList<>(Arrays.asList(events))).build();
  }

  public static Event event(
      String event,
      String stage,
      EventStatus status,
      Instant occurredAt,
      Instant scheduledAt,
      Instant updatedAt,
      DataValue... values) {
    var builder = Event.builder().event(UID.ofNullable(event)).programStage(stage).status(status);
    builder.occurredAt(occurredAt).scheduledAt(scheduledAt).updatedAt(updatedAt);
    return builder.dataValues(new HashSet<>(Arrays.asList(values))).build();
  }

  public static DataValue dataValue(String dataElement, String value) {
    return DataValue.builder().dataElement(dataElement).value(value).build();
  }

  @SafeVarargs
  public static <T> FilteredPage<T> page(T... items) {
    return new FilteredPage<>(Page.withoutPager("items", List.of(items)), Fields.all());
  }

  public static List<FhirFieldMapping> entries(Entry... entries) {
    return new ArrayList<>(Arrays.stream(entries).map(Entry::build).toList());
  }

  public static ResolvedMapping resolved(
      FhirResourceType type,
      String entityType,
      String program,
      String stage,
      List<FhirFieldMapping> entries,
      Map<String, ValueType> valueTypes) {
    return new ResolvedMapping(
        uid(), type, entityType, program, stage, entries, valueTypes, Map.of(), Map.of(), UPDATED);
  }

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

  public static TrackedEntityType trackedEntityType(String uid, TrackedEntityAttribute... attrs) {
    TrackedEntityType type = identified(new TrackedEntityType(), uid, "Type ");
    type.setShortName(type.getName());
    var members = Arrays.stream(attrs).map(a -> new TrackedEntityTypeAttribute(type, a));
    type.setTrackedEntityTypeAttributes(new ArrayList<>(members.toList()));
    return type;
  }

  public static DataElement dataElement(String uid, ValueType valueType) {
    DataElement dataElement = identified(new DataElement(), uid, "Data element ");
    dataElement.setShortName(dataElement.getName());
    dataElement.setValueType(valueType);
    return dataElement;
  }

  public static Program program(
      String uid, ProgramType kind, TrackedEntityType type, TrackedEntityAttribute... attributes) {
    Program program = identified(new Program(), uid, "Program ");
    program.setShortName(program.getName());
    program.setProgramType(kind);
    program.setTrackedEntityType(type);
    var members = Arrays.stream(attributes).map(a -> new ProgramTrackedEntityAttribute(program, a));
    program.setProgramAttributes(new ArrayList<>(members.toList()));
    return program;
  }

  public static ProgramStage programStage(String uid, Program program, DataElement... elements) {
    ProgramStage stage = identified(new ProgramStage(), uid, "Stage ");
    stage.setShortName(stage.getName());
    stage.setProgram(program);
    var members = Arrays.stream(elements).map(e -> new ProgramStageDataElement(stage, e));
    stage.setProgramStageDataElements(new HashSet<>(members.toList()));
    Optional.ofNullable(program).ifPresent(p -> p.getProgramStages().add(stage));
    return stage;
  }

  private static <T extends IdentifiableObject> T identified(T object, String uid, String prefix) {
    object.setUid(uid);
    object.setName(prefix + uid);
    object.setCreated(Date.from(UPDATED));
    object.setLastUpdated(Date.from(UPDATED));
    return object;
  }

  public static BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup(
      IdentifiableObject... objects) {
    List<IdentifiableObject> registered = new ArrayList<>(Arrays.asList(objects));
    return (klass, uid) -> {
      var found = registered.stream().filter(o -> klass != null && klass.isInstance(o));
      return found.filter(o -> UID.isValid(uid) && uid.equals(o.getUid())).findFirst().orElse(null);
    };
  }

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

    public Entry valueMap(Map<String, String> valueMap) {
      entry.setValueMap(valueMap == null ? null : new LinkedHashMap<>(valueMap));
      return this;
    }

    public FhirFieldMapping build() {
      return new FhirFieldMapping(entry);
    }
  }
}
