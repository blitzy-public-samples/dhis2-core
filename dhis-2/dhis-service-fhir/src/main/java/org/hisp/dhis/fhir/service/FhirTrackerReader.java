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

import static org.springframework.transaction.support.TransactionOperations.withoutTransaction;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.*;
import javax.annotation.*;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.common.IllegalQueryException;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.deadline.*;
import org.hisp.dhis.dxf2.webmessage.WebMessageException;
import org.hisp.dhis.feedback.*;
import org.hisp.dhis.fhir.FhirApiException;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.search.FhirSearchParameters;
import org.hisp.dhis.tracker.export.timeout.TrackerExportTimeout;
import org.hisp.dhis.webapi.controller.tracker.export.enrollment.*;
import org.hisp.dhis.webapi.controller.tracker.export.trackedentity.*;
import org.hisp.dhis.webapi.controller.tracker.view.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads Tracker data for the FHIR API, translating export-path errors into FHIR errors. Each
 * operation runs under one deadline and, with a transaction manager, one read-only transaction.
 */
@Slf4j
@Service
public class FhirTrackerReader {
  static final String PARAMETER_SEPARATOR = ", ";
  static final String ATTRIBUTE_VALUE_REJECTED =
      "The value is not accepted by the mapped attribute";
  static final String ATTRIBUTE_NOT_SEARCHABLE =
      "The attribute cannot be searched outside the user's capture scope";
  static final String NO_ATTRIBUTE_PARAMETERS =
      " and none is configured, so " + FhirSearchParameters.ID + " is required";
  static final String SELECTOR_NOT_FOUND = "is specified but does not exist";
  private static final Pattern MIN_ATTRIBUTES = Pattern.compile("At least (\\d+) attributes");
  private static final List<Class<? extends RuntimeException>> QUERY_TIMEOUTS =
      List.of(
          jakarta.persistence.QueryTimeoutException.class,
          org.hibernate.QueryTimeoutException.class,
          org.springframework.dao.QueryTimeoutException.class);
  private final FhirTrackedEntityExportAdapter trackedEntityAdapter;
  private final FhirEnrollmentExportAdapter enrollmentAdapter;
  private final TrackerExportTimeout timeout;
  private final TransactionOperations operationTransaction;

  /** Creates a reader whose operations run without a transaction of their own. */
  public FhirTrackerReader(
      FhirTrackedEntityExportAdapter trackedEntityAdapter,
      FhirEnrollmentExportAdapter enrollmentAdapter,
      TrackerExportTimeout timeout) {
    this(trackedEntityAdapter, enrollmentAdapter, timeout, withoutTransaction());
  }

  /** Creates a reader whose operations each run in a read-only transaction when none is active. */
  @Autowired
  public FhirTrackerReader(
      FhirTrackedEntityExportAdapter trackedEntityAdapter,
      FhirEnrollmentExportAdapter enrollmentAdapter,
      TrackerExportTimeout timeout,
      PlatformTransactionManager transactionManager) {
    this(trackedEntityAdapter, enrollmentAdapter, timeout, readOnlyTransaction(transactionManager));
  }

  private FhirTrackerReader(
      FhirTrackedEntityExportAdapter trackedEntityAdapter,
      FhirEnrollmentExportAdapter enrollmentAdapter,
      TrackerExportTimeout timeout,
      TransactionOperations operationTransaction) {
    this.trackedEntityAdapter = trackedEntityAdapter;
    this.enrollmentAdapter = enrollmentAdapter;
    this.timeout = timeout;
    this.operationTransaction = operationTransaction;
  }

  /**
   * Runs {@code operation} under the held or a configured new deadline, if any, and clears only its
   * own. The operation then runs in the active transaction or else in the reader's operation
   * transaction, which rolls back when it throws. Under a deadline, a query timeout, alone or as a
   * cause, becomes a {@link DeadlineExceededException} naming the budget; anything else is rethrown
   * unchanged.
   */
  public <T> T withinDeadline(@Nonnull Supplier<T> operation) {
    Objects.requireNonNull(operation, "operation");
    boolean owner = DeadlineHolder.get() == null;
    if (owner) {
      DeadlineHolder.set(timeout.newDeadline());
    }
    try {
      return inOperationTransaction(operation);
    } catch (RuntimeException e) {
      throw asDeadlineExceeded(e);
    } finally {
      if (owner) {
        DeadlineHolder.clear();
      }
    }
  }

  /** Throws {@code DeadlineExceededException} when the thread's deadline has expired. */
  public void checkpoint() {
    DeadlineHolder.checkNotExpired();
  }

