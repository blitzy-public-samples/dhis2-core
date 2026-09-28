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
package org.hisp.dhis.fhir.mapping.hibernate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import java.util.*;
import javax.annotation.Nonnull;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.common.hibernate.HibernateIdentifiableObjectStore;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.hibernate.JpaQueryParameters;
import org.hisp.dhis.program.*;
import org.hisp.dhis.security.acl.AclService;
import org.hisp.dhis.trackedentity.TrackedEntityType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository("org.hisp.dhis.fhir.mapping.FhirResourceMappingStore")
public class HibernateFhirResourceMappingStore
    extends HibernateIdentifiableObjectStore<FhirResourceMapping>
    implements FhirResourceMappingStore {
  private static final String WITH_ENCOUNTERS =
      "from FhirResourceMapping m where m.resourceType = :type or (m.resourceType = :encounter and"
          + " m.programStage in (select s.programStage from FhirResourceMapping s"
          + " where s.resourceType = :type))";
  private static final Map<Class<?>, String> MEMBERS =
      Map.of(
          TrackedEntityType.class,
              "trackedEntityTypeAttributes m left join fetch m.trackedEntityAttribute",
          Program.class, "programAttributes m left join fetch m.attribute",
          ProgramStage.class, "programStageDataElements m left join fetch m.dataElement");
  private static final String MEMBERS_HQL = "from %s o left join fetch o.%s where o.uid in (:uids)";

  public HibernateFhirResourceMappingStore(
      EntityManager entityManager,
      JdbcTemplate jdbcTemplate,
      ApplicationEventPublisher publisher,
      AclService aclService) {
    super(entityManager, jdbcTemplate, publisher, FhirResourceMapping.class, aclService, false);
  }

  @Nonnull
  @Override
  public List<FhirResourceMapping> getByResourceTypeNoAcl(@Nonnull FhirResourceType type) {
    CriteriaBuilder builder = getCriteriaBuilder();
    return getList(
        builder,
        new JpaQueryParameters<FhirResourceMapping>()
            .addPredicate(root -> builder.equal(root.get("resourceType"), type)));
  }

  @Nonnull
  @Override
  public List<FhirResourceMapping> getByResourceTypeWithEncountersNoAcl(
      @Nonnull FhirResourceType type) {
    return getQuery(WITH_ENCOUNTERS, FhirResourceMapping.class)
        .setParameter("type", type)
        .setParameter("encounter", FhirResourceType.ENCOUNTER)
        .list();
  }

  @Nonnull
  @Override
  public <T extends IdentifiableObject> List<T> getWithMembersNoAcl(
      @Nonnull Class<T> type, @Nonnull Collection<String> uids) {
    String hql = MEMBERS_HQL.formatted(type.getName(), Objects.requireNonNull(MEMBERS.get(type)));
    return uids.isEmpty()
        ? List.of()
        : getQuery(hql, type).setParameterList("uids", uids).list().stream().distinct().toList();
  }
}
