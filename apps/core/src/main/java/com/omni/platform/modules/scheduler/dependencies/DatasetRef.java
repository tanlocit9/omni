package com.omni.platform.modules.scheduler.dependencies;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import lombok.Getter;

/**
 * Logical reference to a dataset partition in the data lake.
 * <p>
 * Identifies a dataset by name and partition coordinates without coupling
 * to physical S3/R2 paths. The manifest reader resolves these references
 * to actual manifest objects.
 * <p>
 * Examples:
 * <ul>
 * <li>eod(exchange=HOSE, date=2026-08-11)</li>
 * <li>indicators(timeframe=1d, exchange=HOSE, code=VCB)</li>
 * <li>signals(strategy=TREND_MOMENTUM_V1, date=2026-08-11)</li>
 * </ul>
 */
@Getter
public final class DatasetRef {

    private final String dataset;
    private final Map<String, String> partition;

    private DatasetRef(String dataset, Map<String, String> partition) {
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException("dataset cannot be null or blank");
        }
        this.dataset = dataset;
        this.partition = partition == null ? Collections.emptyMap() : Map.copyOf(partition);
    }

    /**
     * Create a dataset reference.
     *
     * @param dataset   logical dataset name (e.g. "eod", "indicators")
     * @param partition partition coordinates (e.g. {"exchange": "HOSE", "date": "2026-08-11"})
     * @return immutable dataset reference
     */
    public static DatasetRef of(String dataset, Map<String, String> partition) {
        return new DatasetRef(dataset, partition);
    }

    /**
     * Create a dataset reference with no partition (dataset-level check).
     *
     * @param dataset logical dataset name
     * @return immutable dataset reference
     */
    public static DatasetRef of(String dataset) {
        return new DatasetRef(dataset, Collections.emptyMap());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DatasetRef other)) {
            return false;
        }
        return Objects.equals(dataset, other.dataset) && Objects.equals(partition, other.partition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataset, partition);
    }

    @Override
    public String toString() {
        if (partition.isEmpty()) {
            return dataset;
        }
        return dataset + partition;
    }
}
