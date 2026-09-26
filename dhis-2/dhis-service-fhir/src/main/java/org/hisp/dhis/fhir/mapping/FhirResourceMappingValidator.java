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
import java.util.Locale;
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

/** Validates a {@link FhirResourceMapping}, returning one {@link ErrorReport} per violation. */
@Component
@RequiredArgsConstructor
public class FhirResourceMappingValidator {
  public static final int MAX_FIELD_MAPPINGS = 500;
  public static final int MAX_VALUE_MAP_SIZE = 100;
  public static final int MAX_TEXT_LENGTH = 1024;
  public static final int MAX_TOTAL_TEXT_LENGTH = 100_000;
  public static final String OTHER_MAPPING = "another mapping";
  private static final Set<String> GENDER_CODES =
      Arrays.stream(AdministrativeGender.values())
          .filter(gender -> gender != AdministrativeGender.NULL)
          .map(AdministrativeGender::toCode)
          .collect(Collectors.toUnmodifiableSet());
  private static final Pattern CODE =
      Pattern.compile("[^\\p{IsWhite_Space}\\p{Cc}]+(?: [^\\p{IsWhite_Space}\\p{Cc}]+)*");
  private static final Pattern OID = Pattern.compile("[0-2](\\.(0|[1-9][0-9]*))+");
  private static final Pattern LOWERCASE_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
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
    Check check = new Check(mapping, lookup);
    check.validateReferences();
    List<FhirFieldMapping> supported = new ArrayList<>();
    List<FhirFieldMapping> entries = fieldMappings(mapping);
    long textLength = textLength(entries);
    if (entries.size() > MAX_FIELD_MAPPINGS) {
      check.addTooLong("fieldMappings", MAX_FIELD_MAPPINGS, entries.size());
    } else if (textLength > MAX_TOTAL_TEXT_LENGTH) {
      check.addTooLong("fieldMappings.text", MAX_TOTAL_TEXT_LENGTH, textLength);
    } else {
      for (FhirFieldMapping entry : entries) {
        if (entry == null) {
          check.add(ErrorCode.E4000, "fieldMappings");
        } else if (check.validateEntry(entry)) {
          supported.add(entry);
        }
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

  /**
   * Returns whether a gender value map key lower-cased in the default locale equals a stored value
   * lower-cased per code point.
   */
  public static boolean genderKeyMatches(String key, String value) {
    return keyCase(key).equals(valueCase(value));
  }

  private static String keyCase(String key) {
    return key.toLowerCase(Locale.getDefault());
  }

  private static String valueCase(String value) {
    StringBuilder lowerCase = new StringBuilder(value.length());
    value.codePoints().map(Character::toLowerCase).forEach(lowerCase::appendCodePoint);
    return lowerCase.toString();
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

  private static String id(FhirResourceMapping mapping) {
    if (mapping.getUid() != null) {
      return mapping.getUid();
    }
    return mapping.getName() == null ? "" : mapping.getName();
  }

  private static boolean isBlank(@CheckForNull String value) {
    return value == null || value.isBlank();
  }

  private static long textLength(List<FhirFieldMapping> entries) {
    long length = 0;
    for (FhirFieldMapping entry : entries) {
      if (entry == null) {
        continue;
      }
      length += length(entry.getSource()) + length(entry.getSystem()) + length(entry.getCode());
      length += length(entry.getDisplay()) + length(entry.getUnit());
      if (entry.getValueMap() != null) {
        for (Map.Entry<String, String> pair : entry.getValueMap().entrySet()) {
          length += length(pair.getKey()) + length(pair.getValue());
        }
      }
    }
    return length;
  }

  private static int length(@CheckForNull String value) {
    return value == null ? 0 : value.length();
  }

  private static boolean isValidSystem(String system, boolean identifier) {
    URI uri;
    try {
      uri = new URI(system);
    } catch (URISyntaxException e) {
      return false;
    }
    String scheme = uri.getScheme();
    if ("http".equals(scheme) || "https".equals(scheme)) {
      return !isBlank(uri.getRawAuthority());
    }
    if (identifier && "ldap".equals(scheme)) {
      return true;
    }
    if (!"urn".equals(scheme)) {
      return false;
    }
    if (system.startsWith("urn:oid:")) {
      String oid = system.substring("urn:oid:".length());
      return OID.matcher(oid).matches() && (oid.lastIndexOf('.') >= 4 || oid.startsWith("1.3"));
    }
    return !system.startsWith("urn:uuid:")
        || LOWERCASE_UUID.matcher(system.substring("urn:uuid:".length())).matches();
  }

  private static final class Check {
    private final FhirResourceMapping mapping;
    @CheckForNull private final FhirResourceType type;
    private final String id;
    private final BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject>
        lookup;
    private final List<ErrorReport> reports = new ArrayList<>();
    @CheckForNull private Set<String> attributes;
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

    void addTooLong(String property, int max, long actual) {
      add(ErrorCode.E4001, property, String.valueOf(max), String.valueOf(actual));
    }

    private void checkLength(String property, @CheckForNull String value) {
      if (value != null && value.length() > MAX_TEXT_LENGTH) {
        addTooLong(property, MAX_TEXT_LENGTH, value.length());
      }
    }

    private boolean exceedsBounds(FhirFieldMapping entry) {
      int before = reports.size();
      checkLength("source", entry.getSource());
      checkLength("system", entry.getSystem());
      checkLength("code", entry.getCode());
      checkLength("display", entry.getDisplay());
      checkLength("unit", entry.getUnit());
      Map<String, String> valueMap = entry.getValueMap();
      if (valueMap != null && valueMap.size() > MAX_VALUE_MAP_SIZE) {
        addTooLong("valueMap", MAX_VALUE_MAP_SIZE, valueMap.size());
      } else if (valueMap != null) {
        valueMap.forEach(
            (key, value) -> {
              checkLength("valueMap.key", key);
              checkLength("valueMap.value", value);
            });
      }
      return reports.size() > before;
    }

    private boolean eventDerived() {
      return type != null && type.isEventDerived();
    }

    @CheckForNull
    private IdentifiableObject find(
        Class<? extends IdentifiableObject> klass, @CheckForNull String uid) {
      return uid == null ? null : lookup.apply(klass, uid);
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
      if (exceedsBounds(entry)) {
        return true;
      }
      if (!isBlank(entry.getCode()) && !CODE.matcher(entry.getCode()).matches()) {
        add(ErrorCode.E4027, entry.getCode(), "code");
      }
      if (!isBlank(entry.getSystem())
          && !isValidSystem(entry.getSystem(), target == FhirTargetField.PATIENT_IDENTIFIER)) {
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

    private void validateGenderKeys(Map<String, String> valueMap) {
      Set<String> folded = new HashSet<>();
      for (String key : valueMap.keySet()) {
        if (isBlank(key) || key.contains(QueryFilter.OPTION_SEP) || !genderKeyMatches(key, key)) {
          add(ErrorCode.E4027, key, "valueMap");
        } else if (!folded.add(keyCase(key))) {
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
        if (!isBlank(value) && value.length() <= MAX_TEXT_LENGTH && !seen.add(value)) {
          repeated.add(value);
        }
      }
      repeated.forEach(value -> add(ErrorCode.E5003, property, value, id, id));
    }

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
          add(ErrorCode.E5003, "resourceType", key, id, OTHER_MAPPING);
          return;
        }
      }
    }
  }
}
