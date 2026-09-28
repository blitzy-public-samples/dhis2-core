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

import static org.hisp.dhis.fhir.mapping.FhirTargetField.IMMUNIZATION_ADMINISTERED;

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

/** Validates a {@link FhirResourceMapping}, returning one {@link ErrorReport} per violation. */
@Component
@RequiredArgsConstructor
public class FhirResourceMappingValidator {
  public static final String OTHER_MAPPING = "another " + FhirResourceMapping.class.getSimpleName();

  /** Properties besides the UID with unique non-null values, in reporting order, with getters. */
  public static final Map<String, Function<FhirResourceMapping, String>> UNIQUE_PROPERTIES =
      uniqueProperties();

  private static final Pattern BLANK_NAME = Pattern.compile("[\\p{IsWhite_Space}\\x{FEFF}]*");
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

  /** Validates by the rules needing no metadata or other mappings: name, size bounds, structure. */
  public List<ErrorReport> validateStructure(FhirResourceMapping mapping) {
    return validate(mapping, null, null);
  }

  /**
   * Validates with {@code lookup} metadata and the keys, names and codes of {@code others}. A blank
   * name is reported first; a mapping over a size bound gets only that and the name report.
   */
  @Transactional(readOnly = true)
  public List<ErrorReport> validate(
      FhirResourceMapping mapping,
      @CheckForNull Collection<FhirResourceMapping> others,
      @CheckForNull
          BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup) {
    Check check = new Check(mapping, lookup);
    check.validateName();
    if (check.exceedsBounds()) {
      return check.reports;
    }
    check.validateReferences();
    List<Integer> supported = new ArrayList<>();
    List<FhirFieldMapping> entries = fieldMappings(mapping);
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i) == null) {
        check.add(ErrorCode.E4000, row(i));
      } else if (check.validateEntry(i, entries.get(i))) {
        supported.add(i);
      }
    }
    if (mapping.getResourceType() != null) {
      check.validateRequiredTargets();
      check.validateIntraMappingUniqueness(supported);
    }
    check.validateUniqueness(others);
    return check.reports;
  }

  /** Returns the key at most one stored mapping may hold; {@code null} when a part is missing. */
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

  static String describe(FhirResourceMapping mapping) {
    String name = isBlank(mapping.getName()) ? "" : mapping.getName() + " ";
    String uid = isBlank(mapping.getUid()) ? "(new " : "[" + mapping.getUid() + "] (";
    return name + uid + FhirResourceMapping.class.getSimpleName() + ")";
  }

  static String row(int index) {
    return "fieldMappings[" + index + "]";
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

  private static boolean isBlankName(@CheckForNull String name) {
    return name == null || BLANK_NAME.matcher(name).matches();
  }

  private static Map<String, Function<FhirResourceMapping, String>> uniqueProperties() {
    Map<String, Function<FhirResourceMapping, String>> properties = new LinkedHashMap<>();
    properties.put("name", FhirResourceMapping::getName);
    properties.put("code", FhirResourceMapping::getCode);
    return Collections.unmodifiableMap(properties);
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
      this.id = describe(mapping);
    }

    void add(ErrorCode code, Object... args) {
      reports.add(new ErrorReport(FhirResourceMapping.class, code, args));
    }

    void validateName() {
      if (isBlankName(mapping.getName())) {
        add(ErrorCode.E4000, "name");
      }
    }

    boolean exceedsBounds() {
      int before = reports.size();
      List<FhirFieldMapping> entries = fieldMappings(mapping);
      long total = 0;
      if (measure("fieldMappings", "size ", entries.size(), MAX_ENTRIES) <= MAX_ENTRIES) {
        for (int i = 0; i < entries.size(); i++) {
          total += entries.get(i) == null || total > MAX_TOTAL ? 0 : textLength(i, entries.get(i));
        }
      }
      measure("fieldMappings", "length ", total, MAX_TOTAL);
      return reports.size() > before;
    }

    private long textLength(int i, FhirFieldMapping entry) {
      long length = length(i, "source", entry.getSource()) + length(i, "system", entry.getSystem());
      length += length(i, "code", entry.getCode()) + length(i, "display", entry.getDisplay());
      length += length(i, "unit", entry.getUnit());
      Map<String, String> pairs = entry.getValueMap();
      String map = row(i) + ".valueMap";
      if (pairs != null && measure(map, "size ", pairs.size(), MAX_PAIRS) <= MAX_PAIRS) {
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
          length += length(i, "valueMap.key", pair.getKey());
          length += length(i, "valueMap.value", pair.getValue());
        }
      }
      return length;
    }

    private long length(int i, String property, @CheckForNull String text) {
      return measure(
          row(i) + "." + property, "length ", text == null ? 0 : text.length(), MAX_TEXT);
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

    boolean validateEntry(int i, FhirFieldMapping entry) {
      FhirTargetField target = entry.getTarget();
      FhirSourceType sourceType = entry.getSourceType();
      if (target == null) {
        add(ErrorCode.E4000, row(i) + ".target");
      }
      if (sourceType == null) {
        add(ErrorCode.E4000, row(i) + ".sourceType");
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
        add(ErrorCode.E4027, entry.getCode(), row(i) + ".code");
      }
      if (!isBlank(entry.getSystem()) && !isValidSystem(entry.getSystem())) {
        add(ErrorCode.E4027, entry.getSystem(), row(i) + ".system");
      }
      if (sourceType == FhirSourceType.CONSTANT) {
        if (isBlank(entry.getCode())) {
          add(ErrorCode.E4000, row(i) + ".code");
        }
      } else {
        validateSource(row(i) + ".source", target, sourceType, entry.getSource());
      }
      if (target == FhirTargetField.PATIENT_IDENTIFIER && isBlank(entry.getSystem())) {
        add(ErrorCode.E4000, row(i) + ".system");
      }
      if (target == FhirTargetField.OBSERVATION_VALUE && isBlank(entry.getCode())) {
        add(ErrorCode.E4000, row(i) + ".code");
      }
      if (target == FhirTargetField.PATIENT_GENDER && entry.getValueMap() != null) {
        for (String value : entry.getValueMap().values()) {
          if (value == null || !GENDER_CODES.contains(value)) {
            add(ErrorCode.E4027, value, row(i) + ".valueMap");
          }
        }
        validateGenderKeys(i, entry.getValueMap());
      }
      return true;
    }

    private void validateSource(
        String path, FhirTargetField target, FhirSourceType kind, @CheckForNull String source) {
      if (isBlank(source)) {
        add(ErrorCode.E4000, path);
        return;
      }
      if (!CodeGenerator.isValidUid(source)) {
        add(ErrorCode.E4014, source, path);
        return;
      }
      if (lookup == null) {
        return;
      }
      boolean attribute = kind == FhirSourceType.ATTRIBUTE;
      Class<? extends IdentifiableObject> klass =
          attribute ? TrackedEntityAttribute.class : DataElement.class;
      IdentifiableObject found = find(klass, source);
      IdentifiableObject resolved = klass.isInstance(found) ? found : null;
      Set<String> allowed = attribute ? attributes : dataElements;
      if (resolved == null || (allowed != null && !allowed.contains(source))) {
        add(ErrorCode.E5002, source, id, path);
      }
      if (resolved instanceof ValueTypedDimensionalItemObject typed
          && !target.accepts(typed.getValueType())) {
        add(ErrorCode.E4027, String.valueOf(typed.getValueType()), target.name());
      }
    }

    private void validateGenderKeys(int i, Map<String, String> valueMap) {
      Map<String, String> folded = new HashMap<>();
      for (String key : valueMap.keySet()) {
        if (isBlank(key) || (valueMap.size() > 1 && key.contains(QueryFilter.OPTION_SEP))) {
          add(ErrorCode.E4027, key, row(i) + ".valueMap");
          continue;
        }
        String earlier = folded.putIfAbsent(genderFold(key), key);
        if (earlier != null) {
          add(ErrorCode.E5003, "valueMap", key, row(i), row(i) + " key `" + earlier + "`");
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

    void validateIntraMappingUniqueness(List<Integer> supported) {
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
        List<Integer> rows, String property, Function<FhirFieldMapping, String> valueOf) {
      Map<String, Integer> first = new HashMap<>();
      for (int i : rows) {
        String value = valueOf.apply(fieldMappings(mapping).get(i));
        Integer earlier = isBlank(value) ? null : first.putIfAbsent(value, i);
        if (earlier != null) {
          add(ErrorCode.E5003, property, value, row(i), row(earlier));
        }
      }
    }

    void validateUniqueness(@CheckForNull Collection<FhirResourceMapping> others) {
      if (others == null) {
        return;
      }
      String self = mapping.getUid();
      List<FhirResourceMapping> distinct =
          others.stream()
              .filter(other -> other != null && other != mapping)
              .filter(other -> self == null || !self.equals(uid(other)))
              .toList();
      String key = uniquenessKey(mapping);
      if (key != null && distinct.stream().anyMatch(other -> key.equals(uniquenessKey(other)))) {
        String part = type == FhirResourceType.IMMUNIZATION ? ", " + IMMUNIZATION_ADMINISTERED : "";
        String property = type == FhirResourceType.PATIENT ? "resourceType" : "programStage" + part;
        String value = key.substring(key.indexOf(':') + 1).replace(":", ", ");
        add(ErrorCode.E5003, property, value, id, OTHER_MAPPING);
      }
      UNIQUE_PROPERTIES.forEach(
          (property, valueOf) -> {
            String value = valueOf.apply(mapping);
            boolean compared = value != null && !(property.equals("name") && isBlankName(value));
            if (compared
                && distinct.stream().anyMatch(other -> value.equals(valueOf.apply(other)))) {
              add(ErrorCode.E5003, property, value, id, OTHER_MAPPING);
            }
          });
    }
  }
}
