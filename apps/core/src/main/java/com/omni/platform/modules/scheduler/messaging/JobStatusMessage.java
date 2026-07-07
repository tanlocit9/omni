package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;

public record JobStatusMessage(
                String symbolKey,
                String jobId,
                String logId,
                String status,
                int recordsInserted,
                int totalRecords,
                String newOffset,
                Instant startedAt,
                Instant finishedAt,
                long durationMs,
                String errorMessage) {
}