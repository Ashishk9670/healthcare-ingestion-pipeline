package com.example.hdip.parse;

import com.example.hdip.model.CanonicalRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hl7V2ParserTest {
    static final String MSG = String.join("\r",
            "MSH|^~\\&|SYNTH|FAC|HDIP|FAC|20260101120000||ORU^R01|MSG-1|P|2.5",
            "PID|1||SYN-123456^^^SYNTH^MR||Testerson^Alex||19700315|M|||||||||||900-12-3456",
            "DG1|1||E11.9^Type 2 diabetes^I10",
            "DG1|2||I10",
            "OBX|1|NM|4548-4^Hemoglobin A1c^LN||7.2|%|||||F|||20260101103000",
            "OBX|2|ST|NOTE^Note^L||free text value");

    final Hl7V2Parser parser = new Hl7V2Parser();

    @Test
    void parsesPatient() {
        CanonicalRecord r = parser.parse("id-1", MSG);
        assertThat(r.messageId()).isEqualTo("id-1");
        assertThat(r.source()).isEqualTo("HL7V2");
        assertThat(r.patient().mrn()).isEqualTo("SYN-123456");
        assertThat(r.patient().familyName()).isEqualTo("Testerson");
        assertThat(r.patient().givenName()).isEqualTo("Alex");
        assertThat(r.patient().birthDate()).isEqualTo(LocalDate.of(1970, 3, 15));
        assertThat(r.patient().gender()).isEqualTo("male");
        assertThat(r.patient().ssn()).isEqualTo("900-12-3456");
    }

    @Test
    void parsesDiagnosesAndObservations() {
        CanonicalRecord r = parser.parse(null, MSG);
        assertThat(r.messageId()).isEqualTo("MSG-1"); // falls back to MSH-10
        assertThat(r.diagnoses()).hasSize(2);
        assertThat(r.diagnoses().get(0).code()).isEqualTo("E11.9");
        assertThat(r.diagnoses().get(0).display()).isEqualTo("Type 2 diabetes");
        assertThat(r.diagnoses().get(1).display()).isNull();
        assertThat(r.observations()).hasSize(2);
        var o = r.observations().get(0);
        assertThat(o.code()).isEqualTo("4548-4");
        assertThat(o.system()).isEqualTo("LN");
        assertThat(o.valueNumeric()).isEqualByComparingTo(new BigDecimal("7.2"));
        assertThat(o.unit()).isEqualTo("%");
        assertThat(o.effectiveAt()).isNotNull();
        assertThat(r.observations().get(1).valueText()).isEqualTo("free text value");
        assertThat(r.observations().get(1).valueNumeric()).isNull();
    }

    @Test
    void acceptsNewlineSeparatedSegments() {
        assertThat(parser.parse("x", MSG.replace("\r", "\n")).diagnoses()).hasSize(2);
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> parser.parse("x", "")).isInstanceOf(MessageParseException.class);
        assertThatThrownBy(() -> parser.parse("x", "PID|1")).isInstanceOf(MessageParseException.class);
        assertThatThrownBy(() -> parser.parse("x", "MSH|^~\\&|A|B|C|D|2026||ADT^A01|1|P|2.5"))
                .hasMessageContaining("PID");
        assertThatThrownBy(() -> parser.parse("x", "MSH|^~\\&|A\rPID|1||||Doe^J")).hasMessageContaining("PID-3");
        assertThatThrownBy(() -> parser.parse("x", "MSH|^~\\&|A\rPID|1||M1||Doe^J||notadate|M"))
                .isInstanceOf(MessageParseException.class);
    }

    @Test
    void errorMessagesDoNotLeakPayload() {
        assertThatThrownBy(() -> parser.parse("x", "MSH|^~\\&|A\rPID|1||M1||Doe^J||99999999|M"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Doe"));
    }
}
