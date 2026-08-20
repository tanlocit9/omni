package com.omni.platform.modules.scheduler.constants;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class SectorSeedConfigTest {

    @Test
    void definesCanonicalIcbLevelTwoTaxonomy() {
        assertThat(SectorSeedConfig.DEFAULT_TAXONOMY).isEqualTo("ICB");
        assertThat(SectorSeedConfig.DEFAULT_LEVEL).isEqualTo(2);
        assertThat(SectorSeedConfig.SECTOR_SEEDS).hasSize(19);
        assertThat(SectorSeedConfig.SECTOR_SEEDS)
                .allSatisfy(seed -> {
                    assertThat(seed.taxonomy()).isEqualTo("ICB");
                    assertThat(seed.taxonomyLevel()).isEqualTo(2);
                    assertThat(seed.code()).isNotBlank();
                    assertThat(seed.nameVi()).isNotBlank();
                    assertThat(seed.nameEn()).isNotBlank();
                    assertThat(seed.sourceCode()).matches("\\d{4}");
                });
        assertThat(SectorSeedConfig.SECTOR_SEEDS.stream().map(SectorSeedConfig.SectorSeed::code))
                .doesNotHaveDuplicates();
        assertThat(SectorSeedConfig.SECTOR_SEEDS.stream().map(SectorSeedConfig.SectorSeed::sourceCode))
                .doesNotHaveDuplicates();
    }

    @Test
    void preservesOfficialLevelTwoSourceCodes() {
        Map<String, String> sourceCodes = SectorSeedConfig.SECTOR_SEEDS.stream()
                .collect(Collectors.toMap(SectorSeedConfig.SectorSeed::code, SectorSeedConfig.SectorSeed::sourceCode));

        assertThat(sourceCodes).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("FOOD_AND_BEVERAGE", "3500"),
                Map.entry("CHEMICALS", "1300"),
                Map.entry("FINANCIAL_SERVICES", "8700"),
                Map.entry("TRAVEL_AND_LEISURE", "5700"),
                Map.entry("INDUSTRIAL_GOODS_AND_SERVICES", "2700"),
                Map.entry("TECHNOLOGY", "9500"),
                Map.entry("BASIC_RESOURCES", "1700"),
                Map.entry("CONSTRUCTION_AND_MATERIALS", "2300"),
                Map.entry("OIL_AND_GAS", "0500"),
                Map.entry("PERSONAL_AND_HOUSEHOLD_GOODS", "3700"),
                Map.entry("MEDIA", "5500"),
                Map.entry("REAL_ESTATE", "8600"),
                Map.entry("TELECOMMUNICATIONS", "6500"),
                Map.entry("UTILITIES", "7500"),
                Map.entry("RETAIL", "5300"),
                Map.entry("HEALTH_CARE", "4500"),
                Map.entry("AUTOMOBILES_AND_PARTS", "3300"),
                Map.entry("BANKS", "8300"),
                Map.entry("INSURANCE", "8500")));
    }
}
