package com.example.hdip.store;

import com.example.hdip.consent.ConsentScope;
import com.example.hdip.consent.ConsentService;
import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.store.PatientViews.*;
import com.example.hdip.extract.ClinicalExtractionEntity;
import com.example.hdip.extract.ExtractedFact;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class PatientQueryService {
    private final PatientRepository repo;
    private final AesGcmFieldCipher cipher;
    private final ConsentService consent;

    public PatientQueryService(PatientRepository repo, AesGcmFieldCipher cipher, ConsentService consent) {
        this.consent = consent;
        this.repo = repo;
        this.cipher = cipher;
    }

    @Transactional(readOnly = true)
    public Optional<PatientView> findByMrn(String mrn) {
        return repo.findByMrnHash(cipher.hmac(mrn)).map(this::toView);
    }

    @Transactional(readOnly = true)
    public CohortSummary cohort(CohortFilter filter, int memberLimit) {
        List<PatientEntity> patients = repo.findAll(spec(filter).and(consent.permitted(ConsentScope.COHORT)));
        Map<String, Integer> byAge = new TreeMap<>();
        Map<String, Integer> byGender = new TreeMap<>();
        Map<String, Integer> byDx = new TreeMap<>();
        List<CohortMember> members = new ArrayList<>();
        for (PatientEntity p : patients) {
            byAge.merge(nz(p.getAgeBand()), 1, Integer::sum);
            byGender.merge(nz(p.getGender()), 1, Integer::sum);
            List<String> codes = p.getDiagnoses().stream().map(DiagnosisEntity::getCode).filter(Objects::nonNull).distinct().sorted().toList();
            codes.forEach(c -> byDx.merge(c, 1, Integer::sum));
            if (members.size() < memberLimit) {
                members.add(new CohortMember(p.getId().toString(), nz(p.getAgeBand()), nz(p.getGender()), codes));
            }
        }
        return new CohortSummary(patients.size(), buckets(byAge), buckets(byGender), buckets(byDx), members);
    }

    static Specification<PatientEntity> spec(CohortFilter f) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (f != null && notBlank(f.diagnosisCode())) {
                query.distinct(true);
                var d = root.join("diagnoses", JoinType.INNER);
                ps.add(cb.like(cb.upper(d.get("code")), f.diagnosisCode().trim().toUpperCase() + "%"));
            }
            if (f != null && notBlank(f.ageBand())) ps.add(cb.equal(root.get("ageBand"), f.ageBand().trim()));
            if (f != null && notBlank(f.gender())) ps.add(cb.equal(cb.lower(root.get("gender")), f.gender().trim().toLowerCase()));
            if (f != null) {
                addExtraction(ps, root, query, cb, ExtractedFact.Kind.DIAGNOSIS, f.extractedDiagnosis());
                addExtraction(ps, root, query, cb, ExtractedFact.Kind.MEDICATION, f.medication());
                addExtraction(ps, root, query, cb, ExtractedFact.Kind.STAGE, f.stage());
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** EXISTS (select 1 from clinical_extraction e where e.mrn_hash = p.mrn_hash and kind/value match). */
    private static void addExtraction(List<Predicate> ps, Root<PatientEntity> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                      ExtractedFact.Kind kind, String filterValue) {
        if (!notBlank(filterValue)) return;
        Subquery<Integer> sub = query.subquery(Integer.class);
        Root<ClinicalExtractionEntity> e = sub.from(ClinicalExtractionEntity.class);
        String v = filterValue.trim();
        Predicate match = switch (kind) {
            case DIAGNOSIS -> cb.like(cb.upper(e.get("code")), v.toUpperCase() + "%");
            case MEDICATION -> cb.equal(cb.lower(e.get("value")), v.toLowerCase());
            case STAGE -> cb.equal(cb.upper(e.get("value")), v.toUpperCase());
        };
        sub.select(cb.literal(1)).where(cb.equal(e.get("mrnHash"), root.get("mrnHash")), cb.equal(e.get("kind"), kind), match);
        ps.add(cb.exists(sub));
    }

    private PatientView toView(PatientEntity p) {
        return new PatientView(p.getMrn(), p.getFamilyName(), p.getGivenName(), p.getBirthDate(), p.getGender(), p.getAgeBand(),
                p.getDiagnoses().stream().map(d -> new DiagnosisView(d.getCode(), d.getSystem(), d.getDisplay())).toList(),
                p.getObservations().stream().map(o -> new ObservationView(o.getCode(), o.getDisplay(), o.getValueText(),
                        o.getValueNumeric() == null ? null : o.getValueNumeric().doubleValue(), o.getUnit(),
                        o.getEffectiveAt() == null ? null : o.getEffectiveAt().toString())).toList());
    }

    private static List<Bucket> buckets(Map<String, Integer> m) {
        return m.entrySet().stream().map(e -> new Bucket(e.getKey(), e.getValue())).toList();
    }

    private static String nz(String s) { return s == null ? "unknown" : s; }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
