package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SymbolUpsertMessage(UUID jobId, UUID logId,
        String exchange,
        int expectedCount,
        int actualCount,
        List<SymbolRecord> symbols,
        Instant detectedAt) {

    public record SymbolRecord(
            String code, String exchange, Map<String, Object> meta) {
    }
}
