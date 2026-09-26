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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.CheckForNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.QueryOperator;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.feedback.ErrorReport;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.schema.SchemaService;
import org.hisp.dhis.trackedentity.TrackedEntityAttribute;
import org.hisp.dhis.trackedentity.TrackedEntityType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers the {@link FhirResourceMapping} schema and resolves stored mappings into detached,
 * validated {@link ResolvedMapping} records.
 *
 * <p>Mappings and the metadata they reference are read without applying the current user's sharing.
 * A stored mapping is resolved only when it passes {@link FhirResourceMappingValidator} and no
 * other valid mapping resolved with it holds its {@link
 * FhirResourceMappingValidator#uniquenessKey(FhirResourceMapping) uniqueness key}; every other
 * mapping is ignored and logged at {@code WARN}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FhirResourceMappingService {
  private final FhirResourceMappingStore store;

  private final FhirResourceMappingValidator validator;

  private final SchemaService schemaService;

  private final IdentifiableObjectManager manager;

  /** Registers the {@link FhirResourceMappingSchemaDescriptor} with the schema service. */
  @PostConstruct
  void init() {
    schemaService.register(new FhirResourceMappingSchemaDescriptor());
  }

  /**
   * Returns the valid, non-conflicting mappings of the type; invalid and duplicated mappings are
   * ignored and logged.
   *
   * @param type the FHIR resource type
   * @return an unmodifiable list ordered by mapping UID; empty when the type has no usable mapping
   */
  @Transactional(readOnly = true)
  public List<ResolvedMapping> resolve(FhirResourceType type) {
    return guard(store.getByResourceTypeNoAcl(type));
  }

  /**
   * Returns the valid, non-conflicting mappings of every resource type; invalid and duplicated
   * mappings are ignored and logged.
   *
   * @return an unmodifiable list ordered by mapping UID; empty when no mapping is usable
   */
  @Transactional(readOnly = true)
  public List<ResolvedMapping> resolveAll() {
    return guard(store.getAllNoAcl());
  }

  /**
   * Drops every mapping with validation errors, then every mapping whose uniqueness key another
   * remaining mapping also holds, and converts the rest into records ordered by UID.
   */
  private List<ResolvedMapping> guard(List<FhirResourceMapping> stored) {
    Map<String, List<FhirResourceMapping>> byKey = new LinkedHashMap<>();
    List<FhirResourceMapping> usable = new ArrayList<>();
    for (FhirResourceMapping mapping : stored) {
      if (mapping == null) {
        continue;
      }
      List<ErrorReport> reports = validator.validate(mapping, List.of());
      if (!reports.isEmpty()) {
        log.warn(
            "Ignoring invalid FHIR resource mapping {} with error codes {}",
            mapping.getUid(),
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
            log.warn(
                "Ignoring FHIR resource mappings {} that share the uniqueness key {}",
                mappings.stream().map(FhirResourceMapping::getUid).toList(),
                key);
          }
        });
    return usable.stream()
        .map(this::toResolved)
        .sorted(
            Comparator.comparing(
                ResolvedMapping::uid, Comparator.nullsLast(Comparator.naturalOrder())))
        .toList();
  }

  /**
   * Converts a mapping into a record, resolving the value type of each attribute and data element
   * source and the search constraints of each attribute source.
   */
  private ResolvedMapping toResolved(FhirResourceMapping mapping) {
    Map<String, ValueType> valueTypes = new LinkedHashMap<>();
    Map<String, Set<QueryOperator>> blockedSearchOperators = new LinkedHashMap<>();
    Map<String, Integer> minCharactersToSearch = new LinkedHashMap<>();
    for (FhirFieldMapping entry : mapping.getFieldMappings()) {
      String source = entry == null ? null : entry.getSource();
      if (source == null) {
        continue;
      }
      if (entry.getSourceType() == FhirSourceType.ATTRIBUTE) {
        TrackedEntityAttribute attribute = manager.getNoAcl(TrackedEntityAttribute.class, source);
        if (attribute != null) {
          putValueType(valueTypes, source, attribute.getValueType());
          Set<QueryOperator> blocked = attribute.getBlockedSearchOperators();
          blockedSearchOperators.put(source, blocked == null ? Set.of() : blocked);
          minCharactersToSearch.put(source, attribute.getMinCharactersToSearch());
        }
      } else if (entry.getSourceType() == FhirSourceType.DATA_ELEMENT) {
        DataElement dataElement = manager.getNoAcl(DataElement.class, source);
        if (dataElement != null) {
          putValueType(valueTypes, source, dataElement.getValueType());
        }
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

  /**
   * A validated mapping detached from persistence: references are UIDs and every collection is an
   * unmodifiable copy made on construction.
   *
   * @param uid the mapping UID
   * @param resourceType the FHIR resource type the mapping produces
   * @param trackedEntityType the UID of the mapping's tracked entity type
   * @param program the UID of the mapping's program, or {@code null} when it has none
   * @param programStage the UID of the mapping's program stage, or {@code null} when it has none
   * @param entries copies of the field mapping entries, in stored order
   * @param valueTypes the value type of each attribute and data element source, by source UID
   * @param blockedSearchOperators the blocked search operators of each attribute source, by
   *     attribute UID; an empty set when none are blocked
   * @param minCharactersToSearch the minimum number of characters to search of each attribute
   *     source, by attribute UID
   * @param lastUpdated when the mapping was last updated, or {@code null} when unknown
   */
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

    /** Replaces every collection with an unmodifiable copy; {@code null} becomes empty. */
    public ResolvedMapping {
      entries = entries == null ? List.of() : entries.stream().map(FhirFieldMapping::new).toList();
      valueTypes = copy(valueTypes);
      Map<String, Set<QueryOperator>> operators = new LinkedHashMap<>();
      if (blockedSearchOperators != null) {
        blockedSearchOperators.forEach((teaUid, ops) -> operators.put(teaUid, copyOperators(ops)));
      }
      blockedSearchOperators = Collections.unmodifiableMap(operators);
      minCharactersToSearch = copy(minCharactersToSearch);
    }

    /**
     * Returns the entries with the given target.
     *
     * @param target the FHIR target field
     * @return an unmodifiable list in stored order; empty when no entry has the target
     */
    public List<FhirFieldMapping> entries(FhirTargetField target) {
      return entries.stream().filter(e -> e.getTarget() == target).toList();
    }

    /**
     * Returns the first entry with the given target.
     *
     * @param target the FHIR target field
     * @return the first such entry in stored order, or empty when no entry has the target
     */
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
