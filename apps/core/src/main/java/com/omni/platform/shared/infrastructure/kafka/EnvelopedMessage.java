package com.omni.platform.shared.infrastructure.kafka;

import java.util.Map;

/** An additive wire envelope flattened by the existing Kafka serialization port. */
public interface EnvelopedMessage {
    Object body();

    Map<String, Object> additionalFields();
}
