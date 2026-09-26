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
package org.hisp.dhis.fhir.mapper;

import static org.hisp.dhis.fhir.mapping.FhirResourceType.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.regex.Pattern;
import org.hisp.dhis.fhir.mapping.FhirResourceType;
import org.junit.jupiter.api.Test;

/** Tests composing and parsing the logical ids of event-derived FHIR resources. */
class FhirLogicalIdTest {
  private static final String ENR = "nxP7UnKhomJ";
  private static final String EVT = "pTzf9KYMk72";
  private static final String DE = "DATAEL00006";
  private static final String ENCOUNTER_ID = "nxP7UnKhomJ-pTzf9KYMk72";
  private static final String PER_DATA_ELEMENT_ID = "nxP7UnKhomJ-pTzf9KYMk72-DATAEL00006";
  private static final String[] MALFORMED_SEGMENTS =
      ",1xP7UnKhomJ,nxP7UnKhom,nxP7UnKhomJx,nxP7Un_homJ,nxP7Un.homJ,nxP7Un homJ,nxP7Un\u00fchomJ"
          .split(",");
  private static final Pattern R4_ID = Pattern.compile("[A-Za-z0-9\\-\\.]{1,64}");

  @Test
  void composesEncounterAndPerDataElementIds() {
    FhirLogicalId encounter = FhirLogicalId.encounter(ENR, EVT);
    FhirLogicalId perDataElement = FhirLogicalId.perDataElement(ENR, EVT, DE);
    for (FhirLogicalId id : List.of(encounter, perDataElement)) {
      assertEquals(ENR, id.enrollment());
      assertEquals(EVT, id.event());
      assertTrue(R4_ID.matcher(id.compose()).matches(), id::compose);
    }
    assertNull(encounter.dataElement());
    assertEquals(ENCOUNTER_ID, encounter.compose());
    assertEquals(DE, perDataElement.dataElement());
    assertEquals(PER_DATA_ELEMENT_ID, perDataElement.compose());
  }

  @Test
  void constructorRequiresEnrollmentAndAnEventForADataElement() {
    assertThrows(NullPointerException.class, () -> new FhirLogicalId(null, EVT, null));
    assertThrows(IllegalArgumentException.class, () -> new FhirLogicalId(ENR, null, DE));
    assertEquals(ENR, new FhirLogicalId(ENR, null, null).compose());
  }

  @Test
  void parseRoundTripsComposedIds() {
    Optional<FhirLogicalId> encounter = FhirLogicalId.parse(ENCOUNTER, ENCOUNTER_ID);
    assertEquals(Optional.of(FhirLogicalId.encounter(ENR, EVT)), encounter);
    for (FhirResourceType type : List.of(IMMUNIZATION, OBSERVATION)) {
      Optional<FhirLogicalId> perDataElement = FhirLogicalId.parse(type, PER_DATA_ELEMENT_ID);
      assertEquals(Optional.of(FhirLogicalId.perDataElement(ENR, EVT, DE)), perDataElement);
    }
  }

  @Test
  void parseRejectsMalformedIdsAndPatientOrMissingType() {
    for (FhirResourceType type : List.of(ENCOUNTER, IMMUNIZATION, OBSERVATION)) {
      assertTrue(FhirLogicalId.parse(type, null).isEmpty(), type::name);
      for (String id : malformedIds(type)) {
        assertTrue(FhirLogicalId.parse(type, id).isEmpty(), () -> type + " " + id);
      }
    }
    for (FhirResourceType type : new FhirResourceType[] {PATIENT, null}) {
      for (String id : List.of(ENR, ENCOUNTER_ID, PER_DATA_ELEMENT_ID)) {
        assertTrue(FhirLogicalId.parse(type, id).isEmpty(), () -> type + " " + id);
      }
    }
  }

  private static List<String> malformedIds(FhirResourceType type) {
    List<String> segments = type == ENCOUNTER ? List.of(ENR, EVT) : List.of(ENR, EVT, DE);
    String valid = String.join("-", segments);
    List<String> ids = new ArrayList<>(List.of("", ENR, String.join("-", ENR, EVT, DE, ENR)));
    ids.add(type == ENCOUNTER ? PER_DATA_ELEMENT_ID : ENCOUNTER_ID);
    Collections.addAll(ids, ENR + "--" + EVT, "-" + valid, valid + "-", " " + valid, valid + " ");
    Collections.addAll(ids, valid.replace('-', '_'), valid.replace('-', '.'));
    Collections.addAll(
        ids, valid.replace("-", ""), valid.substring(0, 12) + "-" + valid.substring(13));
    ids.add(valid.substring(0, 11) + valid.charAt(12) + "-" + valid.substring(13));
    for (int position = 0; position < segments.size(); position++) {
      for (String malformed : MALFORMED_SEGMENTS) {
        List<String> corrupted = new ArrayList<>(segments);
        corrupted.set(position, malformed);
        ids.add(String.join("-", corrupted));
      }
    }
    return ids;
  }
}
