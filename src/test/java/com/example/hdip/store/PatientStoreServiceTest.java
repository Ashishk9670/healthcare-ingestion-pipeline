package com.example.hdip.store;

import com.example.hdip.audit.AuditService;
import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PatientStoreServiceTest {
    @Mock PatientRepository repo;
    @Mock AuditService audit;
    final AesGcmFieldCipher cipher = new AesGcmFieldCipher(
            "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=");

    EnrichedRecord rec(String dx) {
        var p = new CanonicalPatient("SYN-1", "Faux", "Casey", LocalDate.of(1980, 1, 1), "female", "900-00-0001");
        var rec = new CanonicalRecord("m", "HL7V2", p,
                List.of(new CanonicalObservation("8480-6", "LN", "SBP", null, new BigDecimal("120"), "mm[Hg]", Instant.now())),
                List.of(new CanonicalDiagnosis(dx, "I10", "d")));
        return new EnrichedRecord(rec, "35-49", Instant.now());
    }

    @Test
    void createsNewPatientAndAudits() {
        when(repo.findByMrnHash(any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        var svc = new PatientStoreService(repo, cipher, audit);
        PatientEntity e = svc.persist(rec("E11.9"));
        assertThat(e.getMrnHash()).isEqualTo(cipher.hmac("SYN-1"));
        assertThat(e.getMrn()).isEqualTo("SYN-1");
        assertThat(e.getAgeBand()).isEqualTo("35-49");
        assertThat(e.getBirthDate()).isEqualTo("1980-01-01");
        assertThat(e.getDiagnoses()).hasSize(1);
        assertThat(e.getObservations()).hasSize(1);
        verify(audit).record(eq("PIPELINE_PERSIST"), eq("Patient"), eq(e.getMrnHash()), any());
    }

    @Test
    void upsertDeduplicatesDiagnosesButAppendsObservations() {
        var existing = new PatientEntity();
        when(repo.findByMrnHash(any())).thenReturn(Optional.of(existing));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
        var svc = new PatientStoreService(repo, cipher, audit);
        svc.persist(rec("E11.9"));
        PatientEntity e = svc.persist(rec("E11.9"));
        assertThat(e).isSameAs(existing);
        assertThat(e.getDiagnoses()).hasSize(1);
        assertThat(e.getObservations()).hasSize(2);
        svc.persist(rec("I10"));
        assertThat(e.getDiagnoses()).hasSize(2);
    }
}
