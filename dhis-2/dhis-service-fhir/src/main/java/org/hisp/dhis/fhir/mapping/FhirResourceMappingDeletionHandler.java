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

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.system.deletion.DeletionVeto;
import org.hisp.dhis.system.deletion.IdObjectDeletionHandler;
import org.hisp.dhis.trackedentity.TrackedEntityType;
import org.springframework.stereotype.Component;

/**
 * Vetoes deleting a {@link TrackedEntityType}, {@link Program} or {@link ProgramStage} that a
 * {@link FhirResourceMapping} references, a program also when a mapping references one of its
 * stages, and a user who created or last updated a mapping. The veto names only the mapping type.
 */
@Component
@RequiredArgsConstructor
public class FhirResourceMappingDeletionHandler
    extends IdObjectDeletionHandler<FhirResourceMapping> {
  private final EntityManager entityManager;

  @Override
  protected void registerHandler() {
    whenVetoing(TrackedEntityType.class, type -> vetoIfMapped("m.trackedEntityType = :o", type));
    whenVetoing(
        Program.class, program -> vetoIfMapped("m.program = :o or s.program = :o", program));
    whenVetoing(ProgramStage.class, stage -> vetoIfMapped("s = :o", stage));
  }

  /** Returns {@link #VETO} when mapping {@code m} or its stage {@code s} meets the condition. */
  private DeletionVeto vetoIfMapped(String condition, IdentifiableObject object) {
    String query = "select m.id from FhirResourceMapping m left join m.programStage s where ";
    return entityManager
            .createQuery(query + condition)
            .setParameter("o", object)
            .setMaxResults(1)
            .getResultList()
            .isEmpty()
        ? DeletionVeto.ACCEPT
        : VETO;
  }
}
