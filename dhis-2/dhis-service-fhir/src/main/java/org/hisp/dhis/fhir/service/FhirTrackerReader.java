/*
 * Copyright (c) 2004-2025, University of Oslo
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
package org.hisp.dhis.fhir.service;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.common.IllegalQueryException;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.deadline.DeadlineHolder;
import org.hisp.dhis.dxf2.webmessage.WebMessageException;
import org.hisp.dhis.feedback.BadRequestException;
import org.hisp.dhis.feedback.ForbiddenException;
import org.hisp.dhis.feedback.NotFoundException;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.tracker.export.timeout.TrackerExportTimeout;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.EnrollmentRequestParams;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.FhirEnrollmentExportAdapter;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.FhirTrackedEntityExportAdapter;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.TrackedEntityRequestParams;
import org.hisp.dhis.webapi.controller.tracker.view.Enrollment;
import org.hisp.dhis.webapi.controller.tracker.view.Page;
import org.hisp.dhis.webapi.controller.tracker.view.TrackedEntity;
import org.springframework.stereotype.Service;

/**
 * Reads Tracker data for the FHIR API.
 *
 * <p>Tracked entities are read through {@link FhirTrackedEntityExportAdapter} and enrollments, with
 * their nested events and data values, through {@link FhirEnrollmentExportAdapter}. Request
 * parameters and the HTTP request are passed to the adapters unchanged. Access control, org-unit
 * scoping, program ownership and attribute and data value filtering are those of the Tracker export
 * path; the reader performs no access check of its own.
 *
 * <p>Each FHIR operation runs within one Tracker export deadline, opened by {@link
 * #withinDeadline(Supplier)} and checked by {@link #checkpoint()}, which each find method also
 * calls before it reads.
 *
 * <p>Export-path exceptions are translated into {@link FhirApiException}s whose diagnostics are
 * fixed texts and FHIR parameter names only; the Tracker exception message is never part of them:
 *
 * <ul>
 *   <li>{@link ForbiddenException}: {@code 403 forbidden} for tracked entities, and a forbidden
 *       {@link EnrollmentResult} for enrollments.
 *   <li>{@link BadRequestException} stating that the program or tracked entity type the request
 *       selects does not exist for the user: the same as {@link ForbiddenException}.
 *   <li>{@link NotFoundException}: {@code 404 not-found}.
 *   <li>{@link BadRequestException} and {@link IllegalQueryException}: {@code 400 invalid} naming
 *       the FHIR parameters of the attributes they cite, or {@code 400 invalid} naming the
 *       attribute search parameters when the minimum attribute count is not met; otherwise {@code
 *       501 not-supported}.
 * </ul>
 *
 * <p>{@link WebMessageException}, {@link org.hisp.dhis.deadline.DeadlineExceededException}, {@link
 * IllegalArgumentException} and every other exception propagate unchanged.
 */
@Slf4j
@Service
public class FhirTrackerReader {

  /**
   * Separator between the FHIR parameter names passed as one {@code name} to {@link
   * FhirApiException#invalidParameter(String, String)}.
   */
  static final String PARAMETER_SEPARATOR = ", ";

  static final String ATTRIBUTE_VALUE_REJECTED =
      "The value is not accepted by the mapped attribute";

  static final String ATTRIBUTE_NOT_SEARCHABLE =
      "The attribute cannot be searched outside the user's capture scope";

  /**
   * The phrase of the export path's {@link BadRequestException} for a selected program or tracked
   * entity type that the current user cannot find.
   */
  static final String SELECTOR_NOT_FOUND = "is specified but does not exist";

  private static final Pattern MIN_ATTRIBUTES = Pattern.compile("At least (\\d+) attributes");

  private final FhirTrackedEntityExportAdapter trackedEntityAdapter;

  private final FhirEnrollmentExportAdapter enrollmentAdapter;

  private final TrackerExportTimeout timeout;

  /**
   * Creates the reader.
   *
   * @param trackedEntityAdapter the adapter that serves every tracked entity read
   * @param enrollmentAdapter the adapter that serves every enrollment read
   * @param timeout the configured Tracker export timeout that supplies each operation's deadline
   */
  public FhirTrackerReader(
      FhirTrackedEntityExportAdapter trackedEntityAdapter,
      FhirEnrollmentExportAdapter enrollmentAdapter,
      TrackerExportTimeout timeout) {
    this.trackedEntityAdapter = trackedEntityAdapter;
    this.enrollmentAdapter = enrollmentAdapter;
    this.timeout = timeout;
  }

