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

import java.util.*;
import java.util.function.*;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.dxf2.metadata.objectbundle.ObjectBundle;
import org.hisp.dhis.dxf2.metadata.objectbundle.hooks.AbstractObjectBundleHook;
import org.hisp.dhis.feedback.ErrorReport;
import org.hisp.dhis.preheat.*;
import org.springframework.stereotype.Component;

/** Validates FHIR resource mappings during metadata import. */
@Component
@RequiredArgsConstructor
public class FhirResourceMappingObjectBundleHook
    extends AbstractObjectBundleHook<FhirResourceMapping> {
  private static final String UNIQUENESS_VIEW =
      FhirResourceMappingObjectBundleHook.class.getName() + ".uniquenessView";
  private final FhirResourceMappingStore store;
  private final FhirResourceMappingValidator validator;

  /** Reports every violation against the stored and bundle mappings and the preheat metadata. */
  @Override
  public void validate(
      FhirResourceMapping mapping, ObjectBundle bundle, Consumer<ErrorReport> addReports) {
    validator.validate(mapping, others(mapping, bundle), lookup(bundle)).forEach(addReports);
  }

  private List<FhirResourceMapping> others(FhirResourceMapping mapping, ObjectBundle bundle) {
    String key = FhirResourceMappingValidator.uniquenessKey(mapping);
    if (key == null) {
      return List.of();
    }
    String uid = mapping.getUid();
    return view(mapping, bundle).byKey().getOrDefault(key, List.of()).stream()
        .filter(other -> other != mapping && (uid == null || !uid.equals(other.getUid())))
        .toList();
  }

  private UniquenessView view(FhirResourceMapping mapping, ObjectBundle bundle) {
    if (bundle.getExtras(mapping, UNIQUENESS_VIEW) instanceof UniquenessView kept) {
      return kept;
    }
    Map<String, FhirResourceMapping> byUid = new LinkedHashMap<>();
    List<FhirResourceMapping> withoutUid = new ArrayList<>();
    for (FhirResourceMapping stored : store.getAllNoAcl()) {
      collect(stored, byUid, withoutUid);
    }
    Iterable<FhirResourceMapping> imported = bundle.getObjects(FhirResourceMapping.class);
    for (FhirResourceMapping candidate : imported) {
      collect(candidate, byUid, withoutUid);
    }
    List<FhirResourceMapping> overlay = new ArrayList<>(byUid.values());
    overlay.addAll(withoutUid);
    Map<String, List<FhirResourceMapping>> byKey = new HashMap<>();
    for (FhirResourceMapping candidate : overlay) {
      String key = FhirResourceMappingValidator.uniquenessKey(candidate);
      if (key != null) {
        byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(candidate);
      }
    }
    UniquenessView view = new UniquenessView(byKey);
    for (FhirResourceMapping candidate : imported) {
      bundle.putExtras(candidate, UNIQUENESS_VIEW, view);
    }
    return view;
  }

  private record UniquenessView(Map<String, List<FhirResourceMapping>> byKey) {}

  private static void collect(
      FhirResourceMapping mapping,
      Map<String, FhirResourceMapping> byUid,
      List<FhirResourceMapping> withoutUid) {
    if (mapping == null) {
      return;
    }
    if (mapping.getUid() == null) {
      withoutUid.add(mapping);
    } else {
      byUid.put(mapping.getUid(), mapping);
    }
  }

  private BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup(
      ObjectBundle bundle) {
    Preheat preheat = bundle.getPreheat();
    return (klass, uid) -> {
      IdentifiableObject found = null;
      if (preheat != null) {
        found = preheat.get(PreheatIdentifier.UID, klass, uid);
      }
      return found != null ? found : manager.getNoAcl(klass, uid);
    };
  }
}
