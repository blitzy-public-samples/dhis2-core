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

import static org.hisp.dhis.common.DxfNamespaces.DXF_2_0;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.*;
import java.io.Serializable;
import java.util.*;
import lombok.*;

/** One entry of a FHIR resource mapping: the FHIR element it fills and its value's source. */
@Data
@NoArgsConstructor
@JacksonXmlRootElement(localName = "fieldMapping", namespace = DXF_2_0)
public class FhirFieldMapping implements Serializable {
  private static final long serialVersionUID = 1L;

  @JsonProperty
  @JacksonXmlProperty(namespace = DXF_2_0)
  private FhirTargetField target;

  @JsonProperty
  @JacksonXmlProperty(namespace = DXF_2_0)
  private FhirSourceType sourceType;

  @JsonProperty
  @JacksonXmlProperty(namespace = DXF_2_0)
  private String source;

  @JsonProperty
  @JacksonXmlProperty(namespace = DXF_2_0)
  private String system;

  @JsonProperty
  @JacksonXmlProperty(namespace = DXF_2_0)
  private String code;

  @JsonProperty
  @JacksonXmlProperty(namespace = DXF_2_0)
  private String display;

  @JsonProperty
  @JacksonXmlProperty(namespace = DXF_2_0)
  private String unit;

  @JsonProperty
  @JacksonXmlProperty(namespace = DXF_2_0)
  private Map<String, String> valueMap;

  /** Creates an independent copy of {@code other}, with a new value map of the same entries. */
  public FhirFieldMapping(FhirFieldMapping other) {
    this(other, other.valueMap == null ? null : new LinkedHashMap<>(other.valueMap));
  }

  private FhirFieldMapping(FhirFieldMapping other, Map<String, String> valueMap) {
    this.target = other.target;
    this.sourceType = other.sourceType;
    this.source = other.source;
    this.system = other.system;
    this.code = other.code;
    this.display = other.display;
    this.unit = other.unit;
    this.valueMap = valueMap;
  }

  /** Returns an unmodifiable copy of {@code entry}, or {@code entry} when it already is one. */
  public static FhirFieldMapping unmodifiableCopy(FhirFieldMapping entry) {
    return entry instanceof Unmodifiable ? entry : new Unmodifiable(entry);
  }

  private static final class Unmodifiable extends FhirFieldMapping {
    private static final long serialVersionUID = 1L;

    private Unmodifiable(FhirFieldMapping entry) {
      super(
          entry,
          entry.valueMap == null
              ? null
              : Collections.unmodifiableMap(new LinkedHashMap<>(entry.valueMap)));
    }

    @Override
    public void setTarget(FhirTargetField target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setSourceType(FhirSourceType sourceType) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setSource(String source) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setSystem(String system) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setCode(String code) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setDisplay(String display) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setUnit(String unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void setValueMap(Map<String, String> valueMap) {
      throw new UnsupportedOperationException();
    }
  }
}
