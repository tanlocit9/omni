package com.omni.platform.modules.scheduler.topology;

import java.util.Locale;
import java.util.Objects;

import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

/** Stable logical identity for one scheduler topology node. */
public record TopologyNodeKey(String value) implements Comparable<TopologyNodeKey> {

    public TopologyNodeKey {
        Objects.requireNonNull(value, "value");
        value = value.strip();
        if (!value.matches("[a-z][a-z0-9-]*")) {
            throw new IllegalArgumentException("Invalid topology node key: " + value);
        }
    }

    public static TopologyNodeKey forJobType(JobType jobType) {
        Objects.requireNonNull(jobType, "jobType");
        return new TopologyNodeKey(jobType.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    @Override
    public int compareTo(TopologyNodeKey other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
