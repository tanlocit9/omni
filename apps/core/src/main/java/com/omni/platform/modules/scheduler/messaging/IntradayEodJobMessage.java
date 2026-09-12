package com.omni.platform.modules.scheduler.messaging;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.shared.executions.WorkType;

public record IntradayEodJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        WorkType workType,
        String workKey,
        String symbolKey,
        String exchange,
        LocalDate tradingDate,
        String provider,
        Map<String, Object> metadata) implements JobMessage {
}
