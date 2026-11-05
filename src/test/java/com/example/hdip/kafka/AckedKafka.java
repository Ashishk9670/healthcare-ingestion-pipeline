package com.example.hdip.kafka;

import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/** Test helper: a {@link KafkaPublisher} over a mocked template whose sends are acknowledged immediately. */
public final class AckedKafka {
    private AckedKafka() {
    }

    public static KafkaPublisher publisher(KafkaTemplate<String, String> template) {
        lenient().when(template.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        return new KafkaPublisher(template, Duration.ofSeconds(2));
    }
}
