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

/**
 * Validates FHIR resource mappings during metadata import when the importing user may create the
 * mapping or update its stored version; otherwise it reports nothing and skips no-ACL lookups.
 */
@Component
@RequiredArgsConstructor
public class FhirResourceMappingObjectBundleHook
    extends AbstractObjectBundleHook<FhirResourceMapping> {
  private static final String UNIQUENESS_VIEW = "fhirResourceMappingUniquenessView";
  private final FhirResourceMappingStore store;
  private final FhirResourceMappingValidator validator;
  @Resource private AclService aclService;

  /** For a user who may write the mapping, reports its violations against mappings and metadata. */
  @Override
  public void validate(
      FhirResourceMapping mapping, ObjectBundle bundle, Consumer<ErrorReport> addReports) {
    if (mayWrite(mapping, bundle)) {
      validator.validate(mapping, others(mapping, bundle), lookup(bundle)).forEach(addReports);
    }
  }

  /** Whether the bundle user, when there is one, may update the stored mapping or create it. */
  private boolean mayWrite(FhirResourceMapping mapping, ObjectBundle bundle) {
    var user = bundle.getUserDetails();
    Preheat preheat = bundle.getPreheat();
    return user == null
        || (bundle.isPersisted(mapping)
            ? aclService.canUpdate(user, preheat.get(bundle.getPreheatIdentifier(), mapping))
            : aclService.canCreate(user, FhirResourceMapping.class));
  }

  /** Returns the stored, then the bundle mappings holding the mapping's key, except its own UID. */
  private List<FhirResourceMapping> others(FhirResourceMapping mapping, ObjectBundle bundle) {
    String key = FhirResourceMappingValidator.uniquenessKey(mapping);
    String uid = mapping.getUid();
    return key == null
        ? List.of()
        : view(mapping, bundle).byKey().getOrDefault(key, List.of()).stream()
            .filter(other -> other != mapping && (uid == null || !uid.equals(other.getUid())))
            .toList();
  }

  /** Groups the mappings of one store read, then the bundle mappings, by uniqueness key. */
  private UniquenessView view(FhirResourceMapping mapping, ObjectBundle bundle) {
    if (bundle.getExtras(mapping, UNIQUENESS_VIEW) instanceof UniquenessView kept) {
      return kept;
    }
    Iterable<FhirResourceMapping> imported = bundle.getObjects(FhirResourceMapping.class);
    UniquenessView view =
        new UniquenessView(
            Stream.concat(
                    store.getAllNoAcl().stream(),
                    StreamSupport.stream(imported.spliterator(), false))
                .filter(each -> FhirResourceMappingValidator.uniquenessKey(each) != null)
                .collect(Collectors.groupingBy(FhirResourceMappingValidator::uniquenessKey)));
    imported.forEach(candidate -> bundle.putExtras(candidate, UNIQUENESS_VIEW, view));
    return view;
  }

  private record UniquenessView(Map<String, List<FhirResourceMapping>> byKey) {}

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
