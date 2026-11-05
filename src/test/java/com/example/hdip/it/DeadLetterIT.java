package com.example.hdip.it;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A record that can never be processed (unparseable JSON) must not be skipped silently: it is published to
 * {@code phi.raw.dlt}. The dead-letter headers carry only the exception class, never message text, because Jackson's
 * messages can quote the payload. SYNTHETIC DATA ONLY.
 */
class DeadLetterIT extends AbstractIntegrationTest {

    @Test
    void poisonRecordIsDeadLetteredWithoutLeakingItsContent() {
        String marker = "SYN-DLT-" + UUID.randomUUID();
        String poison = "{\"not\":\"a raw message\"," + marker; // truncated JSON containing a synthetic identifier

        try (var producer = new KafkaProducer<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            producer.send(new ProducerRecord<>("phi.raw", marker, poison));
            producer.flush();
        }

        List<ConsumerRecord<String, String>> dead = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("phi.raw.dlt"));
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (marker.equals(r.key())) dead.add(r);
                });
                assertThat(dead).as("poison record on phi.raw.dlt").isNotEmpty();
            });
        }

        ConsumerRecord<String, String> rec = dead.get(0);
        assertThat(rec.value()).as("original payload preserved for reprocessing").isEqualTo(poison);
        StringBuilder headers = new StringBuilder();
        for (Header h : rec.headers()) headers.append(h.key()).append('=').append(new String(h.value(), StandardCharsets.UTF_8)).append('\n');
        assertThat(headers.toString()).as("exception class recorded").contains("Json");
        assertThat(headers.toString()).as("no payload text in dead-letter headers").doesNotContain(marker);
    }
}
