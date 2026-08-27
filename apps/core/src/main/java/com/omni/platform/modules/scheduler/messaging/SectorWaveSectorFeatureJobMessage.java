package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

import com.omni.platform.shared.executions.WorkType;

public record SectorWaveSectorFeatureJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        WorkType workType,
        String workKey,
        String sectorCode,
        int sectorLevel,
        String timeframe,
        Map<String, Object> metadata) implements JobMessage {
}
