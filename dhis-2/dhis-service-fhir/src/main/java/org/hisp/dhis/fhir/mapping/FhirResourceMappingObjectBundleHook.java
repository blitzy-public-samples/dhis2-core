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

import jakarta.annotation.Resource;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.dxf2.metadata.objectbundle.ObjectBundle;
import org.hisp.dhis.dxf2.metadata.objectbundle.hooks.AbstractObjectBundleHook;
import org.hisp.dhis.feedback.ErrorReport;
import org.hisp.dhis.preheat.*;
import org.hisp.dhis.security.acl.AclService;
import org.springframework.stereotype.Component;

/** Validates FHIR resource mappings during metadata import for a user who may write them. */
@Component
@RequiredArgsConstructor
public class FhirResourceMappingObjectBundleHook
    extends AbstractObjectBundleHook<FhirResourceMapping> {
  private static final String UNIQUENESS_VIEW = "fhirResourceMappingUniquenessView";
  private final FhirResourceMappingStore store;
  private final FhirResourceMappingValidator validator;
  @Resource private AclService aclService;

  /** Removes other holders of its name and code from the preheat, then validates it if writable. */
  @Override
  public void validate(
      FhirResourceMapping mapping, ObjectBundle bundle, Consumer<ErrorReport> addReports) {
    forgetOtherHolders(mapping, bundle);
    if (mayWrite(mapping, bundle)) {
      validator.validate(mapping, others(mapping, bundle), lookup(bundle)).forEach(addReports);
    }
  }

  private static void forgetOtherHolders(FhirResourceMapping mapping, ObjectBundle bundle) {
    Preheat preheat = bundle.getPreheat();
    var byClass = preheat == null ? null : preheat.getUniquenessMap();
    Map<String, Map<Object, String>> byProperty =
        byClass == null ? null : byClass.get(FhirResourceMapping.class);
    if (byProperty == null) {
      return;
    }
    String own = bundle.getPreheatIdentifier().getIdentifier(mapping);
    FhirResourceMappingValidator.UNIQUE_PROPERTIES.forEach(
        (property, valueOf) -> {
          Map<Object, String> holders = byProperty.get(property);
          String value = valueOf.apply(mapping);
          String holder = holders == null || value == null ? null : holders.get(value);
          if (holder != null && !holder.equals(own)) {
            holders.remove(value);
          }
        });
  }

  private boolean mayWrite(FhirResourceMapping mapping, ObjectBundle bundle) {
    var user = bundle.getUserDetails();
    Preheat preheat = bundle.getPreheat();
    return user == null
        || (bundle.isPersisted(mapping)
            ? aclService.canUpdate(user, preheat.get(bundle.getPreheatIdentifier(), mapping))
            : aclService.canCreate(user, FhirResourceMapping.class));
  }

  private List<FhirResourceMapping> others(FhirResourceMapping mapping, ObjectBundle bundle) {
    UniquenessView view = view(mapping, bundle);
    Stream<FhirResourceMapping> byKey =
        holders(view.byKey(), FhirResourceMappingValidator.uniquenessKey(mapping));
    Stream<FhirResourceMapping> byProperty =
        FhirResourceMappingValidator.UNIQUE_PROPERTIES.entrySet().stream()
            .flatMap(
                unique ->
                    holders(
                        view.byProperty().get(unique.getKey()), unique.getValue().apply(mapping)));
    String uid = mapping.getUid();
    Set<FhirResourceMapping> listed = Collections.newSetFromMap(new IdentityHashMap<>());
    return Stream.concat(byKey, byProperty)
        .filter(other -> other != mapping && (uid == null || !uid.equals(other.getUid())))
        .filter(listed::add)
        .toList();
  }

  private static Stream<FhirResourceMapping> holders(
      Map<String, List<FhirResourceMapping>> byValue, String value) {
    return value == null || byValue == null
        ? Stream.empty()
        : byValue.getOrDefault(value, List.of()).stream();
  }

  private UniquenessView view(FhirResourceMapping mapping, ObjectBundle bundle) {
    if (bundle.getExtras(mapping, UNIQUENESS_VIEW) instanceof UniquenessView kept) {
      return kept;
    }
    Iterable<FhirResourceMapping> imported = bundle.getObjects(FhirResourceMapping.class);
    List<FhirResourceMapping> all =
        Stream.concat(
                store.getAllNoAcl().stream(), StreamSupport.stream(imported.spliterator(), false))
            .filter(Objects::nonNull)
            .toList();
    Map<String, Map<String, List<FhirResourceMapping>>> byProperty = new HashMap<>();
    FhirResourceMappingValidator.UNIQUE_PROPERTIES.forEach(
        (property, valueOf) -> byProperty.put(property, groupBy(all, valueOf)));
    UniquenessView view =
        new UniquenessView(groupBy(all, FhirResourceMappingValidator::uniquenessKey), byProperty);
    imported.forEach(candidate -> bundle.putExtras(candidate, UNIQUENESS_VIEW, view));
    return view;
  }

  private static Map<String, List<FhirResourceMapping>> groupBy(
      List<FhirResourceMapping> mappings, Function<FhirResourceMapping, String> valueOf) {
    return mappings.stream()
        .filter(each -> valueOf.apply(each) != null)
        .collect(Collectors.groupingBy(valueOf));
  }

  private record UniquenessView(
      Map<String, List<FhirResourceMapping>> byKey,
      Map<String, Map<String, List<FhirResourceMapping>>> byProperty) {}

  private BiFunction<Class<? extends IdentifiableObject>, String, IdentifiableObject> lookup(
      ObjectBundle bundle) {
    Preheat preheat = bundle.getPreheat();
    return (klass, uid) -> {
      IdentifiableObject found =
          preheat == null ? null : preheat.get(PreheatIdentifier.UID, klass, uid);
      return found != null ? found : manager.getNoAcl(klass, uid);
    };
  }
}
