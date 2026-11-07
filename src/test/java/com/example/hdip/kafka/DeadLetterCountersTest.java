package com.example.hdip.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class DeadLetterCountersTest {
    @Test
    void everySourceTopicHasADeadLetterCounterAtZeroFromStartup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaConfig.registerDeadLetterCounters(registry);
        for (String topic : new String[] {Topics.RAW, Topics.NORMALIZED, Topics.ENRICHED, Topics.EXTRACTED}) {
            assertEquals(0.0, registry.get(KafkaConfig.DLT_METRIC).tag("topic", topic).counter().count(), topic);
        }
    }
}
