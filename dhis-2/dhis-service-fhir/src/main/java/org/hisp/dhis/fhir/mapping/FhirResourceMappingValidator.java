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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.CodeGenerator;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.feedback.ErrorCode;
import org.hisp.dhis.feedback.ErrorReport;
import org.hisp.dhis.fhir.mapping.FhirTargetField.Cardinality;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.program.ProgramType;
import org.hisp.dhis.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.trackedentity.TrackedEntityType;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates a {@link FhirResourceMapping} against the FHIR mapping rules and returns one {@link
 * ErrorReport} per violation, in rule order: the resource type and the tracked entity type, program
 * and program stage references; each field mapping entry; the required targets of the resource
 * type; repeated targets, identifier systems and sources within the mapping; and the {@link
 * #uniquenessKey(FhirResourceMapping) uniqueness key} against other mappings.
 *
 * <p>An entry without a target or source type, or with a target or source type the mapping does not
 * support, is not checked further. Membership of an attribute, data element or program stage is
 * only checked when the metadata that owns it resolves. References are resolved by UID, so they may
 * carry nothing but a UID. Nothing passed in is modified. Reports use only the error codes E4000,
 * E4010, E4014, E4027, E5002 and E5003, with {@link FhirResourceMapping} as their class.
 */
@Component
@RequiredArgsConstructor
public class FhirResourceMappingValidator {
  private static final Set<String> GENDER_CODES =
      Arrays.stream(AdministrativeGender.values())
          .filter(gender -> gender != AdministrativeGender.NULL)
          .map(AdministrativeGender::toCode)
          .collect(Collectors.toUnmodifiableSet());

  private final IdentifiableObjectManager manager;

  /**
   * Validates a mapping, resolving referenced metadata without applying the current user's sharing.
   *
   * @param mapping the mapping to validate, never {@code null}
   * @param others the mappings its uniqueness key is compared with; {@code mapping} itself and
   *     mappings with its UID are ignored, {@code null} means none
   * @return a new mutable list with one report per violation; empty when the mapping is valid
   */
  @Transactional(readOnly = true)
  public List<ErrorReport> validate(
      FhirResourceMapping mapping, Collection<FhirResourceMapping> others) {
    return validate(mapping, others, (klass, uid) -> manager.getNoAcl(klass, uid));
  }

  /**
   * Validates a mapping, resolving referenced metadata through the given lookup.
   *
   * @param mapping the mapping to validate, never {@code null}
   * @param others the mappings its uniqueness key is compared with; {@code mapping} itself and
   *     mappings with its UID are ignored, {@code null} means none
   * @param lookup returns the object of the given class with the given UID, or {@code null} when
   *     none exists; it is only called with non-null UIDs
   * @return a new mutable list with one report per violation; empty when the mapping is valid
   */
  @Transactional(readOnly = true)
  public List<ErrorReport> validate(
      FhirResourceMapping mapping,
      Collection<FhirResourceMapping> others,
      BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup) {
    Check check = new Check(mapping, lookup);
    check.validateReferences();
    List<FhirFieldMapping> supported = new ArrayList<>();
    for (FhirFieldMapping entry : fieldMappings(mapping)) {
      if (entry == null) {
        check.add(ErrorCode.E4000, "fieldMappings");
      } else if (check.validateEntry(entry)) {
        supported.add(entry);
      }
    }
    if (mapping.getResourceType() != null) {
      check.validateRequiredTargets();
      check.validateIntraMappingUniqueness(supported);
    }
    check.validateUniqueness(others);
    return check.reports;
  }

  /**
   * Returns the key that at most one stored mapping may hold: {@code PATIENT}; {@code
   * ENCOUNTER:{programStageUid}}; {@code OBSERVATION:{programStageUid}}; or {@code
   * IMMUNIZATION:{programStageUid}:{dataElementUid}}, where the data element is the source of the
   * first {@link FhirTargetField#IMMUNIZATION_ADMINISTERED} entry.
   *
   * @param mapping the mapping, may be {@code null} or incomplete
   * @return the key, or {@code null} when the resource type, the program stage UID or the
   *     administered data element is missing
   */
  @CheckForNull
  public static String uniquenessKey(@CheckForNull FhirResourceMapping mapping) {
    FhirResourceType type = mapping == null ? null : mapping.getResourceType();
    if (type == null) {
      return null;
    }
    if (type == FhirResourceType.PATIENT) {
      return type.name();
    }
    String stage = uid(mapping.getProgramStage());
    if (stage == null) {
      return null;
    }
    if (type != FhirResourceType.IMMUNIZATION) {
      return type.name() + ":" + stage;
    }
    String administered =
        fieldMappings(mapping).stream()
            .filter(e -> e != null && e.getTarget() == FhirTargetField.IMMUNIZATION_ADMINISTERED)
            .findFirst()
            .map(FhirFieldMapping::getSource)
            .orElse(null);
    return isBlank(administered) ? null : type.name() + ":" + stage + ":" + administered;
  }

  private static List<FhirFieldMapping> fieldMappings(FhirResourceMapping mapping) {
    return mapping.getFieldMappings() == null ? List.of() : mapping.getFieldMappings();
  }

  private static Set<String> uids(@CheckForNull Collection<? extends IdentifiableObject> objects) {
    return objects == null
        ? Set.of()
        : objects.stream()
            .filter(Objects::nonNull)
            .map(IdentifiableObject::getUid)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
  }

  @CheckForNull
  private static String uid(@CheckForNull IdentifiableObject object) {
    return object == null ? null : object.getUid();
  }

  /** Returns the mapping's UID, or its name when it has no UID, or the empty string. */
  private static String id(FhirResourceMapping mapping) {
    if (mapping.getUid() != null) {
      return mapping.getUid();
    }
    return mapping.getName() == null ? "" : mapping.getName();
  }

  private static boolean isBlank(@CheckForNull String value) {
    return value == null || value.isBlank();
  }

  /** The checks of one validation call and the reports they produce. */
  private static final class Check {
    private final FhirResourceMapping mapping;

    @CheckForNull private final FhirResourceType type;

    private final String id;

    private final BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject>
        lookup;

    private final List<ErrorReport> reports = new ArrayList<>();

    /** UIDs of the attributes entries may use; {@code null} when their owners did not resolve. */
    @CheckForNull private Set<String> attributes;

    /** UIDs of the data elements entries may use; {@code null} when the stage did not resolve. */
    @CheckForNull private Set<String> dataElements;

    Check(
        FhirResourceMapping mapping,
        BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup) {
      this.mapping = mapping;
      this.type = mapping.getResourceType();
      this.lookup = lookup;
      this.id = id(mapping);
    }

    void add(ErrorCode code, Object... args) {
      reports.add(new ErrorReport(FhirResourceMapping.class, code, args));
    }

    private boolean eventDerived() {
      return type != null && type.isEventDerived();
    }

    @CheckForNull
    private IdentifiableObject find(
        Class<? extends IdentifiableObject> klass, @CheckForNull String uid) {
      return uid == null ? null : lookup.apply(klass, uid);
    }

    /** Checks the resource type and the tracked entity type, program and stage references. */
    void validateReferences() {
      if (type == null) {
        add(ErrorCode.E4000, "resourceType");
      }

      String typeUid = uid(mapping.getTrackedEntityType());
      TrackedEntityType trackedEntityType =
          find(TrackedEntityType.class, typeUid) instanceof TrackedEntityType t ? t : null;
      if (mapping.getTrackedEntityType() == null) {
        add(ErrorCode.E4000, "trackedEntityType");
      } else if (trackedEntityType == null) {
        add(ErrorCode.E5002, typeUid, id, "trackedEntityType");
      }

      String programUid = uid(mapping.getProgram());
      Program program = find(Program.class, programUid) instanceof Program p ? p : null;
      if (mapping.getProgram() == null) {
        if (eventDerived()) {
          add(ErrorCode.E4000, "program");
        }
      } else if (program == null) {
        add(ErrorCode.E5002, programUid, id, "program");
      } else {
        if (program.getProgramType() != ProgramType.WITH_REGISTRATION) {
          add(ErrorCode.E5002, programUid, id, "program");
        }
        if (typeUid != null && !typeUid.equals(uid(program.getTrackedEntityType()))) {
          add(ErrorCode.E5002, programUid, id, "trackedEntityType");
        }
      }

      ProgramStage stage = validateProgramStage(program);

      if (trackedEntityType != null && (mapping.getProgram() == null || program != null)) {
        attributes = new HashSet<>();
        if (trackedEntityType.getTrackedEntityTypeAttributes() != null) {
          attributes.addAll(uids(trackedEntityType.getTrackedEntityAttributes()));
        }
        if (program != null && program.getProgramAttributes() != null) {
          attributes.addAll(uids(program.getTrackedEntityAttributes()));
        }
      }
      if (stage != null) {
        dataElements =
            stage.getProgramStageDataElements() == null ? Set.of() : uids(stage.getDataElements());
      }
    }

    /**
     * Checks the program stage reference and returns the resolved stage, or {@code null} when it is
     * absent, not allowed for the resource type or unresolvable.
     */
    @CheckForNull
    private ProgramStage validateProgramStage(@CheckForNull Program program) {
      String stageUid = uid(mapping.getProgramStage());
      if (mapping.getProgramStage() == null) {
        if (eventDerived()) {
          add(ErrorCode.E4000, "programStage");
        }
        return null;
      }
      if (type == FhirResourceType.PATIENT) {
        add(ErrorCode.E5002, stageUid, id, "programStage");
        return null;
      }
      ProgramStage stage = find(ProgramStage.class, stageUid) instanceof ProgramStage s ? s : null;
      if (stage == null || (program != null && !belongsTo(stage, program))) {
        add(ErrorCode.E5002, stageUid, id, "programStage");
      }
      return stage;
    }

    private static boolean belongsTo(ProgramStage stage, Program program) {
      if (program.getUid() != null && program.getUid().equals(uid(stage.getProgram()))) {
        return true;
      }
      return program.getProgramStages() != null
          && program.getProgramStages().stream()
              .anyMatch(s -> s != null && Objects.equals(s.getUid(), stage.getUid()));
    }

    /**
     * Checks one non-null entry and returns whether its target and source type are supported for
     * the mapping. A missing gender code in the value map is reported as an invalid code.
     */
    boolean validateEntry(FhirFieldMapping entry) {
      FhirTargetField target = entry.getTarget();
      FhirSourceType sourceType = entry.getSourceType();
      if (target == null) {
        add(ErrorCode.E4000, "target");
      }
      if (sourceType == null) {
        add(ErrorCode.E4000, "sourceType");
      }
      if (target == null || sourceType == null) {
        return false;
      }

      boolean supported = true;
      if (type != null && target.resourceType() != type) {
        add(ErrorCode.E4010, target.name(), type.name());
        supported = false;
      }
      if (!target.allowedSources().contains(sourceType)) {
        add(ErrorCode.E4010, sourceType.name(), target.name());
        supported = false;
      }
      if (!supported) {
        return false;
      }

      if (sourceType == FhirSourceType.CONSTANT) {
        if (isBlank(entry.getCode())) {
          add(ErrorCode.E4000, "code");
        }
      } else {
        validateSource(target, sourceType, entry.getSource());
      }
      if (target == FhirTargetField.PATIENT_IDENTIFIER && isBlank(entry.getSystem())) {
        add(ErrorCode.E4000, "system");
      }
      if (target == FhirTargetField.OBSERVATION_VALUE && isBlank(entry.getCode())) {
        add(ErrorCode.E4000, "code");
      }
      if (target == FhirTargetField.PATIENT_GENDER && entry.getValueMap() != null) {
        for (String value : entry.getValueMap().values()) {
          if (value == null || !GENDER_CODES.contains(value)) {
            add(ErrorCode.E4027, value, "valueMap");
          }
        }
      }
      return true;
    }

    /**
     * Checks the attribute or data element source of an entry and, whenever it resolves, its value
     * type, also for a source outside the allowed attributes or data elements.
     */
    private void validateSource(
        FhirTargetField target, FhirSourceType sourceType, @CheckForNull String source) {
      if (isBlank(source)) {
        add(ErrorCode.E4000, "source");
        return;
      }
      if (!CodeGenerator.isValidUid(source)) {
        add(ErrorCode.E4014, source, "source");
        return;
      }

      boolean attribute = sourceType == FhirSourceType.ATTRIBUTE;
      IdentifiableObject found =
          find(attribute ? TrackedEntityAttribute.class : DataElement.class, source);
      IdentifiableObject resolved =
          (attribute && found instanceof TrackedEntityAttribute)
                  || (!attribute && found instanceof DataElement)
              ? found
              : null;
      Set<String> allowed = attribute ? attributes : dataElements;
      if (resolved == null || (allowed != null && !allowed.contains(source))) {
        add(ErrorCode.E5002, source, id, target.name());
      }

      ValueType valueType = null;
      if (resolved instanceof TrackedEntityAttribute trackedEntityAttribute) {
        valueType = trackedEntityAttribute.getValueType();
      } else if (resolved instanceof DataElement dataElement) {
        valueType = dataElement.getValueType();
      }
      if (resolved != null && !target.accepts(valueType)) {
        add(ErrorCode.E4027, String.valueOf(valueType), target.name());
      }
    }

    /** Reports every required target of the resource type that no entry has. */
    void validateRequiredTargets() {
      Set<FhirTargetField> present =
          fieldMappings(mapping).stream()
              .filter(Objects::nonNull)
              .map(FhirFieldMapping::getTarget)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet());
      for (FhirTargetField target : FhirTargetField.forResourceType(type)) {
        if (target.isRequired() && !present.contains(target)) {
          add(ErrorCode.E4000, target.name());
        }
      }
    }

    /**
     * Reports, once per value and in this order, repeated single-entry targets, identifier systems
     * and sources (Patient attributes and observation data elements) among the supported entries.
     */
    void validateIntraMappingUniqueness(List<FhirFieldMapping> supported) {
      reportRepeated(
          supported,
          "target",
          e -> e.getTarget().cardinality() == Cardinality.ONE ? e.getTarget().name() : null);
      reportRepeated(
          supported,
          "system",
          e -> e.getTarget() == FhirTargetField.PATIENT_IDENTIFIER ? e.getSystem() : null);
      reportRepeated(
          supported,
          "source",
          e ->
              (type == FhirResourceType.PATIENT && e.getSourceType() == FhirSourceType.ATTRIBUTE)
                      || e.getTarget() == FhirTargetField.OBSERVATION_VALUE
                  ? e.getSource()
                  : null);
    }

    /** Reports, once per value, each non-blank value that more than one entry yields. */
    private void reportRepeated(
        List<FhirFieldMapping> entries,
        String property,
        Function<FhirFieldMapping, String> valueOf) {
      Set<String> seen = new HashSet<>();
      Set<String> repeated = new LinkedHashSet<>();
      for (FhirFieldMapping entry : entries) {
        String value = valueOf.apply(entry);
        if (!isBlank(value) && !seen.add(value)) {
          repeated.add(value);
        }
      }
      repeated.forEach(value -> add(ErrorCode.E5003, property, value, id, id));
    }

    /**
     * Reports the first other mapping that holds the mapping's uniqueness key, naming it by UID, or
     * by name when it has no UID.
     */
    void validateUniqueness(@CheckForNull Collection<FhirResourceMapping> others) {
      String key = uniquenessKey(mapping);
      if (key == null || others == null) {
        return;
      }
      for (FhirResourceMapping other : others) {
        if (other == null
            || other == mapping
            || (mapping.getUid() != null && mapping.getUid().equals(other.getUid()))) {
          continue;
        }
        if (key.equals(uniquenessKey(other))) {
          add(ErrorCode.E5003, "resourceType", key, id, id(other));
          return;
        }
      }
    }
  }
}
