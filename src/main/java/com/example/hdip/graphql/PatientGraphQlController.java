package com.example.hdip.graphql;

import com.example.hdip.audit.AuditEntry;
import com.example.hdip.audit.AuditService;
import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.store.PatientQueryService;
import com.example.hdip.store.PatientViews.CohortFilter;
import com.example.hdip.store.PatientViews.CohortSummary;
import com.example.hdip.store.PatientViews.PatientView;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * GraphQL API. RBAC: patient detail = CLINICIAN; cohort aggregates (de-identified) = CLINICIAN|ANALYST;
 * audit log = AUDITOR. Every call is audited.
 */
@Controller
public class PatientGraphQlController {
    private final PatientQueryService queries;
    private final AuditService audit;
    private final AesGcmFieldCipher cipher;

    public PatientGraphQlController(PatientQueryService queries, AuditService audit, AesGcmFieldCipher cipher) {
        this.queries = queries;
        this.audit = audit;
        this.cipher = cipher;
    }

    @QueryMapping
    @PreAuthorize("hasRole('CLINICIAN')")
    public PatientView patient(@Argument String mrn) {
        var view = queries.findByMrn(mrn);
        audit.record("PATIENT_VIEW", "Patient", cipher.hmac(mrn), view.isPresent() ? "found" : "not-found");
        return view.orElse(null);
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('CLINICIAN','ANALYST')")
    public CohortSummary cohort(@Argument CohortFilter filter, @Argument Integer limit) {
        CohortSummary s = queries.cohort(filter, limit == null ? 50 : Math.max(0, Math.min(limit, 500)));
        audit.record("COHORT_QUERY", "Cohort", null, "dx=" + (filter == null ? null : filter.diagnosisCode())
                + " ageBand=" + (filter == null ? null : filter.ageBand())
                + " gender=" + (filter == null ? null : filter.gender()) + " total=" + s.totalPatients());
        return s;
    }

    @QueryMapping
    @PreAuthorize("hasRole('AUDITOR')")
    public List<AuditEntryView> auditLog(@Argument Integer limit) {
        List<AuditEntry> entries = audit.latest(limit == null ? 50 : limit);
        audit.record("AUDIT_LOG_VIEW", "AuditLog", null, "limit=" + limit);
        return entries.stream().map(e -> new AuditEntryView(e.getId().toString(), e.getTimestamp().toString(), e.getActor(),
                e.getRoles(), e.getAction(), e.getResourceType(), e.getResourceId(), e.getDetail())).toList();
    }

    public record AuditEntryView(String id, String timestamp, String actor, String roles, String action,
                                 String resourceType, String resourceId, String detail) {
    }
}
