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

import java.net.*;
import java.util.*;
import java.util.Objects;
import java.util.function.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.CheckForNull;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.*;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.feedback.*;
import org.hisp.dhis.fhir.mapping.FhirTargetField.Cardinality;
import org.hisp.dhis.program.*;
import org.hisp.dhis.trackedentity.*;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates a {@link FhirResourceMapping}, returning one {@link ErrorReport} per violation in rule
 * order, each of class {@link FhirResourceMapping} with code E4000, E4010, E4014, E4027, E5002 or
 * E5003. A mapping over a size bound (500 entries, 100 pairs per value map, 1024 characters per
 * text, 100,000 in total) gets only E4027 reports and is not checked further. An entry whose target
 * or source type is missing, or not supported for the mapping, is not checked further. Membership
 * of an attribute, data element or stage is checked only when the metadata owning it resolves.
 */
@Component
@RequiredArgsConstructor
public class FhirResourceMappingValidator {
  public static final String OTHER_MAPPING = "another mapping";
  private static final int MAX_ENTRIES = 500;
  private static final int MAX_PAIRS = 100;
  private static final int MAX_TEXT = 1024;
  private static final int MAX_TOTAL = 100_000;
  private static final Set<String> GENDER_CODES =
      Arrays.stream(AdministrativeGender.values())
          .filter(gender -> gender != AdministrativeGender.NULL)
          .map(AdministrativeGender::toCode)
          .collect(Collectors.toUnmodifiableSet());
  private static final Pattern CODE =
      Pattern.compile("[^\\p{IsWhite_Space}\\p{Cc}]+(?: [^\\p{IsWhite_Space}\\p{Cc}]+)*");
  private static final Pattern URN_OID = Pattern.compile("urn:oid:[0-2](\\.(0|[1-9][0-9]*))+");
  private static final Pattern URN_UUID =
      Pattern.compile("urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private final IdentifiableObjectManager manager;

  /** Validates a mapping, resolving referenced metadata without applying sharing. */
  @Transactional(readOnly = true)
  public List<ErrorReport> validate(
      FhirResourceMapping mapping, Collection<FhirResourceMapping> others) {
    return validate(mapping, others, (klass, uid) -> manager.getNoAcl(klass, uid));
  }

  /**
   * Validates a mapping, resolving metadata through {@code lookup} and comparing its uniqueness key
   * with the nullable {@code others}, except the mapping itself and those with its UID.
   */
  @Transactional(readOnly = true)
  public List<ErrorReport> validate(
      FhirResourceMapping mapping,
      Collection<FhirResourceMapping> others,
      BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup) {
    return check(mapping, others, lookup);
  }

  /**
   * Validates a mapping by every rule that needs neither referenced metadata nor other mappings:
   * references are not resolved, and sources are checked only for presence and UID syntax.
   */
  public List<ErrorReport> validateStructure(FhirResourceMapping mapping) {
    return check(mapping, null, null);
  }

  private static List<ErrorReport> check(
      FhirResourceMapping mapping,
      @CheckForNull Collection<FhirResourceMapping> others,
      @CheckForNull
          BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup) {
    Check check = new Check(mapping, lookup);
    if (check.exceedsBounds()) {
      return check.reports;
    }
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
   * Returns the key at most one stored mapping may hold: {@code PATIENT}, {@code TYPE:stageUid} or
   * {@code IMMUNIZATION:stageUid:administeredDataElementUid}; {@code null} when a part is missing.
   */
  @CheckForNull
  public static String uniquenessKey(@CheckForNull FhirResourceMapping mapping) {
    FhirResourceType type = mapping == null ? null : mapping.getResourceType();
    if (type == null || type == FhirResourceType.PATIENT) {
      return type == null ? null : type.name();
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

  /** Returns whether a gender value map key and a TEA value have the same {@link #genderFold}. */
  public static boolean genderKeyMatches(String key, String value) {
    return genderFold(key).equals(genderFold(value));
  }

  /** Lower-cases each code point by {@link Character#toLowerCase(int)}, whatever the locale. */
  public static String genderFold(String value) {
    StringBuilder folded = new StringBuilder(value.length());
    value.codePoints().map(Character::toLowerCase).forEach(folded::appendCodePoint);
    return folded.toString();
  }

  private static List<FhirFieldMapping> fieldMappings(FhirResourceMapping mapping) {
    return mapping.getFieldMappings() == null ? List.of() : mapping.getFieldMappings();
  }

  private static Set<String> uids(@CheckForNull Collection<? extends IdentifiableObject> objects) {
    return objects == null
        ? Set.of()
        : objects.stream()
            .map(FhirResourceMappingValidator::uid)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
  }

  @CheckForNull
  static String uid(@CheckForNull IdentifiableObject object) {
    return object == null ? null : object.getUid();
  }

  private static boolean isBlank(@CheckForNull String value) {
    return value == null || value.isBlank();
  }

  private static boolean isValidSystem(String system) {
    try {
      return new URI(system).isAbsolute()
          && (!system.startsWith("urn:oid:") || URN_OID.matcher(system).matches())
          && (!system.startsWith("urn:uuid:") || URN_UUID.matcher(system).matches());
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static final class Check {
    private final FhirResourceMapping mapping;
    @CheckForNull private final FhirResourceType type;
    private final String id;

    @CheckForNull
    private final BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject>
        lookup;

    private final List<ErrorReport> reports = new ArrayList<>();
    @CheckForNull private Set<String> attributes;
    @CheckForNull private Set<String> dataElements;

    Check(
        FhirResourceMapping mapping,
        @CheckForNull
            BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup) {
      this.mapping = mapping;
      this.type = mapping.getResourceType();
      this.lookup = lookup;
      this.id = Objects.toString(mapping.getUid(), Objects.toString(mapping.getName(), ""));
    }

    void add(ErrorCode code, Object... args) {
      reports.add(new ErrorReport(FhirResourceMapping.class, code, args));
    }

    boolean exceedsBounds() {
      List<FhirFieldMapping> entries = fieldMappings(mapping);
      long total = 0;
      if (measure("fieldMappings", "size ", entries.size(), MAX_ENTRIES) <= MAX_ENTRIES) {
        for (FhirFieldMapping entry : entries) {
          total += entry == null || total > MAX_TOTAL ? 0 : textLength(entry);
        }
      }
      measure("fieldMappings", "length ", total, MAX_TOTAL);
      return !reports.isEmpty();
    }

    private long textLength(FhirFieldMapping entry) {
      long length = length("source", entry.getSource()) + length("system", entry.getSystem());
      length += length("code", entry.getCode()) + length("display", entry.getDisplay());
      length += length("unit", entry.getUnit());
      Map<String, String> pairs = entry.getValueMap();
      if (pairs != null && measure("valueMap", "size ", pairs.size(), MAX_PAIRS) <= MAX_PAIRS) {
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
          length += length("valueMap.key", pair.getKey());
          length += length("valueMap.value", pair.getValue());
        }
      }
      return length;
    }

    private long length(String property, @CheckForNull String text) {
      return measure(property, "length ", text == null ? 0 : text.length(), MAX_TEXT);
    }

    private long measure(String property, String measure, long actual, int max) {
      if (actual > max) {
        add(ErrorCode.E4027, measure + actual + " > " + max, property);
      }
      return actual;
    }

    private boolean eventDerived() {
      return type != null && type.isEventDerived();
    }

    @CheckForNull
    private IdentifiableObject find(
        Class<? extends IdentifiableObject> klass, @CheckForNull String uid) {
      return uid == null || lookup == null ? null : lookup.apply(klass, uid);
    }

    void validateReferences() {
      if (type == null) {
        add(ErrorCode.E4000, "resourceType");
      }
      String typeUid = uid(mapping.getTrackedEntityType());
      TrackedEntityType trackedEntityType =
          find(TrackedEntityType.class, typeUid) instanceof TrackedEntityType t ? t : null;
      if (mapping.getTrackedEntityType() == null) {
        add(ErrorCode.E4000, "trackedEntityType");
      } else if (trackedEntityType == null && lookup != null) {
        add(ErrorCode.E5002, typeUid, id, "trackedEntityType");
      }
      String programUid = uid(mapping.getProgram());
      Program program = find(Program.class, programUid) instanceof Program p ? p : null;
      if (mapping.getProgram() == null) {
        if (eventDerived()) {
          add(ErrorCode.E4000, "program");
        }
      } else if (program == null && lookup != null) {
        add(ErrorCode.E5002, programUid, id, "program");
      } else if (program != null) {
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
      if (stage == null ? lookup != null : program != null && !belongsTo(stage, program)) {
        add(ErrorCode.E5002, stageUid, id, "programStage");
      }
      return stage;
    }

    private static boolean belongsTo(ProgramStage stage, Program program) {
      return (program.getUid() != null && program.getUid().equals(uid(stage.getProgram())))
          || (program.getProgramStages() != null
              && program.getProgramStages().stream()
                  .anyMatch(s -> s != null && Objects.equals(s.getUid(), stage.getUid())));
    }

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
      if (!isBlank(entry.getCode()) && !CODE.matcher(entry.getCode()).matches()) {
        add(ErrorCode.E4027, entry.getCode(), "code");
      }
      if (!isBlank(entry.getSystem()) && !isValidSystem(entry.getSystem())) {
        add(ErrorCode.E4027, entry.getSystem(), "system");
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
        validateGenderKeys(entry.getValueMap());
      }
      return true;
    }

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
      if (lookup == null) {
        return;
      }
      boolean attribute = sourceType == FhirSourceType.ATTRIBUTE;
      Class<? extends IdentifiableObject> klass =
          attribute ? TrackedEntityAttribute.class : DataElement.class;
      IdentifiableObject found = find(klass, source);
      IdentifiableObject resolved = klass.isInstance(found) ? found : null;
      Set<String> allowed = attribute ? attributes : dataElements;
      if (resolved == null || (allowed != null && !allowed.contains(source))) {
        add(ErrorCode.E5002, source, id, target.name());
      }
      if (resolved instanceof ValueTypedDimensionalItemObject typed
          && !target.accepts(typed.getValueType())) {
        add(ErrorCode.E4027, String.valueOf(typed.getValueType()), target.name());
      }
    }

    /**
     * Reports as E4027 blank keys and, in a map of several keys, keys holding {@code ;}, and as
     * E5003 keys with the same {@link #genderFold} as an earlier key.
     */
    private void validateGenderKeys(Map<String, String> valueMap) {
      Set<String> folded = new HashSet<>();
      for (String key : valueMap.keySet()) {
        if (isBlank(key) || (valueMap.size() > 1 && key.contains(QueryFilter.OPTION_SEP))) {
          add(ErrorCode.E4027, key, "valueMap");
        } else if (!folded.add(genderFold(key))) {
          add(ErrorCode.E5003, "valueMap", key, id, id);
        }
      }
    }

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

    void validateUniqueness(@CheckForNull Collection<FhirResourceMapping> others) {
      String key = uniquenessKey(mapping);
      String self = mapping.getUid();
      if (key != null
          && others != null
          && others.stream()
              .filter(other -> other != mapping && (self == null || !self.equals(uid(other))))
              .anyMatch(other -> key.equals(uniquenessKey(other)))) {
        add(ErrorCode.E5003, "resourceType", key, id, OTHER_MAPPING);
      }
    }
  }
}
