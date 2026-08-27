package com.omni.platform.shared.executions;

import java.util.Objects;

public record WorkIdentity(WorkType type, String key) {

    public WorkIdentity {
        Objects.requireNonNull(type, "work type is required");
        key = Objects.requireNonNull(key, "work key is required").trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("work key must not be blank");
        }
    }

    public static WorkIdentity of(WorkType type, String key) {
        return new WorkIdentity(type, key);
    }
}
