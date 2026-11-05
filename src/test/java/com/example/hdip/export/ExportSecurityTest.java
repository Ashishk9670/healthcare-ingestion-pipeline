package com.example.hdip.export;

import com.example.hdip.audit.AuditService;
import com.example.hdip.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExportController.class)
@Import({SecurityConfig.class, ExportSecurityTest.Props.class})
class ExportSecurityTest {
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(com.example.hdip.ratelimit.IngestProperties.class)
    static class Props {
    }

    @Autowired MockMvc mvc;
    @MockitoBean FhirNdjsonExportService export;
    @MockitoBean AuditService audit;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String... roles) {
        List<GrantedAuthority> a = Arrays.stream(roles).<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        return jwt().authorities(a);
    }

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(get("/api/v1/export/fhir/ndjson")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAnalystMayExport() throws Exception {
        for (String r : List.of("INGESTOR", "CLINICIAN", "AUDITOR")) {
            mvc.perform(get("/api/v1/export/fhir/ndjson").with(as(r))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(export);
        verifyNoInteractions(audit);
    }

    @Test
    void analystGetsNdjsonAndExportIsAudited() throws Exception {
        when(export.export(any(OutputStream.class), anyInt())).thenAnswer(inv -> {
            ((OutputStream) inv.getArgument(0)).write("{\"resourceType\":\"Patient\"}\n".getBytes());
            return new FhirNdjsonExportService.ExportResult(1, 1);
        });
        mvc.perform(get("/api/v1/export/fhir/ndjson?limit=5").with(as("ANALYST")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/fhir+ndjson"))
                .andExpect(content().string("{\"resourceType\":\"Patient\"}\n"));
        verify(export).export(any(OutputStream.class), eq(5));
        verify(audit).record(eq("EXPORT_FHIR_NDJSON_START"), eq("Export"), isNull(), contains("limit=5"));
        verify(audit).record(eq("EXPORT_FHIR_NDJSON_DONE"), eq("Export"), isNull(), contains("patients=1"));
    }

    @Test
    void limitIsClamped() throws Exception {
        when(export.export(any(OutputStream.class), anyInt())).thenReturn(new FhirNdjsonExportService.ExportResult(0, 0));
        mvc.perform(get("/api/v1/export/fhir/ndjson?limit=99999999").with(as("ANALYST"))).andExpect(status().isOk());
        verify(export).export(any(OutputStream.class), eq(ExportController.MAX_PATIENTS));
    }
}
