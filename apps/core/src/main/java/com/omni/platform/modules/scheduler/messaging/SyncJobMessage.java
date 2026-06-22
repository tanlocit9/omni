package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SyncJobMessage(
        UUID jobId,
        UUID logId,
        String symbol,
        Instant fromOffset,
        Instant toOffset,
        Map<String, Object> configJson) {
}