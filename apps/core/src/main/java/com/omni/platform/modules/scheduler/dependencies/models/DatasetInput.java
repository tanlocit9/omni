package com.omni.platform.modules.scheduler.dependencies.models;

import java.util.Map;

/**
 * Upstream dataset dependency recorded in manifest lineage.
 * <p>
 * Captures which upstream dataset version was consumed when producing
 * this dataset, enabling CURRENT_INPUTS checks to detect stale data.
 */
public record DatasetInput(
        String dataset,
        Map<String, String> partition,
        String dataVersion
) {
}
