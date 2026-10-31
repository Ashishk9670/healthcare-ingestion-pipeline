package com.example.hdip.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaPublisherTest {
    @Mock KafkaTemplate<String, String> template;

    @Test
    void returnsOnceTheBrokerAcknowledges() {
        when(template.send("t", "k", "v")).thenReturn(CompletableFuture.completedFuture(null));
        assertThatCode(() -> new KafkaPublisher(template, Duration.ofSeconds(1)).publish("t", "k", "v")).doesNotThrowAnyException();
    }

    @Test
    void failedSendIsReportedNotSwallowed() {
        when(template.send("t", "k", "SECRET-PAYLOAD")).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        assertThatThrownBy(() -> new KafkaPublisher(template, Duration.ofSeconds(1)).publish("t", "k", "SECRET-PAYLOAD"))
                .isInstanceOf(PublishFailedException.class)
                // topic only: neither key nor payload may leak into the message
                .satisfies(e -> assertThat(e.getMessage()).contains("t").doesNotContain("SECRET-PAYLOAD").doesNotContain("k "));
    }

    @Test
    void unacknowledgedSendTimesOut() {
        when(template.send("t", "k", "v")).thenReturn(new CompletableFuture<>());
        assertThatThrownBy(() -> new KafkaPublisher(template, Duration.ofMillis(50)).publish("t", "k", "v"))
                .isInstanceOf(PublishFailedException.class);
    }
}
