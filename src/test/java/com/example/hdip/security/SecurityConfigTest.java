package com.example.hdip.security;

import com.example.hdip.api.IngestController;
import com.example.hdip.api.IngestionService;
import com.example.hdip.synthetic.SyntheticDataGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = IngestController.class)
@Import(SecurityConfig.class)
class SecurityConfigTest {
    @Autowired MockMvc mvc;
    @MockitoBean IngestionService ingestion;
    @MockitoBean SyntheticDataGenerator generator;
    @MockitoBean JwtDecoder jwtDecoder;

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(String... roles) {
        java.util.List<org.springframework.security.core.GrantedAuthority> auths = java.util.Arrays.stream(roles)
                .<org.springframework.security.core.GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
        return jwt().authorities(auths);
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/ingest/hl7").contentType(MediaType.TEXT_PLAIN).content("MSH|x"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clinicianCannotIngest() throws Exception {
        mvc.perform(post("/api/v1/ingest/hl7").with(as("CLINICIAN")).contentType(MediaType.TEXT_PLAIN).content("MSH|x"))
                .andExpect(status().isForbidden());
    }

    @Test
    void analystAndAuditorCannotIngestFhirOrSynthetic() throws Exception {
        mvc.perform(post("/api/v1/ingest/fhir").with(as("ANALYST")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/synthetic").with(as("AUDITOR"))).andExpect(status().isForbidden());
    }

    @Test
    void ingestorCanIngestHl7() throws Exception {
        when(ingestion.submit(eq("HL7V2"), any())).thenReturn("id-1");
        mvc.perform(post("/api/v1/ingest/hl7").with(as("INGESTOR")).contentType(MediaType.TEXT_PLAIN).content("MSH|x"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageId").value("id-1"));
        verify(ingestion).submit("HL7V2", "MSH|x");
    }

    @Test
    void ingestorCanIngestFhir() throws Exception {
        when(ingestion.submit(eq("FHIR_JSON"), any())).thenReturn("id-2");
        mvc.perform(post("/api/v1/ingest/fhir").with(as("INGESTOR")).contentType(MediaType.APPLICATION_JSON).content("{\"resourceType\":\"Bundle\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void validationErrorIs400() throws Exception {
        when(ingestion.submit(any(), any())).thenThrow(new IllegalArgumentException("Empty payload"));
        mvc.perform(post("/api/v1/ingest/hl7").with(as("INGESTOR")).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPathsDenied() throws Exception {
        mvc.perform(get("/api/v1/secret").with(as("INGESTOR", "CLINICIAN", "ANALYST", "AUDITOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublic() throws Exception {
        // no actuator in this slice: the point is that the request passes the security filter (404, not 401)
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
    }
}
