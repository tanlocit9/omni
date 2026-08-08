package com.omni.platform.modules.scheduler.messaging;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SectorRotationBacktestJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        List<String> sectorCodes,
        int sectorLevel,
        String timeframe,
        String strategy,
        Map<String, Object> metadata) implements JobMessage {
}