  /** Finds tracked entities after a checkpoint, translating export-path errors to FHIR errors. */
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
      log.debug("Tracked entity export denied access ({})", exceptionName(e));
      throw FhirApiException.forbidden();
    } catch (NotFoundException e) {
      log.debug("Tracked entity export found no entity ({})", exceptionName(e));
      throw FhirApiException.notFound();
    } catch (BadRequestException e) {
      String message = withoutFilter(e.getMessage(), params.getFilter());
      if (hidesSelector(message, params.getProgram(), params.getTrackedEntityType())) {
        log.debug(
            "Tracked entity export cannot see the selected program or tracked entity type ({})",
            exceptionName(e));
        throw FhirApiException.forbidden();
      }
      throw translateBadRequest(e, message, params, origin);
    } catch (IllegalQueryException e) {
      throw translateIllegalQuery(
          e, withoutFilter(e.getMessage(), params.getFilter()), params, origin);
    }
  }

  /** Finds enrollments with their events after a checkpoint; a denial yields a forbidden result. */
  public EnrollmentResult findEnrollments(
      @Nonnull EnrollmentRequestParams params,
      @Nonnull HttpServletRequest request,
      @Nonnull FhirResourceType type) {
    Objects.requireNonNull(type, "type");
    checkpoint();
    try {
      return EnrollmentResult.of(enrollmentAdapter.find(params, request).page().getItems());
    } catch (ForbiddenException e) {
      log.debug("Enrollment export for {} denied access ({})", type.fhirType(), exceptionName(e));
      return EnrollmentResult.ofForbidden();
    } catch (BadRequestException e) {
      if (hidesSelector(e.getMessage(), params.getProgram())) {
        log.debug(
            "Enrollment export for {} cannot see the selected program ({})",
            type.fhirType(),
            exceptionName(e));
        return EnrollmentResult.ofForbidden();
      }
      throw unusableMapping(type, e, params.getProgram());
    } catch (IllegalQueryException e) {
      throw unusableMapping(type, e, params.getProgram());
    }
  }

  /** Runs {@code operation} in the active or else the operation transaction, rethrowing as is. */
  private <T> T inOperationTransaction(Supplier<T> operation) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      return operation.get();
    }
    AtomicReference<Exception> failure = new AtomicReference<>();
    try {
      return operationTransaction.execute(
          status -> {
            try {
              return operation.get();
            } catch (Exception e) {
              failure.set(e);
              throw e;
            }
          });
    } catch (UndeclaredThrowableException e) {
      Throwable undeclared = e.getUndeclaredThrowable();
      if (undeclared != null && undeclared == failure.get()) {
        throw rethrow(undeclared);
      }
      throw e;
    }
  }

  @SneakyThrows
  private static RuntimeException rethrow(Throwable throwable) {
    throw throwable;
  }

  /** {@code exception}, or the held deadline's timeout when it is caused by a query timeout. */
  private static RuntimeException asDeadlineExceeded(RuntimeException exception) {
    Deadline deadline = DeadlineHolder.get();
    if (deadline == null
        || exception instanceof DeadlineExceededException
        || !isOrIsCausedByQueryTimeout(exception)) {
      return exception;
    }
    log.debug("A FHIR operation query timed out under its deadline ({})", exceptionName(exception));
    return new DeadlineExceededException(deadline.budget(), exception);
  }

  /** Whether {@code exception} or a cause in its chain is a query timeout; stops at a cycle. */
  private static boolean isOrIsCausedByQueryTimeout(Throwable exception) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable t = exception; t != null && visited.add(t); t = t.getCause()) {
      for (Class<? extends RuntimeException> type : QUERY_TIMEOUTS) {
        if (type.isInstance(t)) {
          return true;
        }
      }
    }
    return false;
  }

  private static TransactionOperations readOnlyTransaction(
      PlatformTransactionManager transactionManager) {
    TransactionTemplate template =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    template.setReadOnly(true);
    return template;
  }

  /** Whether {@code message} reports a selector as missing, as it does for unreadable metadata. */
  private static boolean hidesSelector(@CheckForNull String message, UID... selectors) {
    return message != null
        && message.contains(SELECTOR_NOT_FOUND)
        && Arrays.stream(selectors)
            .anyMatch(selector -> selector != null && cites(message, selector.getValue()));
  }

  private static FhirApiException translateBadRequest(
      BadRequestException exception,
      @CheckForNull String message,
      TrackedEntityRequestParams params,
      FhirSearchOrigin origin) {
    for (Map.Entry<String, String> entry : origin.attributeToParameter().entrySet()) {
      if (cites(message, entry.getKey())) {
        log.debug(
            "Tracked entity export rejected parameter '{}' ({})",
            entry.getValue(),
            exceptionName(exception));
        return FhirApiException.invalidParameter(entry.getValue(), ATTRIBUTE_VALUE_REJECTED);
      }
    }
    return unusableMapping(
        FhirResourceType.PATIENT, exception, params.getProgram(), params.getTrackedEntityType());
  }

  private static FhirApiException translateIllegalQuery(
      IllegalQueryException exception,
      @CheckForNull String message,
      TrackedEntityRequestParams params,
      FhirSearchOrigin origin) {
    Set<String> cited = new LinkedHashSet<>();
    for (Map.Entry<String, String> entry : origin.attributeToParameter().entrySet()) {
      if (cites(message, entry.getKey())) {
        cited.add(entry.getValue());
      }
    }
    if (!cited.isEmpty()) {
      log.debug(
          "Tracked entity export rejected parameters {} ({})", cited, exceptionName(exception));
      return FhirApiException.invalidParameter(
          String.join(PARAMETER_SEPARATOR, cited), ATTRIBUTE_NOT_SEARCHABLE);
    }
    Matcher minimum = message == null ? null : MIN_ATTRIBUTES.matcher(message);
    if (minimum != null && minimum.find()) {
      List<String> names =
          origin.suppliedAttributeParameters().isEmpty()
              ? origin.configuredAttributeParameters()
              : origin.suppliedAttributeParameters();
      String named =
          names.isEmpty() ? FhirSearchParameters.ID : String.join(PARAMETER_SEPARATOR, names);
      String required =
          "At least " + minimum.group(1) + " attribute search parameters are required";
      log.debug(
          "Tracked entity export requires at least {} attribute parameters, naming {} ({})",
          minimum.group(1),
          named,
          exceptionName(exception));
      return FhirApiException.invalidParameter(
          named, names.isEmpty() ? required + NO_ATTRIBUTE_PARAMETERS : required);
    }
    return unusableMapping(
        FhirResourceType.PATIENT, exception, params.getProgram(), params.getTrackedEntityType());
  }

  private static FhirApiException unusableMapping(
      FhirResourceType type, Exception exception, UID... selectors) {
    List<String> selected =
        Arrays.stream(selectors).filter(Objects::nonNull).map(UID::getValue).toList();
    log.warn(
        "The configured FHIR mapping for {} selecting {} was rejected by the Tracker export ({})",
        type.fhirType(),
        selected,
        exceptionName(exception));
    return FhirApiException.notSupported(
        "The configured mapping for " + type.fhirType() + " cannot be used");
  }

  private static String exceptionName(Exception exception) {
    return exception.getClass().getSimpleName();
  }

  @CheckForNull
  private static String withoutFilter(@CheckForNull String message, @CheckForNull String filter) {
    return message == null || filter == null || filter.isEmpty()
        ? message
        : message.replace(filter, " ");
  }

  private static boolean cites(@CheckForNull String message, String uid) {
    if (message == null || uid == null || uid.isEmpty()) {
      return false;
    }
    for (int index = message.indexOf(uid); index >= 0; index = message.indexOf(uid, index + 1)) {
      int end = index + uid.length();
      if ((index == 0 || !Character.isLetterOrDigit(message.charAt(index - 1)))
          && (end == message.length() || !Character.isLetterOrDigit(message.charAt(end)))) {
        return true;
      }
    }
    return false;
  }

  /** The FHIR search parameters behind the attribute filters of one tracked entity request. */
  public record FhirSearchOrigin(
      Map<String, String> attributeToParameter,
      List<String> suppliedAttributeParameters,
      List<String> configuredAttributeParameters) {
    /** Copies every component in order into an unmodifiable collection; null becomes empty. */
    public FhirSearchOrigin {
      attributeToParameter = copyOf(attributeToParameter);
      suppliedAttributeParameters = copyOf(suppliedAttributeParameters);
      configuredAttributeParameters = copyOf(configuredAttributeParameters);
    }

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

    private static List<String> copyOf(@CheckForNull List<String> source) {
      return source == null ? List.of() : List.copyOf(source);
    }
  }

  /** The enrollments one export call returned, or the export path's denial of access. */
  public record EnrollmentResult(List<Enrollment> enrollments, boolean forbidden) {
    /** Copies {@code enrollments} unmodifiably; a forbidden result with enrollments is rejected. */
    public EnrollmentResult {
      enrollments = enrollments == null ? List.of() : List.copyOf(enrollments);
      if (forbidden && !enrollments.isEmpty()) {
        throw new IllegalArgumentException("A forbidden result holds no enrollments");
      }
    }

    /** Returns a result that is not forbidden; {@code null} enrollments become empty. */
    public static EnrollmentResult of(@CheckForNull List<Enrollment> enrollments) {
      return new EnrollmentResult(enrollments, false);
    }

    public static EnrollmentResult ofForbidden() {
      return new EnrollmentResult(List.of(), true);
    }
  }
}
