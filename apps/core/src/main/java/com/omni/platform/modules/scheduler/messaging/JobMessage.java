package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

public sealed interface JobMessage permits SymbolJobMessage, SyncSymbolsJobMessage {
    UUID jobDefinitionId();

    UUID executionId();

    UUID parentExecutionId();

    String source();

    Map<String, Object> metadata();

    default UUID jobId() {
        return jobDefinitionId();
    }

    default UUID logId() {
        return executionId();
    }
}