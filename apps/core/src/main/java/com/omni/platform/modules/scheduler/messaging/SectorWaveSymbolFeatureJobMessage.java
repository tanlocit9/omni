package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

public record SectorWaveSymbolFeatureJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        String symbolKey,
        String timeframe,
        Map<String, Object> metadata) implements JobMessage {
}
