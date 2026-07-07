package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SymbolJobMessage(
                UUID jobId, UUID logId, String source,
                String symbolKey, Instant fromOffset, Instant toOffset,
                Map<String, Object> metadata) implements JobMessage {
}