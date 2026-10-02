package com.example.hdip.security;

import com.example.hdip.audit.AuditService;
import com.example.hdip.crypto.AesGcmFieldCipher;
import com.example.hdip.graphql.PatientGraphQlController;
import com.example.hdip.store.PatientQueryService;
import com.example.hdip.store.PatientViews.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** GraphQL schema binding + method-level RBAC, without any infrastructure. */
@GraphQlTest(PatientGraphQlController.class)
@Import(GraphQlRbacTest.MethodSecurity.class)
class GraphQlRbacTest {
    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired GraphQlTester tester;
    @MockitoBean PatientQueryService queries;
    @MockitoBean AuditService audit;
    @MockitoBean AesGcmFieldCipher cipher;

    static final String PATIENT = "{ patient(mrn:\"SYN-1\"){ mrn familyName ageBand diagnoses{code} } }";
    static final String COHORT = "{ cohort(filter:{diagnosisCode:\"E11\", ageBand:\"50-64\"}, limit: 5){ totalPatients byAgeBand{key count} members{patientRef diagnosisCodes} } }";
    static final String AUDIT = "{ auditLog(limit: 5){ action actor } }";

    private void stub() {
        when(queries.findByMrn("SYN-1")).thenReturn(Optional.of(new PatientView("SYN-1", "Faux", "Casey", "1970-01-01", "female",
                "50-64", List.of(new DiagnosisView("E11.9", null, null)), List.of())));
        when(queries.cohort(any(), anyInt())).thenReturn(new CohortSummary(1, List.of(new Bucket("50-64", 1)), List.of(),
                List.of(), List.of(new CohortMember("ref", "50-64", "female", List.of("E11.9")))));
        when(audit.latest(anyInt())).thenReturn(List.of());
    }

    @Test
    @WithMockUser(roles = "CLINICIAN")
    void clinicianCanQueryPatientAndCohort() {
        stub();
        tester.document(PATIENT).execute().path("patient.familyName").entity(String.class).isEqualTo("Faux");
        tester.document(COHORT).execute().path("cohort.totalPatients").entity(Integer.class).isEqualTo(1);
        verify(audit).record(eq("PATIENT_VIEW"), any(), any(), any());
        verify(audit).record(eq("COHORT_QUERY"), any(), any(), any());
        verify(queries).cohort(eq(new CohortFilter("E11", "50-64", null)), eq(5));
    }

    @Test
    @WithMockUser(roles = "ANALYST")
    void analystCanOnlyQueryCohort() {
        stub();
        tester.document(COHORT).execute().path("cohort.byAgeBand[0].key").entity(String.class).isEqualTo("50-64");
        tester.document(PATIENT).execute().errors().satisfy(e -> org.assertj.core.api.Assertions.assertThat(e).isNotEmpty());
        tester.document(AUDIT).execute().errors().satisfy(e -> org.assertj.core.api.Assertions.assertThat(e).isNotEmpty());
        verify(queries, never()).findByMrn(any());
    }

    @Test
    @WithMockUser(roles = "AUDITOR")
    void auditorCanOnlyReadAuditLog() {
        stub();
        tester.document(AUDIT).execute().path("auditLog").entityList(Object.class).hasSize(0);
        tester.document(COHORT).execute().errors().satisfy(e -> org.assertj.core.api.Assertions.assertThat(e).isNotEmpty());
        tester.document(PATIENT).execute().errors().satisfy(e -> org.assertj.core.api.Assertions.assertThat(e).isNotEmpty());
    }

    @Test
    @WithMockUser(roles = "INGESTOR")
    void ingestorCannotReadAnything() {
        stub();
        for (String q : List.of(PATIENT, COHORT, AUDIT)) {
            tester.document(q).execute().errors().satisfy(e -> org.assertj.core.api.Assertions.assertThat(e).isNotEmpty());
        }
        verifyNoInteractions(queries);
    }
}
