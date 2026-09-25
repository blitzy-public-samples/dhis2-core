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
package org.hisp.dhis.webapi.controller.tracker.export.enrollment;

import jakarta.servlet.http.HttpServletRequest;
import org.hisp.dhis.feedback.BadRequestException;
import org.hisp.dhis.feedback.ForbiddenException;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.FilteredPage;
import org.springframework.stereotype.Component;

/**
 * Delegates enrollment list reads to the list handler of {@link EnrollmentsExportController}.
 *
 * <p>The request parameters and the current HTTP request are passed through unchanged, and the
 * controller's result and exceptions are returned or propagated as they are. Request parameter
 * validation, sharing and data-read checks, org-unit scoping, program ownership and the filtering
 * of nested events and data values are all performed by the controller's export path.
 */
@Component
public final class FhirEnrollmentExportAdapter {
  private final EnrollmentsExportController controller;

  /**
   * Creates the adapter.
   *
   * @param controller the enrollment export controller whose list handler serves every read
   */
  public FhirEnrollmentExportAdapter(EnrollmentsExportController controller) {
    this.controller = controller;
  }

  /**
   * Finds enrollments through the enrollment export controller's list handler.
   *
   * @param params the enrollment request parameters, including the requested fields, passed to the
   *     controller as they are
   * @param request the current HTTP request, used by the controller to build pager links when
   *     paging is enabled
   * @return the page of enrollment view DTOs and the requested fields, as returned by the
   *     controller
   * @throws BadRequestException if the controller rejects the request parameters
   * @throws ForbiddenException if the controller denies the current user access to the requested
   *     program, its tracked entity type, org units or org unit selection mode
   */
  public FilteredPage<Enrollment> find(EnrollmentRequestParams params, HttpServletRequest request)
      throws BadRequestException, ForbiddenException {
    return controller.getEnrollments(params, request);
  }
}
