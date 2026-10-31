package com.example.hdip.kafka;

import com.example.hdip.parse.MessageParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {
    public static final String DLT_SUFFIX = ".dlt";
    public static final String DLT_METRIC = "hdip.pipeline.dead_lettered";

    @Bean
    NewTopic rawTopic() {
        return TopicBuilder.name(Topics.RAW).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic normalizedTopic() {
        return TopicBuilder.name(Topics.NORMALIZED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic enrichedTopic() {
        return TopicBuilder.name(Topics.ENRICHED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic extractedTopic() {
        return TopicBuilder.name(Topics.EXTRACTED).partitions(3).replicas(1).build();
    }

    // Dead-letter topics keep the same partition count so the recoverer can reuse the source partition.
    // They carry the original payload, so they hold the same PHI as the source topic: restrict access identically.
    @Bean
    NewTopic rawDlt() {
        return TopicBuilder.name(Topics.RAW + DLT_SUFFIX).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic normalizedDlt() {
        return TopicBuilder.name(Topics.NORMALIZED + DLT_SUFFIX).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic enrichedDlt() {
        return TopicBuilder.name(Topics.ENRICHED + DLT_SUFFIX).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic extractedDlt() {
        return TopicBuilder.name(Topics.EXTRACTED + DLT_SUFFIX).partitions(3).replicas(1).build();
    }

    /**
     * Failed records are retried with exponential backoff (the input offset is not committed meanwhile, so a broker or
     * database outage never drops data) and then published to {@code <topic>.dlt} instead of being skipped. Records that can
     * never succeed (malformed JSON, unparseable messages) go straight to the DLT.
     */
    @Bean
    CommonErrorHandler pipelineErrorHandler(KafkaOperations<String, String> template, MeterRegistry registry,
                                            @Value("${hdip.kafka.retry.initial-interval:500ms}") java.time.Duration initial,
                                            @Value("${hdip.kafka.retry.max-interval:10s}") java.time.Duration max,
                                            @Value("${hdip.kafka.retry.max-elapsed:60s}") java.time.Duration maxElapsed) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(rec.topic() + DLT_SUFFIX, rec.partition()));
        // Exception messages (notably Jackson's) can quote payload text, so only the exception class goes into the headers.
        recoverer.setExceptionHeadersCreator((headers, ex, isKey, names) ->
                headers.add(names.getExceptionInfo().getExceptionFqcn(), rootClass(ex).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var delegate = (org.springframework.kafka.listener.ConsumerRecordRecoverer) (rec, ex) -> {
            registry.counter(DLT_METRIC, "topic", rec.topic()).increment();
            recoverer.accept(rec, ex);
        };
        ExponentialBackOff backOff = new ExponentialBackOff(initial.toMillis(), 2.0);
        backOff.setMaxInterval(max.toMillis());
        backOff.setMaxElapsedTime(maxElapsed.toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler(delegate, backOff);
        handler.addNotRetryableExceptions(JsonProcessingException.class, MessageParseException.class, IllegalArgumentException.class);
        return handler;
    }

    /** Class of the underlying failure: the listener wrapper says nothing useful, and its message must not be stored. */
    private static String rootClass(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getName();
    }
}
