package com.example.hdip.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhiMaskerTest {

    @Test
    void masksSsn() {
        assertThat(PhiMasker.mask("patient ssn 900-12-3456 seen")).doesNotContain("900-12-3456").contains("***");
    }

    @Test
    void masksKeyValueNamesDobMrn() {
        String out = PhiMasker.mask("familyName=Testerson givenName=Alex dob=1970-01-01 mrn=SYN-123456 other=ok");
        assertThat(out).doesNotContain("Testerson", "Alex", "1970-01-01", "SYN-123456").contains("other=ok");
    }

    @Test
    void masksJsonFields() {
        String out = PhiMasker.mask("{\"family\":\"Testerson\",\"birthDate\":\"1970-01-01\",\"ssn\":\"900-12-3456\",\"gender\":\"male\"}");
        assertThat(out).doesNotContain("Testerson", "1970-01-01", "900-12-3456").contains("\"gender\":\"male\"");
    }

    @Test
    void masksHl7PidSegment() {
        String msg = "MSH|^~\\&|A|B|C|D|2026||ORU^R01|1|P|2.5\rPID|1||SYN-111111^^^S^MR||Faux^Casey||19800101|F\rOBX|1|NM|1^x||5";
        String out = PhiMasker.mask(msg);
        assertThat(out).doesNotContain("Faux", "Casey", "19800101", "SYN-111111").contains("PID|***").contains("OBX|1|NM");
    }

    @Test
    void masksStandaloneMrnToken() {
        assertThat(PhiMasker.mask("looked up SYN-654321 today")).doesNotContain("SYN-654321");
    }

    @Test
    void leavesBenignTextAndNulls() {
        assertThat(PhiMasker.mask("Normalized message abc-123 (HL7V2)")).isEqualTo("Normalized message abc-123 (HL7V2)");
        assertThat(PhiMasker.mask(null)).isNull();
        assertThat(PhiMasker.mask("")).isEmpty();
    }
}
