package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SymbolUpsertMessage(
        UUID jobDefinitionId,
        UUID executionId,
        UUID parentExecutionId,
        String exchange,
        int expectedCount,
        int actualCount,
        List<SymbolRecord> symbols,
        Instant detectedAt) {

    public record SymbolRecord(
            String code,
            String exchange,
            String companyId,
            String companyName,
            LocalDate listedDate,
            String sectorTaxonomy,
            Integer sectorLevel,
            String sourceSectorCode,
            String sectorLv1Code,
            String sectorLv2Code,
            String sectorLv3Code,
            String sectorLv4Code,
            Instant classificationUpdatedAt,
            Map<String, Object> meta) {
    }
}
