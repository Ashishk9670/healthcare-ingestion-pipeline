package com.example.hdip.consent;

import com.example.hdip.audit.AuditService;
import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.store.PatientEntity;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Records per-patient consent and applies it as a gate: {@link #permitted(ConsentScope)} is a JPA specification that
 * every cohort and export query must AND into its criteria. The latest decision by {@code effectiveAt} wins; a late
 * arriving older decision is kept in history but does not change the effective state.
 */
@Service
public class ConsentService {
    private final ConsentEventRepository events;
    private final ConsentStateRepository states;
    private final AesGcmFieldCipher cipher;
    private final AuditService audit;
    private final Clock clock;
    private final ConsentPolicy policy;

    public ConsentService(ConsentEventRepository events, ConsentStateRepository states, AesGcmFieldCipher cipher, AuditService audit,
                          Clock clock, @Value("${app.consent.default-policy:OPT_OUT}") ConsentPolicy policy) {
        this.events = events;
        this.states = states;
        this.cipher = cipher;
        this.audit = audit;
        this.clock = clock;
        this.policy = policy;
    }

    public ConsentPolicy policy() {
        return policy;
    }

    /** Appends an event, updates the effective state and audits (patient referenced by MRN hash only). */
    @Transactional
    public String record(String mrn, ConsentScope scope, ConsentStatus status, Instant effectiveAt) {
        Instant now = Instant.now(clock);
        Instant eff = effectiveAt == null ? now : effectiveAt;
        String hash = cipher.hmac(mrn);
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        String by = a == null ? "system" : a.getName();
        var ev = events.save(new ConsentEventEntity(hash, scope, status, eff, by, now));
        ConsentStateEntity st = states.findByMrnHashAndScope(hash, scope).orElseGet(() -> new ConsentStateEntity(hash, scope));
        boolean newer = st.getEffectiveAt() == null || !eff.isBefore(st.getEffectiveAt());
        if (newer) {
            st.apply(status, eff, now);
            states.save(st);
        }
        audit.record("CONSENT_RECORD", "Consent", hash, "scope=" + scope + " status=" + status + " applied=" + newer);
        return ev.getId().toString();
    }

    /** Effective status, or empty when no decision exists. */
    @Transactional(readOnly = true)
    public Optional<ConsentStatus> effective(String mrn, ConsentScope scope) {
        return states.findByMrnHashAndScope(cipher.hmac(mrn), scope).map(ConsentStateEntity::getStatus);
    }

    @Transactional(readOnly = true)
    public boolean isPermitted(String mrn, ConsentScope scope) {
        return decide(effective(mrn, scope).orElse(null), policy);
    }

    static boolean decide(ConsentStatus effective, ConsentPolicy policy) {
        if (effective == null) return policy == ConsentPolicy.OPT_OUT;
        return effective == ConsentStatus.GRANTED;
    }

    /** Predicate gating patients in cohort/export queries for the given scope. */
    public Specification<PatientEntity> permitted(ConsentScope scope) {
        return (root, query, cb) -> {
            Subquery<Integer> sub = query.subquery(Integer.class);
            Root<ConsentStateEntity> s = sub.from(ConsentStateEntity.class);
            var sameSubject = cb.and(cb.equal(s.get("mrnHash"), root.get("mrnHash")), cb.equal(s.get("scope"), scope));
            if (policy == ConsentPolicy.REQUIRE_EXPLICIT) {
                sub.select(cb.literal(1)).where(sameSubject, cb.equal(s.get("status"), ConsentStatus.GRANTED));
                return cb.exists(sub);
            }
            sub.select(cb.literal(1)).where(sameSubject, cb.equal(s.get("status"), ConsentStatus.WITHDRAWN));
            return cb.not(cb.exists(sub));
        };
    }
}
