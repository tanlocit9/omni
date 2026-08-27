package com.omni.platform.modules.scheduler.messaging;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.shared.executions.WorkType;

public record SectorTransitionAnalyzeJobMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String source,
        WorkType workType,
        String workKey,
        LocalDate evaluationDate,
        List<String> sectorCodes,
        List<String> focusSectorCodes,
        int sectorLevel,
        String timeframe,
        String strategy,
        List<Integer> predictionHorizons,
        Map<String, Object> metadata) implements JobMessage {
}
