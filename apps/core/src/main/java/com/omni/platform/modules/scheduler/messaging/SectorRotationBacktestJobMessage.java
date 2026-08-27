package com.omni.platform.modules.scheduler.messaging;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.shared.executions.WorkType;

public record SectorRotationBacktestJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        WorkType workType,
        String workKey,
        List<String> sectorCodes,
        int sectorLevel,
        String timeframe,
        String strategy,
        Map<String, Object> metadata) implements JobMessage {
}
