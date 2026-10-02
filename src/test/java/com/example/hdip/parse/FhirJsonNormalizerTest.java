package com.example.hdip.parse;

import com.example.hdip.model.CanonicalRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FhirJsonNormalizerTest {
    static final String BUNDLE = """
            {"resourceType":"Bundle","id":"b1","entry":[
              {"resource":{"resourceType":"Patient","identifier":[{"system":"urn:synthetic:mrn","value":"SYN-1"},{"system":"urn:synthetic:ssn","value":"900-11-2222"}],
                "name":[{"family":"Faux","given":["Casey"]}],"gender":"female","birthDate":"1985-06-01"}},
              {"resource":{"resourceType":"Condition","code":{"coding":[{"system":"icd10","code":"I10","display":"HTN"}]}}},
              {"resource":{"resourceType":"Observation","code":{"coding":[{"system":"http://loinc.org","code":"8480-6"}],"text":"SBP"},
                "valueQuantity":{"value":128.5,"unit":"mm[Hg]"},"effectiveDateTime":"2026-01-01T10:00:00Z"}},
              {"resource":{"resourceType":"Observation","code":{"coding":[{"code":"X"}]},"valueString":"abc"}}
            ]}""";
    final FhirJsonNormalizer n = new FhirJsonNormalizer(new ObjectMapper());

    @Test
    void parsesBundle() {
        CanonicalRecord r = n.parse(null, BUNDLE);
        assertThat(r.messageId()).isEqualTo("b1");
        assertThat(r.source()).isEqualTo("FHIR_JSON");
        assertThat(r.patient().mrn()).isEqualTo("SYN-1");
        assertThat(r.patient().ssn()).isEqualTo("900-11-2222");
        assertThat(r.patient().familyName()).isEqualTo("Faux");
        assertThat(r.patient().givenName()).isEqualTo("Casey");
        assertThat(r.patient().birthDate()).isEqualTo(LocalDate.of(1985, 6, 1));
        assertThat(r.diagnoses()).singleElement().satisfies(d -> assertThat(d.code()).isEqualTo("I10"));
        assertThat(r.observations()).hasSize(2);
        assertThat(r.observations().get(0).valueNumeric()).isEqualByComparingTo("128.5");
        assertThat(r.observations().get(0).display()).isEqualTo("SBP");
        assertThat(r.observations().get(0).effectiveAt()).isNotNull();
        assertThat(r.observations().get(1).valueText()).isEqualTo("abc");
    }

    @Test
    void parsesLonePatient() {
        var r = n.parse("m", "{\"resourceType\":\"Patient\",\"identifier\":[{\"value\":\"A1\"}]}");
        assertThat(r.patient().mrn()).isEqualTo("A1");
        assertThat(r.messageId()).isEqualTo("m");
    }

    @Test
    void rejectsInvalid() {
        assertThatThrownBy(() -> n.parse("m", "not json")).isInstanceOf(MessageParseException.class);
        assertThatThrownBy(() -> n.parse("m", "{\"a\":1}")).hasMessageContaining("resourceType");
        assertThatThrownBy(() -> n.parse("m", "{\"resourceType\":\"Bundle\",\"entry\":[]}")).hasMessageContaining("Patient");
        assertThatThrownBy(() -> n.parse("m", "{\"resourceType\":\"Patient\"}")).hasMessageContaining("identifier");
        assertThatThrownBy(() -> n.parse("m", "{\"resourceType\":\"Patient\",\"identifier\":[{\"value\":\"A\"}],\"birthDate\":\"zzz\"}"))
                .isInstanceOf(MessageParseException.class);
    }
}
