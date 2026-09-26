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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.ArrayList;
import java.util.List;
import lombok.Setter;
import org.hisp.dhis.common.BaseIdentifiableObject;
import org.hisp.dhis.common.DxfNamespaces;
import org.hisp.dhis.common.IdentifiableObject;
import org.hisp.dhis.common.MetadataObject;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.schema.annotation.Property;
import org.hisp.dhis.trackedentity.TrackedEntityType;

/**
 * Metadata object that describes how DHIS2 Tracker data is turned into one type of FHIR R4
 * resource.
 *
 * <p>A mapping names the {@link #getResourceType() FHIR resource type} it produces and the Tracker
 * metadata it reads from: the {@link #getTrackedEntityType() tracked entity type} of the patients,
 * and, for resources built from events, the {@link #getProgram() program} and {@link
 * #getProgramStage() program stage} whose events supply the data. Its {@link #getFieldMappings()
 * field mappings} list which tracked entity attribute, data element or constant coding fills each
 * FHIR element.
 *
 * <p>Besides the identifiable properties inherited from {@link BaseIdentifiableObject} (identity,
 * code, name, audit fields, translations, sharing and attribute values), the object carries exactly
 * the five properties declared here. Referenced metadata is serialised as a reference limited to
 * its identifiable properties, and is accepted as an identifier reference ({@code {"id": "..."}})
 * on input.
 *
 * <p>This class holds state only. Consistency rules between the resource type, the referenced
 * metadata and the field mappings are checked by {@code FhirResourceMappingValidator}.
 */
@Setter
@JacksonXmlRootElement(localName = "fhirResourceMapping", namespace = DxfNamespaces.DXF_2_0)
public class FhirResourceMapping extends BaseIdentifiableObject implements MetadataObject {

  /** The FHIR resource type produced by this mapping. */
  private FhirResourceType resourceType;

  /** The tracked entity type whose tracked entities are the subjects of the produced resources. */
  private TrackedEntityType trackedEntityType;

  /** The program whose enrollments are read, or {@code null} when none is configured. */
  private Program program;

  /** The program stage whose events are read, or {@code null} when none is configured. */
  private ProgramStage programStage;

  /** The entries that fill the FHIR elements of the produced resources; never {@code null}. */
  private List<FhirFieldMapping> fieldMappings = new ArrayList<>();

  /** Creates an empty mapping with no field mappings. */
  public FhirResourceMapping() {}

  /**
   * Returns the FHIR resource type produced by this mapping.
   *
   * @return the resource type, or {@code null} when not yet set
   */
  @JsonProperty
  @JacksonXmlProperty(namespace = DxfNamespaces.DXF_2_0)
  public FhirResourceType getResourceType() {
    return resourceType;
  }

  /**
   * Returns the tracked entity type whose tracked entities are the subjects of the produced
   * resources.
   *
   * @return the tracked entity type, or {@code null} when not yet set
   */
  @JsonProperty
  @JsonSerialize(as = BaseIdentifiableObject.class)
  @JacksonXmlProperty(namespace = DxfNamespaces.DXF_2_0)
  public TrackedEntityType getTrackedEntityType() {
    return trackedEntityType;
  }

  /**
   * Returns the program whose enrollments are read. Patient mappings may name a program to include
   * its program attributes; mappings of event-derived resource types name the program of their
   * program stage.
   *
   * <p>The program is serialised through its {@link IdentifiableObject} properties: {@link Program}
   * is a {@code BaseMetadataObject}, not a {@link BaseIdentifiableObject}.
   *
   * @return the program, or {@code null} when none is configured
   */
  @JsonProperty
  @JsonSerialize(as = IdentifiableObject.class)
  @JacksonXmlProperty(namespace = DxfNamespaces.DXF_2_0)
  public Program getProgram() {
    return program;
  }

  /**
   * Returns the program stage whose events supply the data of event-derived resources.
   *
   * @return the program stage, or {@code null} when none is configured
   */
  @JsonProperty
  @JsonSerialize(as = BaseIdentifiableObject.class)
  @JacksonXmlProperty(namespace = DxfNamespaces.DXF_2_0)
  public ProgramStage getProgramStage() {
    return programStage;
  }

  /**
   * Returns the entries that fill the FHIR elements of the produced resources, in their configured
   * order. The returned list is the mapping's own list, so changes to it change the mapping.
   *
   * @return the field mappings, never {@code null}; empty when none are configured
   */
  @JsonProperty
  @JacksonXmlElementWrapper(localName = "fieldMappings", namespace = DxfNamespaces.DXF_2_0)
  @JacksonXmlProperty(localName = "fieldMapping", namespace = DxfNamespaces.DXF_2_0)
  @Property(required = Property.Value.FALSE)
  public List<FhirFieldMapping> getFieldMappings() {
    return fieldMappings;
  }

  /**
   * Replaces the entries that fill the FHIR elements of the produced resources. The given list is
   * used as is; {@code null} is stored as an empty list.
   *
   * @param fieldMappings the new field mappings, or {@code null} for none
   */
  public void setFieldMappings(List<FhirFieldMapping> fieldMappings) {
    this.fieldMappings = fieldMappings == null ? new ArrayList<>() : fieldMappings;
  }
}