  /**
   * Runs one FHIR operation within a Tracker export deadline.
   *
   * <p>When this thread holds no deadline, a new one from {@link
   * TrackerExportTimeout#newDeadline()} is set before {@code operation} runs and cleared after it
   * returns or throws; a disabled timeout leaves the operation unbounded. When this thread already
   * holds a deadline, the operation runs within it and it is left in place.
   *
   * @param operation the whole body of the read, search or {@code $everything} operation
   * @param <T> the result type of the operation
   * @return the result of {@code operation}
   * @throws org.hisp.dhis.deadline.DeadlineExceededException if the deadline expires during a
   *     checkpoint or a Tracker query of the operation
   */
  public <T> T withinDeadline(@Nonnull Supplier<T> operation) {
    Objects.requireNonNull(operation, "operation");
    boolean owner = DeadlineHolder.get() == null;
    if (owner) {
      DeadlineHolder.set(timeout.newDeadline());
    }
    try {
      return operation.get();
    } finally {
      if (owner) {
        DeadlineHolder.clear();
      }
    }
  }

  /**
   * Checks that the deadline of the current operation has not expired. Does nothing when this
   * thread holds no deadline.
   *
   * @throws org.hisp.dhis.deadline.DeadlineExceededException if the deadline has expired
   */
  public void checkpoint() {
    DeadlineHolder.checkNotExpired();
  }

  /**
   * Finds tracked entities through {@link FhirTrackedEntityExportAdapter}, after a {@link
   * #checkpoint()}. Sets and clears no deadline.
   *
   * @param params the tracked entity request parameters, passed to the adapter unchanged
   * @param request the current HTTP request, passed to the adapter unchanged
   * @param origin the FHIR parameter behind each attribute filter in {@code params}; {@link
   *     FhirSearchOrigin#empty()} for reads
   * @return the page returned by the export path
   * @throws FhirApiException {@code 403 forbidden} with fixed diagnostics when the export path
   *     denies access; {@code 404 not-found} when it reports a missing entity; {@code 400 invalid}
   *     naming the FHIR parameters of the attributes a rejection cites, or naming the attribute
   *     search parameters when the minimum attribute count is not met; {@code 501 not-supported}
   *     for every other rejection
   * @throws WebMessageException propagated unchanged from the export path
   * @throws org.hisp.dhis.deadline.DeadlineExceededException if the deadline has expired
   */
  @SneakyThrows(WebMessageException.class)
  public Page<TrackedEntity> findTrackedEntities(
      @Nonnull TrackedEntityRequestParams params,
      @Nonnull HttpServletRequest request,
      @Nonnull FhirSearchOrigin origin) {
    Objects.requireNonNull(origin, "origin");
    checkpoint();
    try {
      return trackedEntityAdapter.find(params, request).page();
    } catch (ForbiddenException e) {
      log.debug("Tracked entity export denied access: {}", e.getMessage());
      throw FhirApiException.forbidden();
    } catch (NotFoundException e) {
      log.debug("Tracked entity export found no entity: {}", e.getMessage());
      throw FhirApiException.notFound();
    } catch (BadRequestException e) {
      if (hidesSelector(e, params.getProgram(), params.getTrackedEntityType())) {
        log.debug("Tracked entity export cannot see the selected metadata: {}", e.getMessage());
        throw FhirApiException.forbidden();
      }
      throw translateBadRequest(e, origin);
    } catch (IllegalQueryException e) {
      throw translateIllegalQuery(e, origin);
    }
  }

