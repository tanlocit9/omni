package com.omni.platform.modules.scheduler.messaging;

import java.util.Map;
import java.util.UUID;

public record SyncMetadataJobMessage(
    UUID jobDefinitionId,
    UUID executionId,
    UUID parentExecutionId,
    String source,
    String metadataType,
    Map<String, Object> metadata
) implements JobMessage {}
