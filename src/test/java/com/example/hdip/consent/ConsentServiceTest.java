package com.example.hdip.consent;

import com.example.hdip.audit.AuditService;
import com.example.hdip.crypto.AesGcmFieldCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConsentServiceTest {
    static final Instant NOW = Instant.parse("2026-06-15T00:00:00Z");
    @Mock ConsentEventRepository events;
    @Mock ConsentStateRepository states;
    @Mock AesGcmFieldCipher cipher;
    @Mock AuditService audit;

    ConsentService svc(ConsentPolicy p) {
        return new ConsentService(events, states, cipher, audit, Clock.fixed(NOW, ZoneOffset.UTC), p);
    }

    @Test
    void defaultPolicyDecisions() {
        assertThat(ConsentService.decide(null, ConsentPolicy.OPT_OUT)).isTrue();
        assertThat(ConsentService.decide(null, ConsentPolicy.REQUIRE_EXPLICIT)).isFalse();
        for (var p : ConsentPolicy.values()) {
            assertThat(ConsentService.decide(ConsentStatus.GRANTED, p)).isTrue();
            assertThat(ConsentService.decide(ConsentStatus.WITHDRAWN, p)).isFalse();
        }
    }

    @Test
    void recordAppendsEventUpdatesStateAndAuditsWithoutMrn() {
        when(cipher.hmac("SYN-1")).thenReturn("hash1");
        when(events.save(any(ConsentEventEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(states.findByMrnHashAndScope("hash1", ConsentScope.COHORT)).thenReturn(Optional.empty());
        String id = svc(ConsentPolicy.OPT_OUT).record("SYN-1", ConsentScope.COHORT, ConsentStatus.WITHDRAWN, null);
        assertThat(id).isNotBlank();
        ArgumentCaptor<ConsentStateEntity> st = ArgumentCaptor.forClass(ConsentStateEntity.class);
        verify(states).save(st.capture());
        assertThat(st.getValue().getStatus()).isEqualTo(ConsentStatus.WITHDRAWN);
        assertThat(st.getValue().getEffectiveAt()).isEqualTo(NOW);
        verify(audit).record(eq("CONSENT_RECORD"), eq("Consent"), eq("hash1"), argThat(d -> d.contains("WITHDRAWN") && !d.contains("SYN-1")));
    }

    @Test
    void olderDecisionDoesNotOverrideNewerState() {
        when(cipher.hmac("SYN-1")).thenReturn("hash1");
        when(events.save(any(ConsentEventEntity.class))).thenAnswer(i -> i.getArgument(0));
        var existing = new ConsentStateEntity("hash1", ConsentScope.EXPORT);
        existing.apply(ConsentStatus.WITHDRAWN, Instant.parse("2026-06-01T00:00:00Z"), NOW);
        when(states.findByMrnHashAndScope("hash1", ConsentScope.EXPORT)).thenReturn(Optional.of(existing));
        svc(ConsentPolicy.OPT_OUT).record("SYN-1", ConsentScope.EXPORT, ConsentStatus.GRANTED, Instant.parse("2026-05-01T00:00:00Z"));
        assertThat(existing.getStatus()).isEqualTo(ConsentStatus.WITHDRAWN); // history kept, state unchanged
        verify(events).save(any(ConsentEventEntity.class));
        verify(states, never()).save(any(ConsentStateEntity.class));
        verify(audit).record(anyString(), anyString(), anyString(), argThat(d -> d.contains("applied=false")));
    }

    @Test
    void newerDecisionOverridesState() {
        when(cipher.hmac("SYN-1")).thenReturn("hash1");
        when(events.save(any(ConsentEventEntity.class))).thenAnswer(i -> i.getArgument(0));
        var existing = new ConsentStateEntity("hash1", ConsentScope.EXPORT);
        existing.apply(ConsentStatus.WITHDRAWN, Instant.parse("2026-06-01T00:00:00Z"), NOW);
        when(states.findByMrnHashAndScope("hash1", ConsentScope.EXPORT)).thenReturn(Optional.of(existing));
        svc(ConsentPolicy.OPT_OUT).record("SYN-1", ConsentScope.EXPORT, ConsentStatus.GRANTED, Instant.parse("2026-06-10T00:00:00Z"));
        assertThat(existing.getStatus()).isEqualTo(ConsentStatus.GRANTED);
    }

    @Test
    void isPermittedUsesEffectiveStateAndPolicy() {
        when(cipher.hmac("SYN-1")).thenReturn("hash1");
        when(states.findByMrnHashAndScope("hash1", ConsentScope.COHORT)).thenReturn(Optional.empty());
        assertThat(svc(ConsentPolicy.OPT_OUT).isPermitted("SYN-1", ConsentScope.COHORT)).isTrue();
        assertThat(svc(ConsentPolicy.REQUIRE_EXPLICIT).isPermitted("SYN-1", ConsentScope.COHORT)).isFalse();
    }
}
