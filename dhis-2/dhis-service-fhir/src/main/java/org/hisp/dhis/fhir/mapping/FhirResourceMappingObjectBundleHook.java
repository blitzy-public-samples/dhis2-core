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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.dxf2.metadata.objectbundle.ObjectBundle;
import org.hisp.dhis.dxf2.metadata.objectbundle.hooks.AbstractObjectBundleHook;
import org.hisp.dhis.feedback.ErrorReport;
import org.hisp.dhis.preheat.Preheat;
import org.hisp.dhis.preheat.PreheatIdentifier;
import org.springframework.stereotype.Component;

/** Validates FHIR resource mappings during metadata import. */
@Component
@RequiredArgsConstructor
public class FhirResourceMappingObjectBundleHook
    extends AbstractObjectBundleHook<FhirResourceMapping> {
  private final FhirResourceMappingStore store;

  private final FhirResourceMappingValidator validator;

  /**
   * Reports every rule of {@link FhirResourceMappingValidator} the mapping violates. The mapping's
   * uniqueness key is compared with every stored mapping and every other mapping of the bundle; a
   * bundle mapping takes the place of the stored mapping with the same UID. Referenced metadata is
   * resolved by UID from the bundle's preheat, then from the database, ignoring sharing. The
   * mapping is not modified.
   *
   * @param mapping the imported mapping to validate
   * @param bundle the bundle being imported
   * @param addReports receives one report per violation
   */
  @Override
  public void validate(
      FhirResourceMapping mapping, ObjectBundle bundle, Consumer<ErrorReport> addReports) {
    validator.validate(mapping, others(mapping, bundle), lookup(bundle)).forEach(addReports);
  }

  /**
   * Returns the stored mappings overlaid by the bundle's mappings, without {@code mapping} and
   * without any mapping that has its UID.
   */
  private List<FhirResourceMapping> others(FhirResourceMapping mapping, ObjectBundle bundle) {
    Map<String, FhirResourceMapping> byUid = new LinkedHashMap<>();
    List<FhirResourceMapping> withoutUid = new ArrayList<>();
    for (FhirResourceMapping stored : store.getAllNoAcl()) {
      collect(stored, byUid, withoutUid);
    }
    for (FhirResourceMapping imported : bundle.getObjects(FhirResourceMapping.class)) {
      collect(imported, byUid, withoutUid);
    }
    if (mapping.getUid() != null) {
      byUid.remove(mapping.getUid());
    }
    List<FhirResourceMapping> others = new ArrayList<>(byUid.size() + withoutUid.size());
    others.addAll(byUid.values());
    others.addAll(withoutUid);
    others.removeIf(other -> other == mapping);
    return others;
  }

  /** Adds a mapping by UID, replacing an earlier one with that UID, or to the UID-less list. */
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

  /**
   * Returns a lookup that finds an object by class and UID in the bundle's preheat, which includes
   * the objects imported with the bundle, and otherwise in the database without sharing checks.
   */
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
