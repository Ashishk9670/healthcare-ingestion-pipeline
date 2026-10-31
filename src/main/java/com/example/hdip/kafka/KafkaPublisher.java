package com.example.hdip.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes and waits for the broker acknowledgement (wait > producer delivery.timeout.ms, so the outcome is never ambiguous). A fire-and-forget send lets a stage commit its input offset (or the API
 * answer 202) before the message is durable, so an outage silently loses accepted data. Failures surface as
 * {@link PublishFailedException} so the caller can reject (API) or retry and dead-letter (consumers).
 */
@Component
public class KafkaPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final Duration ackTimeout;

    public KafkaPublisher(KafkaTemplate<String, String> kafka, @Value("${hdip.kafka.publish-timeout:20s}") Duration ackTimeout) {
        this.kafka = kafka;
        this.ackTimeout = ackTimeout;
    }

    public void publish(String topic, String key, String value) {
        try {
            kafka.send(topic, key, value).get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PublishFailedException(topic, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new PublishFailedException(topic, e);
        }
    }
}
