package com.example.hdip.store;

import com.example.hdip.audit.AuditService;
import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Upserts enriched records into PostgreSQL (sensitive columns encrypted by JPA converters). */
@Service
public class PatientStoreService {
    private final PatientRepository repo;
    private final AesGcmFieldCipher cipher;
    private final AuditService audit;

    public PatientStoreService(PatientRepository repo, AesGcmFieldCipher cipher, AuditService audit) {
        this.repo = repo;
        this.cipher = cipher;
        this.audit = audit;
    }

    @Transactional
    public PatientEntity persist(EnrichedRecord enriched) {
        CanonicalRecord rec = enriched.record();
        CanonicalPatient cp = rec.patient();
        String hash = cipher.hmac(cp.mrn());
        PatientEntity p = repo.findByMrnHash(hash).orElseGet(PatientEntity::new);
        p.setMrnHash(hash);
        p.setMrn(cp.mrn());
        p.setFamilyName(cp.familyName());
        p.setGivenName(cp.givenName());
        p.setBirthDate(cp.birthDate() == null ? null : cp.birthDate().toString());
        p.setSsn(cp.ssn());
        p.setGender(cp.gender());
        p.setAgeBand(enriched.ageBand());
        p.setUpdatedAt(Instant.now());

        for (CanonicalDiagnosis d : rec.diagnoses()) {
            boolean exists = p.getDiagnoses().stream().anyMatch(e -> java.util.Objects.equals(e.getCode(), d.code()));
            if (!exists) {
                DiagnosisEntity de = new DiagnosisEntity();
                de.setPatient(p);
                de.setCode(d.code());
                de.setSystem(d.system());
                de.setDisplay(d.display());
                p.getDiagnoses().add(de);
            }
        }
        for (CanonicalObservation o : rec.observations()) {
            ObservationEntity oe = new ObservationEntity();
            oe.setPatient(p);
            oe.setCode(o.code());
            oe.setSystem(o.system());
            oe.setDisplay(o.display());
            oe.setValueText(o.valueText());
            oe.setValueNumeric(o.valueNumeric());
            oe.setUnit(o.unit());
            oe.setEffectiveAt(o.effectiveAt());
            p.getObservations().add(oe);
        }
        PatientEntity saved = repo.save(p);
        audit.record("PIPELINE_PERSIST", "Patient", hash, "messageId=" + rec.messageId() + " source=" + rec.source());
        return saved;
    }
}