  /**
   * Finds enrollments, with their nested events and data values, through {@link
   * FhirEnrollmentExportAdapter}, after a {@link #checkpoint()}. Sets and clears no deadline.
   *
   * @param params the enrollment request parameters, passed to the adapter unchanged
   * @param request the current HTTP request, passed to the adapter unchanged
   * @param type the FHIR resource type being served, named in {@code 501} diagnostics
   * @return the enrollments of the page returned by the export path, or {@link
   *     EnrollmentResult#ofForbidden()} when the export path denies access
   * @throws FhirApiException {@code 501 not-supported} naming {@code type} when the export path
   *     rejects the request
   * @throws org.hisp.dhis.deadline.DeadlineExceededException if the deadline has expired
   */
  public EnrollmentResult findEnrollments(
      @Nonnull EnrollmentRequestParams params,
      @Nonnull HttpServletRequest request,
      @Nonnull FhirResourceType type) {
    Objects.requireNonNull(type, "type");
    checkpoint();
    try {
      return EnrollmentResult.of(enrollmentAdapter.find(params, request).page().getItems());
    } catch (ForbiddenException e) {
      log.debug("Enrollment export denied access: {}", e.getMessage());
      return EnrollmentResult.ofForbidden();
    } catch (BadRequestException e) {
      if (hidesSelector(e, params.getProgram())) {
        log.debug("Enrollment export cannot see the selected program: {}", e.getMessage());
        return EnrollmentResult.ofForbidden();
      }
      throw unusableMapping(type, e);
    } catch (IllegalQueryException e) {
      throw unusableMapping(type, e);
    }
  }

