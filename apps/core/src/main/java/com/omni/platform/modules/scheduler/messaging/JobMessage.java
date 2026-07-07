package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

public sealed interface JobMessage permits SymbolJobMessage, SyncSymbolsJobMessage {
    UUID jobId();

    UUID logId();

    String source();

    Map<String, Object> metadata();
}
