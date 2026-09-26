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
package org.hisp.dhis.fhir.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;
import org.hisp.dhis.common.OpenApi;

final class FhirOpenApi {
  static final String FHIR_JSON = "application/fhir+json";

  private FhirOpenApi() {}

  enum FhirPatientType {
    Patient
  }

  enum FhirEncounterType {
    Encounter
  }

  enum FhirImmunizationType {
    Immunization
  }

  enum FhirObservationType {
    Observation
  }

  enum FhirBundleType {
    Bundle
  }

  enum FhirCapabilityStatementType {
    CapabilityStatement
  }

  record FhirMeta(
      @OpenApi.Description("Last update of the source Tracker object.") Date lastUpdated) {}

  record FhirReference(
      @OpenApi.Description(
              "`Patient/{trackedEntityUid}` or `Encounter/{enrollmentUid}-{eventUid}`.")
          String reference) {}

  record FhirCoding(
      @JsonProperty String system, @JsonProperty String code, @JsonProperty String display) {}

  record FhirCodeableConcept(@JsonProperty List<FhirCoding> coding) {}

  record FhirPatientResource(
      @JsonProperty(required = true)
          @OpenApi.Description("See https://hl7.org/fhir/R4/patient.html")
          FhirPatientType resourceType,
      @OpenApi.Description("The tracked entity UID") String id,
      @JsonProperty FhirMeta meta) {}

  record FhirEncounterResource(
      @JsonProperty(required = true)
          @OpenApi.Description("See https://hl7.org/fhir/R4/encounter.html")
          FhirEncounterType resourceType,
      @OpenApi.Description("`{enrollmentUid}-{eventUid}`") String id,
      @JsonProperty FhirMeta meta,
      @JsonProperty(required = true)
          @OpenApi.Description("`planned`, `in-progress`, `finished` or `cancelled`.")
          String status,
      @JsonProperty(value = "class", required = true) FhirCoding encounterClass,
      @JsonProperty FhirReference subject) {}

  record FhirImmunizationResource(
      @JsonProperty(required = true)
          @OpenApi.Description("See https://hl7.org/fhir/R4/immunization.html")
          FhirImmunizationType resourceType,
      @OpenApi.Description("`{enrollmentUid}-{eventUid}-{dataElementUid}`") String id,
      @JsonProperty FhirMeta meta,
      @JsonProperty(required = true) @OpenApi.Description("`completed` or `not-done`.")
          String status,
      @JsonProperty(required = true) FhirCodeableConcept vaccineCode,
      @JsonProperty(required = true) FhirReference patient,
      @JsonProperty(required = true) Date occurrenceDateTime) {}

  record FhirObservationResource(
      @JsonProperty(required = true)
          @OpenApi.Description("See https://hl7.org/fhir/R4/observation.html")
          FhirObservationType resourceType,
      @OpenApi.Description("`{enrollmentUid}-{eventUid}-{dataElementUid}`") String id,
      @JsonProperty FhirMeta meta,
      @JsonProperty(required = true)
          @OpenApi.Description("`final`, `preliminary`, `registered` or `cancelled`.")
          String status,
      @JsonProperty(required = true) FhirCodeableConcept code,
      @JsonProperty FhirReference subject) {}

  record FhirSearchsetBundle(
      @JsonProperty(required = true) @OpenApi.Description("See https://hl7.org/fhir/R4/bundle.html")
          FhirBundleType resourceType,
      @JsonProperty(required = true) @OpenApi.Description("Always `searchset`.") String type,
      @OpenApi.Description("Number of matches; absent for `Patient` search.") Integer total,
      @JsonProperty List<FhirBundleLink> link,
      @JsonProperty List<FhirBundleEntry> entry) {}

  record FhirBundleLink(
      @OpenApi.Description(
              "`self`, `next` when more results exist, or `previous` when `_page` > 1.")
          String relation,
      @JsonProperty String url) {}

  record FhirBundleEntry(
      @OpenApi.Description("`{base}/api/fhir/{Type}/{id}`.") String fullUrl,
      @OpenApi.Property({
            FhirPatientResource.class,
            FhirEncounterResource.class,
            FhirImmunizationResource.class,
            FhirObservationResource.class
          })
          Object resource,
      @JsonProperty FhirBundleEntrySearch search) {}

