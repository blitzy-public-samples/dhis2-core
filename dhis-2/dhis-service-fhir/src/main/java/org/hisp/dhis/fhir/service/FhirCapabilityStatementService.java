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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.hisp.dhis.fhir.search.FhirSearchParameters;
import org.hisp.dhis.fhir.search.FhirSearchParameters.Operation;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementKind;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.hl7.fhir.r4.model.Enumerations;
import org.springframework.stereotype.Service;

/**
 * Builds the FHIR R4 {@link CapabilityStatement} returned by {@code GET /api/fhir/metadata}.
 *
 * <p>The statement is derived from the usable resource mappings that {@link
 * FhirResourceMappingService#resolveAll()} returns and contains:
 *
 * <ul>
 *   <li>{@code status=active}, {@code kind=instance}, {@code fhirVersion=4.0.1} and {@code
 *       format=[json]};
 *   <li>{@code date}: the latest {@code lastUpdated} of the usable mappings, or the time of the
 *       request when no usable mapping has one;
 *   <li>{@code implementation}: the description {@value #IMPLEMENTATION_DESCRIPTION} and the URL
 *       {@code {request base}/api/fhir};
 *   <li>one {@code rest} entry in {@code server} mode with one {@code resource} per {@link
 *       FhirResourceType} that has at least one usable mapping, in {@link FhirResourceType} order.
 *       Each resource declares the interactions {@code read} and {@code search-type} and one {@code
 *       searchParam} per parameter of {@link FhirSearchParameters#supportedParameters}, typed by
 *       {@link FhirSearchParameters#typeOf}. {@code Patient} also declares the operation {@code
 *       everything} with the definition {@value #EVERYTHING_OPERATION_DEFINITION}.
 * </ul>
 *
 * <p>Resource types without a usable mapping are omitted. The service reads mapping configuration
 * only; it performs no Tracker read and runs no deadline.
 *
 * <pre>{@code
 * CapabilityStatement statement = capabilityStatementService.capabilities(request);
 * return serializer.ok(statement);
 * }</pre>
 */
@Slf4j
@Service
public class FhirCapabilityStatementService {
  /** The {@code implementation.description} of the statement. */
  public static final String IMPLEMENTATION_DESCRIPTION = "DHIS2 FHIR R4 read-only API";

  /** The only {@code format} the statement declares. */
  public static final String FORMAT_JSON = "json";

  /** The name of the {@code Patient/$everything} operation. */
  public static final String EVERYTHING_OPERATION_NAME = "everything";

  /** The canonical definition of the {@code Patient/$everything} operation. */
  public static final String EVERYTHING_OPERATION_DEFINITION =
      "http://hl7.org/fhir/OperationDefinition/Patient-everything";

  private final FhirResourceMappingService mappingService;

  private final FhirSearchParameters parameters;

  /**
   * Creates the service.
   *
   * @param mappingService resolves the usable resource mappings
   * @param parameters validates the query and lists the supported search parameters
   * @throws NullPointerException if an argument is {@code null}
   */
  public FhirCapabilityStatementService(
      FhirResourceMappingService mappingService, FhirSearchParameters parameters) {
    this.mappingService = Objects.requireNonNull(mappingService, "mappingService");
    this.parameters = Objects.requireNonNull(parameters, "parameters");
  }

  /**
   * Builds the CapabilityStatement of the FHIR API from the usable resource mappings.
   *
   * <p>The query is validated first: only {@code _format} with a JSON value is accepted.
   *
   * @param request the current HTTP request; supplies the query and the base URL
   * @return a new CapabilityStatement
   * @throws org.hisp.dhis.fhir.FhirApiException {@code 400 invalid} naming the first parameter
   *     other than a valid {@code _format}
   * @throws NullPointerException if {@code request} is {@code null}
   */
  public CapabilityStatement capabilities(HttpServletRequest request) {
    Objects.requireNonNull(request, "request");
    parameters.checkFormatOnly(Operation.METADATA, request);

    List<ResolvedMapping> mappings = mappingService.resolveAll();
    Map<FhirResourceType, List<ResolvedMapping>> mappingsByType = groupByType(mappings);

    CapabilityStatement statement = new CapabilityStatement();
    statement
        .setStatus(Enumerations.PublicationStatus.ACTIVE)
        .setDate(Date.from(latestUpdate(mappings)))
        .setKind(CapabilityStatementKind.INSTANCE)
        .setFhirVersion(Enumerations.FHIRVersion._4_0_1)
        .addFormat(FORMAT_JSON);
    statement
        .getImplementation()
        .setDescription(IMPLEMENTATION_DESCRIPTION)
        .setUrl(FhirEventResourceService.fhirBase(request));

    CapabilityStatementRestComponent rest =
        statement.addRest().setMode(RestfulCapabilityMode.SERVER);
    for (FhirResourceType type : FhirResourceType.values()) {
      List<ResolvedMapping> mappingsOfType = mappingsByType.get(type);
      if (mappingsOfType != null && !mappingsOfType.isEmpty()) {
        addResource(rest, type, mappingsOfType);
      }
    }

    log.debug(
        "Built FHIR CapabilityStatement with resource types {} from {} usable mappings",
        mappingsByType.keySet(),
        mappings.size());
    return statement;
  }

  /**
   * Appends the {@code rest.resource} entry of a resource type: its interactions, its search
   * parameters and, for {@code Patient}, the {@code everything} operation.
   */
  private void addResource(
      CapabilityStatementRestComponent rest,
      FhirResourceType type,
      List<ResolvedMapping> mappingsOfType) {
    CapabilityStatementRestResourceComponent resource = rest.addResource().setType(type.fhirType());
    resource.addInteraction().setCode(TypeRestfulInteraction.READ);
    resource.addInteraction().setCode(TypeRestfulInteraction.SEARCHTYPE);
    for (String name : parameters.supportedParameters(type, mappingsOfType)) {
      resource.addSearchParam().setName(name).setType(FhirSearchParameters.typeOf(name));
    }
    if (type == FhirResourceType.PATIENT) {
      resource
          .addOperation()
          .setName(EVERYTHING_OPERATION_NAME)
          .setDefinition(EVERYTHING_OPERATION_DEFINITION);
    }
  }

  /**
   * Groups mappings by resource type, keeping their order within each type; {@code null} mappings
   * and mappings without a resource type are skipped.
   */
  private static Map<FhirResourceType, List<ResolvedMapping>> groupByType(
      List<ResolvedMapping> mappings) {
    Map<FhirResourceType, List<ResolvedMapping>> byType = new EnumMap<>(FhirResourceType.class);
    for (ResolvedMapping mapping : mappings) {
      if (mapping != null && mapping.resourceType() != null) {
        byType.computeIfAbsent(mapping.resourceType(), type -> new ArrayList<>()).add(mapping);
      }
    }
    return byType;
  }

  /**
   * Returns the latest non-null {@code lastUpdated} of the mappings, or the current time when none
   * has one.
   */
  private static Instant latestUpdate(List<ResolvedMapping> mappings) {
    return mappings.stream()
        .filter(Objects::nonNull)
        .map(ResolvedMapping::lastUpdated)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElseGet(Instant::now);
  }
}
