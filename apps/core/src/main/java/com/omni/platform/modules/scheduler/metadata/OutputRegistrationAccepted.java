package com.omni.platform.modules.scheduler.metadata;

import java.util.UUID;

/** Synchronous in-transaction signal, not a notification or external Kafka event. */
public record OutputRegistrationAccepted(UUID executionId) {}
