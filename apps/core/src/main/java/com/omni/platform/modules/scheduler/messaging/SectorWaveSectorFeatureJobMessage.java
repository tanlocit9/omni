package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

public record SectorWaveSectorFeatureJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        String sectorCode,
        int sectorLevel,
        String timeframe,
        Map<String, Object> metadata) implements JobMessage {
}
