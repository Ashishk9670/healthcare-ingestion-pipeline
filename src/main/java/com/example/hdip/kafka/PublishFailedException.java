package com.example.hdip.kafka;

/** A Kafka publish was not acknowledged by the broker. The message must be treated as not accepted. */
public class PublishFailedException extends RuntimeException {
    public PublishFailedException(String topic, Throwable cause) {
        // topic only: never include key or payload text (PHI)
        super("Publish to " + topic + " was not acknowledged", cause);
    }
}