  /**
   * Returns whether a {@link BadRequestException} reports that the program or tracked entity type
   * the request selects does not exist for the current user, which is how the export path answers a
   * selected object the user may not read as metadata: its message contains {@value
   * #SELECTOR_NOT_FOUND} and cites one of {@code selectors}.
   */
  private static boolean hidesSelector(BadRequestException exception, UID... selectors) {
    String message = exception.getMessage();
    if (message == null || !message.contains(SELECTOR_NOT_FOUND)) {
      return false;
    }
    for (UID selector : selectors) {
      if (selector != null && cites(message, selector.getValue())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Translates a {@link BadRequestException} of the tracked entity export path: {@code 400 invalid}
   * naming the parameter of the first origin attribute the message cites, in origin order,
   * otherwise {@code 501 not-supported}.
   */
  private static FhirApiException translateBadRequest(
      BadRequestException exception, FhirSearchOrigin origin) {
    String message = exception.getMessage();
    for (Map.Entry<String, String> entry : origin.attributeToParameter().entrySet()) {
      if (cites(message, entry.getKey())) {
        log.debug("Tracked entity export rejected parameter '{}': {}", entry.getValue(), message);
        return FhirApiException.invalidParameter(entry.getValue(), ATTRIBUTE_VALUE_REJECTED);
      }
    }
    return unusableMapping(FhirResourceType.PATIENT, exception);
  }

  /**
   * Translates an {@link IllegalQueryException} of the tracked entity export path, in this order:
   * {@code 400 invalid} naming the distinct parameters of every origin attribute the message cites;
   * {@code 400 invalid} naming the supplied, or else the configured, attribute parameters and the
   * minimum when the message reports a minimum attribute count; otherwise {@code 501
   * not-supported}.
   */
  private static FhirApiException translateIllegalQuery(
      IllegalQueryException exception, FhirSearchOrigin origin) {
    String message = exception.getMessage();

    Set<String> cited = new LinkedHashSet<>();
    for (Map.Entry<String, String> entry : origin.attributeToParameter().entrySet()) {
      if (cites(message, entry.getKey())) {
        cited.add(entry.getValue());
      }
    }
    if (!cited.isEmpty()) {
      log.debug("Tracked entity export rejected parameters {}: {}", cited, message);
      return FhirApiException.invalidParameter(
          String.join(PARAMETER_SEPARATOR, cited), ATTRIBUTE_NOT_SEARCHABLE);
    }

    Matcher minimum = message == null ? null : MIN_ATTRIBUTES.matcher(message);
    if (minimum != null && minimum.find()) {
      List<String> names =
          origin.suppliedAttributeParameters().isEmpty()
              ? origin.configuredAttributeParameters()
              : origin.suppliedAttributeParameters();
      if (!names.isEmpty()) {
        log.debug("Tracked entity export requires more attribute parameters: {}", message);
        return FhirApiException.invalidParameter(
            String.join(PARAMETER_SEPARATOR, names),
            "At least " + minimum.group(1) + " attribute search parameters are required");
      }
    }

    return unusableMapping(FhirResourceType.PATIENT, exception);
  }

  /**
   * Creates the {@code 501 not-supported} error "The configured mapping for {type} cannot be used"
   * and logs the export-path message at {@code WARN}.
   */
  private static FhirApiException unusableMapping(FhirResourceType type, Exception exception) {
    log.warn(
        "The configured FHIR mapping for {} was rejected by the Tracker export: {}",
        type.fhirType(),
        exception.getMessage());
    return FhirApiException.notSupported(
        "The configured mapping for " + type.fhirType() + " cannot be used");
  }

  /**
   * Returns whether {@code message} contains {@code uid} as a whole token, that is neither preceded
   * nor followed by a letter or digit.
   */
  private static boolean cites(@CheckForNull String message, String uid) {
    if (message == null || uid == null || uid.isEmpty()) {
      return false;
    }
    int from = 0;
    int index;
    while ((index = message.indexOf(uid, from)) >= 0) {
      int end = index + uid.length();
      boolean startsToken = index == 0 || !Character.isLetterOrDigit(message.charAt(index - 1));
      boolean endsToken =
          end == message.length() || !Character.isLetterOrDigit(message.charAt(end));
      if (startsToken && endsToken) {
        return true;
      }
      from = index + 1;
    }
    return false;
  }

  /**
   * The FHIR search parameters behind the attribute filters of one tracked entity request.
   *
   * @param attributeToParameter tracked entity attribute UID to the FHIR parameter that produced
   *     its filter, in filter order; a {@code null} map is treated as empty
   * @param suppliedAttributeParameters the attribute-backed FHIR parameters present in the request;
   *     a {@code null} list is treated as empty
   * @param configuredAttributeParameters the attribute-backed FHIR parameters the resolved mapping
   *     supports; a {@code null} list is treated as empty
   */
  public record FhirSearchOrigin(
      Map<String, String> attributeToParameter,
      List<String> suppliedAttributeParameters,
      List<String> configuredAttributeParameters) {

    /**
     * Copies every component into an unmodifiable collection, keeping the iteration order of {@code
     * attributeToParameter}.
     *
     * @throws NullPointerException if a key, value or list element is {@code null}
     */
    public FhirSearchOrigin {
      attributeToParameter = copyOf(attributeToParameter);
      suppliedAttributeParameters =
          suppliedAttributeParameters == null
              ? List.of()
              : List.copyOf(suppliedAttributeParameters);
      configuredAttributeParameters =
          configuredAttributeParameters == null
              ? List.of()
              : List.copyOf(configuredAttributeParameters);
    }

    /**
     * Returns the origin of a request without attribute filters, as used for reads.
     *
     * @return an origin whose map and lists are empty
     */
    public static FhirSearchOrigin empty() {
      return new FhirSearchOrigin(Map.of(), List.of(), List.of());
    }

    private static Map<String, String> copyOf(@CheckForNull Map<String, String> source) {
      if (source == null || source.isEmpty()) {
        return Map.of();
      }
      Map<String, String> copy = new LinkedHashMap<>();
      source.forEach(
          (uid, parameter) ->
              copy.put(
                  Objects.requireNonNull(uid, "attribute"),
                  Objects.requireNonNull(parameter, "parameter")));
      return Collections.unmodifiableMap(copy);
    }
  }

  /**
   * The enrollments one export call returned, or the export path's denial of access.
   *
   * @param enrollments the enrollments in the order returned; empty when {@code forbidden}; a
   *     {@code null} list is treated as empty
   * @param forbidden whether the export path denied the current user access
   */
  public record EnrollmentResult(List<Enrollment> enrollments, boolean forbidden) {

    /**
     * Copies {@code enrollments} into an unmodifiable list.
     *
     * @throws IllegalArgumentException if {@code forbidden} is {@code true} and {@code enrollments}
     *     is not empty
     */
    public EnrollmentResult {
      enrollments = enrollments == null ? List.of() : List.copyOf(enrollments);
      if (forbidden && !enrollments.isEmpty()) {
        throw new IllegalArgumentException("A forbidden result holds no enrollments");
      }
    }

    /**
     * Returns the result of an export call that returned {@code enrollments}.
     *
     * @param enrollments the enrollments returned; {@code null} is treated as empty
     * @return a result that is not forbidden
     */
    public static EnrollmentResult of(@CheckForNull List<Enrollment> enrollments) {
      return new EnrollmentResult(enrollments, false);
    }

    /**
     * Returns the result of an export call that the export path denied.
     *
     * @return a forbidden result without enrollments
     */
    public static EnrollmentResult ofForbidden() {
      return new EnrollmentResult(List.of(), true);
    }
  }
}
