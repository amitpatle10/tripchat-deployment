package com.tripchat.config;

import com.tripchat.dto.messaging.ChatMessageEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * KafkaConfig — configures the Kafka consumer factory with manual offset commit.
 *
 * Why manual acknowledgment (AckMode.MANUAL_IMMEDIATE):
 *   Default auto-commit offsets periodically, regardless of whether the message
 *   was successfully processed. If the app crashes between auto-commit and DB write,
 *   the message is lost (at-most-once — unacceptable for chat).
 *
 *   With MANUAL_IMMEDIATE: we call ack.acknowledge() only after DB write succeeds.
 *   If the app crashes before ack — Kafka re-delivers the message (at-least-once).
 *   Duplicate delivery is handled by clientId idempotency check in the consumer.
 *
 * enable.auto.commit = false:
 *   Disables Kafka's automatic offset commits entirely.
 *   Spring Kafka manages commits via the Acknowledgment object we pass to the listener.
 *
 * Concurrency = 1 (implicit default):
 *   One consumer thread per listener container.
 *   With 3 partitions and 1 server, Spring Kafka assigns all 3 partitions to
 *   this single consumer thread — processes them sequentially.
 *   Scale to 3 servers → 1 partition per server, true parallelism.
 */
@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    // Inject Spring Boot's fully-resolved Kafka config object so that ALL
    // spring.kafka.* properties (including spring.kafka.properties.* SASL/SSL
    // overrides from application-prod.yml) flow into our custom factory.
    // Without this, a manually-constructed props map would miss profile-specific
    // settings like security.protocol=SASL_SSL for MSK Serverless.
    private final KafkaProperties kafkaProperties;

    @Bean
    public ConsumerFactory<String, ChatMessageEvent> consumerFactory() {
        JsonDeserializer<ChatMessageEvent> deserializer = new JsonDeserializer<>(ChatMessageEvent.class);
        deserializer.addTrustedPackages("com.tripchat.*");
        deserializer.setUseTypeMapperForKey(false);

        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                kafkaProperties.getBootstrapServers().stream().reduce((a, b) -> a + "," + b).orElse(""));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, kafkaProperties.getConsumer().getGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Disable auto-commit — we manage offsets manually via Acknowledgment
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Merge spring.kafka.properties.* entries (SASL/SSL for MSK in prod).
        // getProperties() returns only the additional properties map, not consumer
        // deserializer settings — so there is no conflict with our explicit JsonDeserializer.
        props.putAll(kafkaProperties.getProperties());

        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
    }

    // Declares the topic so Spring Kafka's auto-configured KafkaAdmin creates it on startup.
    // KafkaAdmin inherits all spring.kafka.properties.* (SASL/SSL) from Spring Boot autoconfiguration,
    // so it authenticates to MSK Serverless the same way the consumer does.
    @Bean
    public NewTopic chatMessagesTopic() {
        return TopicBuilder.name("chat.messages")
                .partitions(3)
                .replicas(3)
                .build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, ChatMessageEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory());

        // MANUAL_IMMEDIATE: ack.acknowledge() commits the offset immediately
        // Alternative: MANUAL (batches commits) — MANUAL_IMMEDIATE is safer for chat
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        return factory;
    }
}
