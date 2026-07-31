package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SectorUpsertMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String exchange,
        int expectedCount,
        int actualCount,
        List<SectorRecord> sectors,
        Instant detectedAt) {

    public record SectorRecord(
            String sectorCode,
            String sectorTaxonomy,
            Integer sectorLevel,
            String sourceSectorCode,
            String sourceSectorNameVi,
            String sourceSectorNameEn,
            Instant classificationUpdatedAt,
            Map<String, Object> meta) {
    }
}