  record FhirBundleEntrySearch(@OpenApi.Description("Always `match`.") String mode) {}

  record FhirCapabilityStatementResource(
      @JsonProperty(required = true)
          @OpenApi.Description("See https://hl7.org/fhir/R4/capabilitystatement.html")
          FhirCapabilityStatementType resourceType,
      @JsonProperty(required = true) @OpenApi.Description("Always `active`.") String status,
      @JsonProperty(required = true)
          @OpenApi.Description("Latest update of the usable mappings, or the request time if none.")
          Date date,
      @JsonProperty(required = true) @OpenApi.Description("Always `instance`.") String kind,
      @JsonProperty(required = true) @OpenApi.Description("Always `4.0.1`.") String fhirVersion,
      @JsonProperty(required = true) @OpenApi.Description("Always `json`.") List<String> format,
      @JsonProperty FhirCapabilityImplementation implementation,
      @OpenApi.Description("One entry with mode `server`.") List<FhirCapabilityRest> rest) {}

  record FhirCapabilityImplementation(
      @OpenApi.Description("Always `DHIS2 FHIR R4 read-only API`.") String description,
      @OpenApi.Description("`{base}/api/fhir`.") String url) {}

  record FhirCapabilityRest(
      @JsonProperty String mode,
      @OpenApi.Description("One entry per resource type with a usable mapping.")
          List<FhirCapabilityResource> resource) {}

  record FhirCapabilityResource(
      @JsonProperty String type,
      @OpenApi.Description("Codes `read` and `search-type`.")
          List<FhirCapabilityInteraction> interaction,
      @OpenApi.Description("`_id` and the other parameters the configured targets support.")
          List<FhirCapabilitySearchParam> searchParam,
      @OpenApi.Description("`everything` on `Patient`.") List<FhirCapabilityOperation> operation) {}

  record FhirCapabilityInteraction(@JsonProperty String code) {}

  record FhirCapabilitySearchParam(
      @JsonProperty String name,
      @OpenApi.Description("`token`, `string`, `date` or `reference`.") String type) {}

  record FhirCapabilityOperation(@JsonProperty String name, @JsonProperty String definition) {}

  interface FhirFormatParameter {
    @JsonProperty("_format")
    @OpenApi.Description("`json`, `application/json` or `application/fhir+json`.")
    String getFormat();
  }

  interface FhirPagingParameters extends FhirFormatParameter {
    @JsonProperty("_id")
    @OpenApi.Description("Comma-separated logical ids.")
    String getId();

    @JsonProperty("_count")
    @OpenApi.Description(
        "Page size, a positive integer; default `50`. For `Patient`, at most the"
            + " `KeyTrackedEntityMaxLimit` system setting when that is positive.")
    Integer getCount();

    @JsonProperty("_page")
    @OpenApi.Description("Page number, a positive integer; default `1`.")
    Integer getPage();
  }

  interface FhirPatientSearchParameters extends FhirPagingParameters {
    @OpenApi.Description("`[system|]value`; `system` may be omitted when one identifier is mapped.")
    String getIdentifier();

    @OpenApi.Description("Case-insensitive starts-with on the mapped family name.")
    String getFamily();

    @OpenApi.Description("Case-insensitive starts-with on the mapped given name.")
    String getGiven();

    @OpenApi.Description("`[eq|ge|le|gt|lt]yyyy-MM-dd` on the mapped birth date.")
    String getBirthdate();

    @OpenApi.Description("Comma-separated `male|female|other|unknown` on the mapped gender.")
    String getGender();
  }

  interface FhirImmunizationSearchParameters extends FhirPagingParameters {
    @OpenApi.Description("`{uid}` or `Patient/{uid}`: the tracked entity of the resources.")
    String getPatient();
  }

  interface FhirEncounterSearchParameters extends FhirImmunizationSearchParameters {
    @OpenApi.Description("Alias of `patient`; not combinable with it.")
    String getSubject();
  }

  interface FhirObservationSearchParameters extends FhirEncounterSearchParameters {
    @OpenApi.Description("Comma-separated `[system|]code`.")
    String getCode();
  }
}
