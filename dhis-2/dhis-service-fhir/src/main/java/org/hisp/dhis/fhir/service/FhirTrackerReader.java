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

import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.springframework.transaction.support.TransactionOperations.withoutTransaction;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.SocketTimeoutException;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.*;
import javax.annotation.*;
import javax.sql.DataSource;
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
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Reads Tracker data for the FHIR API, translating export-path errors into FHIR errors. */
@Slf4j
@Service
public class FhirTrackerReader {
  static final Duration NETWORK_TIMEOUT_GRACE = Duration.ofSeconds(2);

  static final String PARAMETER_SEPARATOR = ", ";
  static final String ATTRIBUTE_VALUE_REJECTED =
      "The value is not accepted by the mapped attribute";
  static final String ATTRIBUTE_NOT_SEARCHABLE =
      "The attribute cannot be searched outside the user's capture scope";
  static final String NO_ATTRIBUTE_PARAMETERS =
      " and none is configured, so " + FhirSearchParameters.ID + " is required";
  static final String SELECTOR_NOT_FOUND = "is specified but does not exist";
  private static final Pattern MIN_ATTRIBUTES = Pattern.compile("At least (\\d+) attributes");
  private static final List<Class<? extends Exception>> QUERY_TIMEOUTS =
      List.of(
          jakarta.persistence.QueryTimeoutException.class,
          org.hibernate.QueryTimeoutException.class,
          org.springframework.dao.QueryTimeoutException.class);
  private static final List<Class<? extends Exception>> NETWORK_TIMEOUTS =
      List.of(SocketTimeoutException.class);
  private static final ThreadLocal<OperationScope> OPERATION = new ThreadLocal<>();
  private final FhirTrackedEntityExportAdapter trackedEntityAdapter;
  private final FhirEnrollmentExportAdapter enrollmentAdapter;
  private final TrackerExportTimeout timeout;
  private final TransactionOperations operationTransaction;
  @CheckForNull private final DataSource dataSource;

  /** Creates a reader whose operations run without a transaction of their own. */
  public FhirTrackerReader(
      FhirTrackedEntityExportAdapter trackedEntityAdapter,
      FhirEnrollmentExportAdapter enrollmentAdapter,
      TrackerExportTimeout timeout) {
    this(trackedEntityAdapter, enrollmentAdapter, timeout, withoutTransaction(), null);
  }

  /** Creates a reader whose operations each run in a read-only transaction when none is active. */
  public FhirTrackerReader(
      FhirTrackedEntityExportAdapter trackedEntityAdapter,
      FhirEnrollmentExportAdapter enrollmentAdapter,
      TrackerExportTimeout timeout,
      PlatformTransactionManager transactionManager) {
    this(
        trackedEntityAdapter,
        enrollmentAdapter,
        timeout,
        readOnlyTransaction(transactionManager),
        null);
  }

  /** Creates a transactional reader whose deadline bounds the network timeout of its connection. */
  @Autowired
  public FhirTrackerReader(
      FhirTrackedEntityExportAdapter trackedEntityAdapter,
      FhirEnrollmentExportAdapter enrollmentAdapter,
      TrackerExportTimeout timeout,
      PlatformTransactionManager transactionManager,
      DataSource dataSource) {
    this(
        trackedEntityAdapter,
        enrollmentAdapter,
        timeout,
        readOnlyTransaction(transactionManager),
        Objects.requireNonNull(dataSource, "dataSource"));
  }

  private FhirTrackerReader(
      FhirTrackedEntityExportAdapter trackedEntityAdapter,
      FhirEnrollmentExportAdapter enrollmentAdapter,
      TrackerExportTimeout timeout,
      TransactionOperations operationTransaction,
      @CheckForNull DataSource dataSource) {
    this.trackedEntityAdapter = trackedEntityAdapter;
    this.enrollmentAdapter = enrollmentAdapter;
    this.timeout = timeout;
    this.operationTransaction = operationTransaction;
    this.dataSource = dataSource;
  }

  /** Runs {@code operation} under one deadline, in the reader's transaction if it has one. */
  public <T> T withinDeadline(@Nonnull Supplier<T> operation) {
    Objects.requireNonNull(operation, "operation");
    if (OPERATION.get() != null) {
      return operation.get();
    }
    boolean owner = DeadlineHolder.get() == null;
    if (owner) {
      DeadlineHolder.set(timeout.newDeadline());
    }
    OperationScope scope = new OperationScope();
    OPERATION.set(scope);
    try {
      return inOperationTransaction(operation, scope);
    } catch (RuntimeException e) {
      throw asDeadlineExceeded(e);
    } finally {
      scope.restore();
      OPERATION.remove();
      if (owner) {
        DeadlineHolder.clear();
      }
    }
  }

