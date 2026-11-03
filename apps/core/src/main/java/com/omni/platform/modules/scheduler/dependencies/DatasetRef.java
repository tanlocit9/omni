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
    private final String atomicWorkId;
    private final java.time.LocalDate businessDate;
    private final java.util.UUID atomicAttemptId;

    private DatasetRef(String dataset, Map<String, String> partition) {
        this(dataset, partition, null, null, null);
    }

    private DatasetRef(String dataset, Map<String, String> partition, String work,
            java.time.LocalDate day, java.util.UUID attempt) {
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException("dataset cannot be null or blank");
        }
        this.dataset = dataset;
        this.partition = partition == null ? Collections.emptyMap() : Map.copyOf(partition);
        this.atomicWorkId = work;
        this.businessDate = day;
        this.atomicAttemptId = attempt;
    }

    public DatasetRef bindWork(String work, java.time.LocalDate day, java.util.UUID attempt) {
        if (work == null || !work.matches("work:[0-9a-f]{64}") || day == null || attempt == null)
            throw new IllegalArgumentException("Invalid approved work identity");
        return new DatasetRef(dataset, partition, work, day, attempt);
    }

    public Map<String, Object> approvedInput(String version) {
        var binding = new java.util.LinkedHashMap<String, Object>();
        binding.put("dataset", dataset); binding.put("partition", partition); binding.put("dataVersion", version);
        if (atomicWorkId != null) {
            binding.put("atomicWorkId", atomicWorkId);
            binding.put("businessDate", businessDate.toString());
            binding.put("atomicAttemptId", atomicAttemptId.toString());
        }
        return Map.copyOf(binding);
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
        return Objects.equals(dataset, other.dataset) && Objects.equals(partition, other.partition)
                && Objects.equals(atomicWorkId, other.atomicWorkId) && Objects.equals(businessDate, other.businessDate)
                && Objects.equals(atomicAttemptId, other.atomicAttemptId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dataset, partition, atomicWorkId, businessDate, atomicAttemptId);
    }

    @Override
    public String toString() {
        if (partition.isEmpty()) {
            return dataset;
        }
        return dataset + partition;
    }
}
