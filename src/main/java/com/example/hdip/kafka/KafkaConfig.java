package com.example.hdip.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {
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
}