  /** Throws {@code DeadlineExceededException} once expired, else re-bounds the network timeout. */
  public void checkpoint() {
    DeadlineHolder.checkNotExpired();
    OperationScope scope = OPERATION.get();
    if (scope != null) {
      scope.refresh();
    }
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

  private <T> T inOperationTransaction(Supplier<T> operation, OperationScope scope) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      boundNetworkTimeout(scope, false);
      return operation.get();
    }
    AtomicReference<Exception> failure = new AtomicReference<>();
    try {
      return operationTransaction.execute(
          status -> {
            boundNetworkTimeout(scope, status.isNewTransaction());
            try {
              return operation.get();
            } catch (Exception e) {
              failure.set(e);
              throw e;
            }
          });
    } catch (RuntimeException e) {
      Exception own = failure.get();
      if (own == null || own == e) {
        throw e;
      }
      if (!(e instanceof UndeclaredThrowableException undeclared
          && undeclared.getUndeclaredThrowable() == own)) {
        own.addSuppressed(e);
      }
      throw rethrow(own);
    }
  }

  private void boundNetworkTimeout(OperationScope scope, boolean newTransaction) {
    Deadline deadline = DeadlineHolder.get();
    if (dataSource == null
        || deadline == null
        || !(TransactionSynchronizationManager.getResource(dataSource)
            instanceof ConnectionHolder holder)
        || holder.getConnectionHandle() == null) {
      return;
    }
    scope.bound(holder.getConnection(), deadline);
    if (newTransaction
        && scope.isBound()
        && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(scope);
    }
  }

  private static int networkTimeout(Deadline deadline, int previous) {
    long bound = Math.max(deadline.remaining().toMillis(), 0) + NETWORK_TIMEOUT_GRACE.toMillis();
    long ceiling = previous > 0 ? previous : Integer.MAX_VALUE;
    return (int) Math.max(1, Math.min(ceiling, bound));
  }

  private static void setNetworkTimeout(Connection connection, int millis) {
    try {
      connection.setNetworkTimeout(Runnable::run, millis);
    } catch (SQLException e) {
      networkTimeoutUnchanged(e);
    }
  }

  private static void networkTimeoutUnchanged(SQLException exception) {
    log.debug(
        "The network timeout of a FHIR operation connection is unchanged ({})",
        exceptionName(exception));
  }

  @SneakyThrows
  private static RuntimeException rethrow(Throwable throwable) {
    throw throwable;
  }

  private static RuntimeException asDeadlineExceeded(RuntimeException exception) {
    Deadline deadline = DeadlineHolder.get();
    if (deadline == null
        || exception instanceof DeadlineExceededException
        || !(isOrIsCausedBy(exception, QUERY_TIMEOUTS)
            || (deadline.isExpired() && isOrIsCausedBy(exception, NETWORK_TIMEOUTS)))) {
      return exception;
    }
    log.debug("A FHIR operation query timed out under its deadline ({})", exceptionName(exception));
    return new DeadlineExceededException(deadline.budget(), exception);
  }

  private static boolean isOrIsCausedBy(
      Throwable exception, List<Class<? extends Exception>> types) {
    Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable t = exception; t != null && visited.add(t); t = t.getCause()) {
      for (Class<? extends Exception> type : types) {
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
    return unusableMapping(PATIENT, exception, params.getProgram(), params.getTrackedEntityType());
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
    return unusableMapping(PATIENT, exception, params.getProgram(), params.getTrackedEntityType());
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

  private static final class OperationScope implements TransactionSynchronization {
    @CheckForNull private Connection connection;
    private int previous;

    void bound(Connection target, Deadline deadline) {
      try {
        int current = target.getNetworkTimeout();
        target.setNetworkTimeout(Runnable::run, networkTimeout(deadline, current));
        connection = target;
        previous = current;
      } catch (SQLException e) {
        networkTimeoutUnchanged(e);
      }
    }

    boolean isBound() {
      return connection != null;
    }

    void refresh() {
      Deadline deadline = DeadlineHolder.get();
      if (connection != null && deadline != null) {
        setNetworkTimeout(connection, networkTimeout(deadline, previous));
      }
    }

    void restore() {
      Connection bounded = connection;
      connection = null;
      if (bounded != null) {
        setNetworkTimeout(bounded, previous);
      }
    }

    /** Restores the previous network timeout after commit or rollback. */
    @Override
    public void afterCompletion(int status) {
      restore();
    }
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
