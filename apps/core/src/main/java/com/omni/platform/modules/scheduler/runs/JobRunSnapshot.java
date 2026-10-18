package com.omni.platform.modules.scheduler.runs;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Durable request and frozen membership; execution progress remains in execution histories. */
public record JobRunSnapshot(
        UUID id,
        Request request,
        State state,
        String failureReason,
        Instant requestedAt,
        FrozenJobRunPath path,
        List<UUID> definitionIds) {
    public JobRunSnapshot {
        definitionIds = List.copyOf(definitionIds);
        if (definitionIds.size() != path.requiredPath().steps().size()) {
            throw new IllegalArgumentException(
                    "Every scoped step must have a persisted definition UUID");
        }
    }

    public enum Mode {
        SCHEDULED,
        MANUAL,
        BACKFILL
    }

    public enum State {
        PREPARED,
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    public record Request(
            Mode mode,
            String actor,
            String idempotencyKey,
            String reason,
            String businessGroup,
            String parametersJson) {
        public Request {
            java.util.Objects.requireNonNull(mode, "mode");
            actor = bounded(actor, 200);
            idempotencyKey = bounded(idempotencyKey, 128);
            reason = bounded(reason, 500);
            if (!idempotencyKey.matches("[A-Za-z0-9._:-]{1,128}")) {
                throw new IllegalArgumentException("Unsupported idempotency key");
            }
            if (businessGroup != null) businessGroup = bounded(businessGroup, 200);
            parametersJson = bounded(parametersJson, 65536);
        }

        private static String bounded(String value, int limit) {
            if (value == null
                    || value.isBlank()
                    || !value.equals(value.strip())
                    || value.length() > limit) {
                throw new IllegalArgumentException(
                        "Request field must be nonblank, trimmed and bounded");
            }
            return value;
        }
    }
}
