/*
 * Copyright (c) 2004-2022, University of Oslo
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
package org.hisp.dhis.webapi.controller.tracker.export.trackedentity;

import jakarta.servlet.http.HttpServletRequest;
import org.hisp.dhis.dxf2.webmessage.WebMessageException;
import org.hisp.dhis.feedback.BadRequestException;
import org.hisp.dhis.feedback.ForbiddenException;
import org.hisp.dhis.feedback.NotFoundException;
import org.hisp.dhis.tracker.TrackerIdSchemeParams;
import org.hisp.dhis.user.CurrentUserUtil;
import org.hisp.dhis.webapi.controller.tracker.view.FilteredPage;
import org.hisp.dhis.webapi.controller.tracker.view.TrackedEntity;
import org.springframework.stereotype.Component;

/**
 * Delegates to the tracked entity list handler of {@link TrackedEntitiesExportController} for the
 * current user with the default UID id scheme.
 */
@Component
public final class FhirTrackedEntityExportAdapter {

  private final TrackedEntitiesExportController controller;

  /**
   * Creates the adapter over the given tracked entity export controller.
   *
   * @param controller the controller whose list handler serves every call
   */
  public FhirTrackedEntityExportAdapter(TrackedEntitiesExportController controller) {
    this.controller = controller;
  }

  /**
   * Finds tracked entities through the tracked entity list handler of {@link
   * TrackedEntitiesExportController}, as the currently authenticated user and with the default UID
   * id scheme.
   *
   * @param params the tracked entity request parameters, passed to the handler unchanged
   * @param request the current HTTP request, passed to the handler unchanged
   * @return the page returned by the handler, unchanged
   * @throws BadRequestException propagated unchanged from the handler
   * @throws ForbiddenException propagated unchanged from the handler
   * @throws NotFoundException propagated unchanged from the handler
   * @throws WebMessageException propagated unchanged from the handler
   * @throws IllegalStateException when no authenticated DHIS2 user is present
   */
  public FilteredPage<TrackedEntity> find(
      TrackedEntityRequestParams params, HttpServletRequest request)
      throws BadRequestException, ForbiddenException, NotFoundException, WebMessageException {
    return controller.getTrackedEntities(
        params, new TrackerIdSchemeParams(), CurrentUserUtil.getCurrentUserDetails(), request);
  }
}
