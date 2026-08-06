package com.omni.platform.modules.scheduler.constants;

import java.util.List;
import java.util.Objects;

public record SectorJobFilterConfig(
        List<String> sectorCodes,
        int sectorLevel) {

    public static final int DEFAULT_SECTOR_LEVEL = 1;
    public static final int MIN_SECTOR_LEVEL = 1;
    public static final int MAX_SECTOR_LEVEL = 4;

    public SectorJobFilterConfig {
        sectorCodes = normalizeCodes(sectorCodes);
        if (sectorLevel < MIN_SECTOR_LEVEL || sectorLevel > MAX_SECTOR_LEVEL) {
            sectorLevel = DEFAULT_SECTOR_LEVEL;
        }
    }

    public static SectorJobFilterConfig defaults() {
        return new SectorJobFilterConfig(List.of(), DEFAULT_SECTOR_LEVEL);
    }

    private static List<String> normalizeCodes(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toString().trim())
                .filter(value -> !value.isBlank())
                .map(String::toUpperCase)
                .distinct()
                .toList();
    }
}
