package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

public sealed interface JobMessage permits SymbolJobMessage, SyncSymbolsJobMessage, IndicatorJobMessage, SignalJobMessage {
    UUID jobDefinitionId();

    UUID executionId();

    UUID parentExecutionId();

    String source();

    Map<String, Object> metadata();
}
