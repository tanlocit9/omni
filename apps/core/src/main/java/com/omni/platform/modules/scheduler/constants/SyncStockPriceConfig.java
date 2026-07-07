package com.omni.platform.modules.scheduler.constants;

import java.util.List;
import java.util.Objects;

public record SyncStockPriceConfig(
        Filters filters) {

    public record Filters(
            List<String> exchanges,
            List<String> symbols,
            List<String> sectors) {

        public Filters {
            exchanges = normalize(exchanges);
            symbols = normalize(symbols);
            sectors = normalize(sectors);
        }

        private static List<String> normalize(List<String> values) {
            if (values == null)
                return List.of();
            return values.stream()
                    .filter(Objects::nonNull)
                    .map(v -> v.toUpperCase())
                    .toList();
        }
    }
}