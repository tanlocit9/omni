package com.omni.platform.modules.scheduler.messaging;

public record KafkaMessage(
        String key,
        Object payload) {
}