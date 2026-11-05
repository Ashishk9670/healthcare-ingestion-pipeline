package com.example.hdip.consent;

import com.example.hdip.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

@WebMvcTest(controllers = ConsentController.class)
@Import({SecurityConfig.class, ConsentControllerTest.Props.class})
class ConsentControllerTest {
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(com.example.hdip.ratelimit.IngestProperties.class)
    static class Props {
    }

    static final String BODY = "{\"mrn\":\"SYN-123\",\"scope\":\"COHORT\",\"status\":\"WITHDRAWN\"}";
    @Autowired MockMvc mvc;
    @MockitoBean ConsentService consent;
    @MockitoBean JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Test
    void onlyClinicianMayRecordConsent() throws Exception {
        mvc.perform(post("/api/v1/consent").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        for (String r : new String[]{"INGESTOR", "ANALYST", "AUDITOR"}) {
            mvc.perform(post("/api/v1/consent").with(as(r)).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(consent);
    }

    @Test
    void clinicianRecordsConsentAndMrnIsNotEchoed() throws Exception {
        when(consent.record(eq("SYN-123"), eq(ConsentScope.COHORT), eq(ConsentStatus.WITHDRAWN), isNull())).thenReturn("c-1");
        mvc.perform(post("/api/v1/consent").with(as("CLINICIAN")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.consentId").value("c-1"))
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(content().string(not(containsString("SYN-123"))));
    }

    @Test
    void invalidBodiesAre400() throws Exception {
        for (String bad : new String[]{"{}", "{\"mrn\":\"\",\"scope\":\"COHORT\",\"status\":\"GRANTED\"}",
                "{\"mrn\":\"x\",\"scope\":\"BOGUS\",\"status\":\"GRANTED\"}", "{\"mrn\":\"x\",\"scope\":\"COHORT\"}"}) {
            mvc.perform(post("/api/v1/consent").with(as("CLINICIAN")).contentType(MediaType.APPLICATION_JSON).content(bad)).andExpect(status().isBadRequest());
        }
        verify(consent, never()).record(any(), any(), any(), any());
    }
}
