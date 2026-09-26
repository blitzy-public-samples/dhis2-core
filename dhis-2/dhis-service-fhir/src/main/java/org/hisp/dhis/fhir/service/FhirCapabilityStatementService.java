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
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.fhir.mapping.*;
import org.hisp.dhis.fhir.mapping.FhirResourceMappingService.ResolvedMapping;
import org.hisp.dhis.fhir.search.FhirSearchParameters;
import org.hisp.dhis.fhir.search.FhirSearchParameters.Operation;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.CapabilityStatement.*;
import org.springframework.stereotype.Service;

/** Builds the FHIR R4 {@link CapabilityStatement} of {@code /api/fhir/metadata} from mappings. */
@Slf4j
@Service
public class FhirCapabilityStatementService {
  public static final String IMPLEMENTATION_DESCRIPTION = "DHIS2 FHIR R4 read-only API";
  public static final String FORMAT_JSON = "json";
  public static final String EVERYTHING_OPERATION_NAME = "everything";
  public static final String EVERYTHING_OPERATION_DEFINITION =
      "http://hl7.org/fhir/OperationDefinition/Patient-everything";
  private final FhirResourceMappingService mappingService;
  private final FhirSearchParameters parameters;

  public FhirCapabilityStatementService(
      FhirResourceMappingService mappingService, FhirSearchParameters parameters) {
    this.mappingService = Objects.requireNonNull(mappingService, "mappingService");
    this.parameters = Objects.requireNonNull(parameters, "parameters");
  }

  /** Builds the statement from the usable mappings after accepting only a JSON {@code _format}. */
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

  private static Instant latestUpdate(List<ResolvedMapping> mappings) {
    return mappings.stream()
        .filter(Objects::nonNull)
        .map(ResolvedMapping::lastUpdated)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElseGet(Instant::now);
  }
}
