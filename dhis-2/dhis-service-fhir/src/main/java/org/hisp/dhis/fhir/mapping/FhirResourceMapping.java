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
import com.fasterxml.jackson.dataformat.xml.annotation.*;
import java.util.*;
import lombok.Setter;
import org.hisp.dhis.common.*;
import org.hisp.dhis.program.*;
import org.hisp.dhis.schema.annotation.Property;
import org.hisp.dhis.trackedentity.TrackedEntityType;

/** Metadata object describing how Tracker data is turned into one type of FHIR R4 resource. */
@Setter
@JacksonXmlRootElement(localName = "fhirResourceMapping", namespace = DxfNamespaces.DXF_2_0)
public class FhirResourceMapping extends BaseIdentifiableObject implements MetadataObject {
  private FhirResourceType resourceType;
  private TrackedEntityType trackedEntityType;
  private Program program;
  private ProgramStage programStage;
  private List<FhirFieldMapping> fieldMappings = new ArrayList<>();

  public FhirResourceMapping() {}

  @JsonProperty
  @JacksonXmlProperty(namespace = DxfNamespaces.DXF_2_0)
  public FhirResourceType getResourceType() {
    return resourceType;
  }

  @JsonProperty
  @JsonSerialize(as = BaseIdentifiableObject.class)
  @JacksonXmlProperty(namespace = DxfNamespaces.DXF_2_0)
  public TrackedEntityType getTrackedEntityType() {
    return trackedEntityType;
  }

  /** Returns the program whose enrollments are read, serialised as {@link IdentifiableObject}. */
  @JsonProperty
  @JsonSerialize(as = IdentifiableObject.class)
  @JacksonXmlProperty(namespace = DxfNamespaces.DXF_2_0)
  public Program getProgram() {
    return program;
  }

  @JsonProperty
  @JsonSerialize(as = BaseIdentifiableObject.class)
  @JacksonXmlProperty(namespace = DxfNamespaces.DXF_2_0)
  public ProgramStage getProgramStage() {
    return programStage;
  }

  /** Returns the mapping's own, never-null list of entries in their configured order. */
  @JsonProperty
  @JacksonXmlElementWrapper(localName = "fieldMappings", namespace = DxfNamespaces.DXF_2_0)
  @JacksonXmlProperty(localName = "fieldMapping", namespace = DxfNamespaces.DXF_2_0)
  @Property(required = Property.Value.FALSE)
  public List<FhirFieldMapping> getFieldMappings() {
    return fieldMappings;
  }

  /** Stores the given list as is, or a new empty list for {@code null}. */
  public void setFieldMappings(List<FhirFieldMapping> fieldMappings) {
    this.fieldMappings = fieldMappings == null ? new ArrayList<>() : fieldMappings;
  }
}
