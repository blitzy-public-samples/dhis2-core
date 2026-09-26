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

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.Objects;
import java.util.function.*;
import java.util.stream.*;
import javax.annotation.CheckForNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.common.*;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.feedback.*;
import org.hisp.dhis.program.*;
import org.hisp.dhis.schema.SchemaService;
import org.hisp.dhis.trackedentity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers the {@link FhirResourceMapping} schema and resolves valid stored mappings. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FhirResourceMappingService {
  private final FhirResourceMappingStore store;
  private final FhirResourceMappingValidator validator;
  private final SchemaService schemaService;
  private final IdentifiableObjectManager manager;

  @PostConstruct
  void init() {
    schemaService.register(new FhirResourceMappingSchemaDescriptor());
  }

  /** Returns the valid, non-conflicting mappings of the type, ordered by UID. */
  @Transactional(readOnly = true)
  public List<ResolvedMapping> resolve(FhirResourceType type) {
    return guard(store.getByResourceTypeNoAcl(type));
  }

  /** Returns the valid, non-conflicting mappings of every resource type, ordered by UID. */
  @Transactional(readOnly = true)
  public List<ResolvedMapping> resolveAll() {
    return guard(store.getAllNoAcl());
  }

  private List<ResolvedMapping> guard(List<FhirResourceMapping> stored) {
    List<FhirResourceMapping> candidates = stored.stream().filter(Objects::nonNull).toList();
    BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> metadata =
        metadata(candidates);
    Map<String, List<FhirResourceMapping>> byKey = new LinkedHashMap<>();
    List<FhirResourceMapping> usable = new ArrayList<>();
    for (FhirResourceMapping mapping : candidates) {
      List<ErrorReport> reports = validator.validate(mapping, List.of(), metadata);
      if (!reports.isEmpty()) {
        logIgnored(
            escapedUids(List.of(mapping)),
            reports.stream().map(ErrorReport::getErrorCode).toList());
        continue;
      }
      String key = FhirResourceMappingValidator.uniquenessKey(mapping);
      if (key == null) {
        usable.add(mapping);
      } else {
        byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(mapping);
      }
    }
    byKey.forEach(
        (key, mappings) -> {
          if (mappings.size() == 1) {
            usable.add(mappings.get(0));
          } else {
            logIgnored(escapedUids(mappings), List.of(ErrorCode.E5003));
          }
        });
    return usable.stream()
        .map(mapping -> toResolved(mapping, metadata))
        .sorted(
            Comparator.comparing(
                ResolvedMapping::uid, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  void logIgnored(List<String> uids, List<ErrorCode> errorCodes) {
    log.warn("Ignoring FHIR resource mappings {} with error codes {}", uids, errorCodes);
  }

  private static List<String> escapedUids(List<FhirResourceMapping> mappings) {
    return mappings.stream().map(mapping -> escapeControlCharacters(mapping.getUid())).toList();
  }

  @CheckForNull
  static String escapeControlCharacters(@CheckForNull String value) {
    if (value == null) {
      return null;
    }
    StringBuilder escaped = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      int type = Character.getType(c);
      if (type == Character.CONTROL
          || type == Character.LINE_SEPARATOR
          || type == Character.PARAGRAPH_SEPARATOR) {
        escaped.append(String.format("\\u%04X", (int) c));
      } else {
        escaped.append(c);
      }
    }
    return escaped.toString();
  }

  private BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> metadata(
      List<FhirResourceMapping> mappings) {
    Map<Class<? extends IdentifiableObject>, Map<String, IdentifiableObject>> loaded =
        new HashMap<>();
    load(
        loaded,
        TrackedEntityType.class,
        TrackedEntityType::getUid,
        mappings.stream()
            .map(FhirResourceMapping::getTrackedEntityType)
            .filter(Objects::nonNull)
            .map(TrackedEntityType::getUid));
    load(
        loaded,
        Program.class,
        Program::getUid,
        mappings.stream()
            .map(FhirResourceMapping::getProgram)
            .filter(Objects::nonNull)
            .map(Program::getUid));
    load(
        loaded,
        ProgramStage.class,
        ProgramStage::getUid,
        mappings.stream()
            .map(FhirResourceMapping::getProgramStage)
            .filter(Objects::nonNull)
            .map(ProgramStage::getUid));
    load(
        loaded,
        TrackedEntityAttribute.class,
        TrackedEntityAttribute::getUid,
        sources(mappings, FhirSourceType.ATTRIBUTE));
    load(
        loaded,
        DataElement.class,
        DataElement::getUid,
        sources(mappings, FhirSourceType.DATA_ELEMENT));
    return (klass, uid) -> {
      Map<String, IdentifiableObject> byUid = loaded.get(klass);
      return byUid != null && byUid.containsKey(uid)
          ? byUid.get(uid)
          : manager.getNoAcl(klass, uid);
    };
  }

  private <T extends IdentifiableObject> void load(
      Map<Class<? extends IdentifiableObject>, Map<String, IdentifiableObject>> loaded,
      Class<T> klass,
      Function<T, String> uidOf,
      Stream<String> uids) {
    Set<String> requested = uids.filter(CodeGenerator::isValidUid).collect(Collectors.toSet());
    if (requested.isEmpty()) {
      return;
    }
    Map<String, IdentifiableObject> byUid = new HashMap<>();
    requested.forEach(uid -> byUid.put(uid, null));
    for (T object : manager.getNoAcl(klass, requested)) {
      if (object != null) {
        byUid.put(uidOf.apply(object), object);
      }
    }
    loaded.put(klass, byUid);
  }

  private static Stream<String> sources(
      List<FhirResourceMapping> mappings, FhirSourceType sourceType) {
    return mappings.stream()
        .map(FhirResourceMapping::getFieldMappings)
        .filter(Objects::nonNull)
        .flatMap(List::stream)
        .filter(entry -> entry != null && entry.getSourceType() == sourceType)
        .map(FhirFieldMapping::getSource);
  }

  private ResolvedMapping toResolved(
      FhirResourceMapping mapping,
      BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> metadata) {
    Map<String, ValueType> valueTypes = new LinkedHashMap<>();
    Map<String, Set<QueryOperator>> blockedSearchOperators = new LinkedHashMap<>();
    Map<String, Integer> minCharactersToSearch = new LinkedHashMap<>();
    for (FhirFieldMapping entry : mapping.getFieldMappings()) {
      String source = entry == null ? null : entry.getSource();
      if (source == null) {
        continue;
      }
      if (entry.getSourceType() == FhirSourceType.ATTRIBUTE) {
        if (metadata.apply(TrackedEntityAttribute.class, source)
            instanceof TrackedEntityAttribute attribute) {
          putValueType(valueTypes, source, attribute.getValueType());
          Set<QueryOperator> blocked = attribute.getBlockedSearchOperators();
          blockedSearchOperators.put(source, blocked == null ? Set.of() : blocked);
          minCharactersToSearch.put(source, attribute.getMinCharactersToSearch());
        }
      } else if (entry.getSourceType() == FhirSourceType.DATA_ELEMENT
          && metadata.apply(DataElement.class, source) instanceof DataElement dataElement) {
        putValueType(valueTypes, source, dataElement.getValueType());
      }
    }
    TrackedEntityType trackedEntityType = mapping.getTrackedEntityType();
    Program program = mapping.getProgram();
    ProgramStage programStage = mapping.getProgramStage();
    return new ResolvedMapping(
        mapping.getUid(),
        mapping.getResourceType(),
        trackedEntityType == null ? null : trackedEntityType.getUid(),
        program == null ? null : program.getUid(),
        programStage == null ? null : programStage.getUid(),
        mapping.getFieldMappings(),
        valueTypes,
        blockedSearchOperators,
        minCharactersToSearch,
        mapping.getLastUpdated() == null ? null : mapping.getLastUpdated().toInstant());
  }

  private static void putValueType(
      Map<String, ValueType> valueTypes, String source, @CheckForNull ValueType valueType) {
    if (valueType != null) {
      valueTypes.put(source, valueType);
    }
  }

  /** A validated, unmodifiable mapping detached from persistence, with UID references. */
  public record ResolvedMapping(
      String uid,
      FhirResourceType resourceType,
      String trackedEntityType,
      @CheckForNull String program,
      @CheckForNull String programStage,
      List<FhirFieldMapping> entries,
      Map<String, ValueType> valueTypes,
      Map<String, Set<QueryOperator>> blockedSearchOperators,
      Map<String, Integer> minCharactersToSearch,
      @CheckForNull Instant lastUpdated) {
    /** Replaces each collection and entry with an unmodifiable copy; {@code null} becomes empty. */
    public ResolvedMapping {
      entries =
          entries == null
              ? List.of()
              : entries.stream().map(FhirFieldMapping::unmodifiableCopy).toList();
      valueTypes = copy(valueTypes);
      Map<String, Set<QueryOperator>> operators = new LinkedHashMap<>();
      if (blockedSearchOperators != null) {
        blockedSearchOperators.forEach((teaUid, ops) -> operators.put(teaUid, copyOperators(ops)));
      }
      blockedSearchOperators = Collections.unmodifiableMap(operators);
      minCharactersToSearch = copy(minCharactersToSearch);
    }

    public List<FhirFieldMapping> entries(FhirTargetField target) {
      return entries.stream().filter(e -> e.getTarget() == target).toList();
    }

    /** Returns the first entry with the given target, in stored order. */
    public Optional<FhirFieldMapping> entry(FhirTargetField target) {
      return entries.stream().filter(e -> e.getTarget() == target).findFirst();
    }

    private static <V> Map<String, V> copy(@CheckForNull Map<String, V> map) {
      return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static Set<QueryOperator> copyOperators(@CheckForNull Set<QueryOperator> operators) {
      return operators == null || operators.isEmpty()
          ? Collections.unmodifiableSet(EnumSet.noneOf(QueryOperator.class))
          : Collections.unmodifiableSet(EnumSet.copyOf(operators));
    }
  }
}
