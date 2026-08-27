package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

import com.omni.platform.shared.executions.WorkType;

public sealed interface JobMessage permits SymbolJobMessage, SyncSymbolsJobMessage, IndicatorJobMessage, SignalJobMessage,
        SignalEvaluationJobMessage, SectorWaveSymbolFeatureJobMessage, SectorWaveSectorFeatureJobMessage,
        SectorRotationBacktestJobMessage, SectorTransitionAnalyzeJobMessage,
        SectorTransitionOutcomeEvaluationJobMessage,
        SyncMetadataJobMessage {
    UUID jobDefinitionId();

    UUID executionId();

    UUID parentExecutionId();

    String source();

    WorkType workType();

    String workKey();

    Map<String, Object> metadata();
}
