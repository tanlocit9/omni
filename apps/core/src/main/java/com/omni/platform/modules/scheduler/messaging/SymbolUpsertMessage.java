package com.omni.platform.modules.scheduler.messaging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SymbolUpsertMessage(UUID jobId, UUID logId,
        String exchange,
        int expectedCount,
        int actualCount,
        List<SymbolRecord> symbols,
        Instant detectedAt) {

    public record SymbolRecord(
            String code,
            String exchange,
            String type,
            String status,
            String isin,
            String companyId,
            String companyName,
            LocalDate listedDate,
            String sectorCode,
            String sectorTaxonomy,
            Integer sectorLevel,
            String sourceSectorCode,
            String sourceSectorNameVi,
            String sourceSectorNameEn,
            String icbLv1Code,
            String icbLv1NameVi,
            String icbLv1NameEn,
            String icbLv2Code,
            String icbLv2NameVi,
            String icbLv2NameEn,
            String icbLv3Code,
            String icbLv3NameVi,
            String icbLv3NameEn,
            String icbLv4Code,
            String icbLv4NameVi,
            String icbLv4NameEn,
            Instant classificationUpdatedAt,
            Map<String, Object> meta) {
    }
}