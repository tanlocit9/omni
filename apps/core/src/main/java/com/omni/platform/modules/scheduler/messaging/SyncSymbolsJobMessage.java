package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SyncSymbolsJobMessage(
                UUID jobId, UUID logId, String source,
                String exchange, Instant timestamp,
                Map<String, Object> metadata) implements JobMessage {
}