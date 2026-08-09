package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

public sealed interface JobMessage permits SymbolJobMessage, SyncSymbolsJobMessage, IndicatorJobMessage, SignalJobMessage,
        SignalEvaluationJobMessage, SectorWaveSymbolFeatureJobMessage, SectorWaveSectorFeatureJobMessage,
        SectorRotationBacktestJobMessage, SectorTransitionAnalyzeJobMessage,
        SectorTransitionOutcomeEvaluationJobMessage {
    UUID jobDefinitionId();

    UUID executionId();

    UUID parentExecutionId();

    String source();

    Map<String, Object> metadata();
}
