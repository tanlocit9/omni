package com.omni.platform.modules.scheduler.constants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

class JobDefinitionConfigTest {

    @Test
    void derivesEnabledSectorsFromCanonicalSeeds() {
        assertThat(JobDefinitionConfig.ENABLED_SECTOR_CODES)
                .containsExactlyElementsOf(SectorSeedConfig.SECTOR_SEEDS.stream()
                        .map(SectorSeedConfig.SectorSeed::code)
                        .toList())
                .hasSize(19)
                .doesNotHaveDuplicates();
    }

    @Test
    void createsExpectedSeedCountsAndUniqueDatabaseIdentities() {
        assertThat(seeds(JobType.SYNC_STOCK_PRICE)).hasSize(19);
        assertThat(seeds(JobType.SECTOR_TRANSITION_ANALYZE)).hasSize(19);
        assertThat(seeds(JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES)).hasSize(19);

        assertThat(JobDefinitionConfig.JOB_DEFINITION_SEEDS)
                .allSatisfy(seed -> {
                    assertThat(seed.source()).isNotNull();
                    assertThat(seed.title()).isNotBlank();
                    assertThat(seed.cronExpr()).isNotBlank();
                    assertThat(CronExpression.isValidExpression(seed.cronExpr())).isTrue();
                    assertThat(seed.config()).isNotNull();
                });
        assertThat(JobDefinitionConfig.JOB_DEFINITION_SEEDS.stream()
                .map(seed -> seed.source() + "|" + seed.jobType() + "|" + seed.cronExpr()))
                .doesNotHaveDuplicates();
    }

    @Test
    void separatesBootstrapAndDeferredSeedsWithoutOverlap() {
        assertThat(JobDefinitionConfig.BOOTSTRAP_JOB_DEFINITION_SEEDS)
                .extracting(JobDefinitionSeed::jobType)
                .containsOnly(JobType.SYNC_SYMBOLS, JobType.SYNC_STOCK_PRICE, JobType.SYNC_SIGNALS);
        assertThat(JobDefinitionConfig.DEFERRED_JOB_DEFINITION_SEEDS)
                .extracting(JobDefinitionSeed::jobType)
                .doesNotContain(JobType.SYNC_SYMBOLS, JobType.SYNC_STOCK_PRICE, JobType.SYNC_SIGNALS);
        assertThat(JobDefinitionConfig.BOOTSTRAP_JOB_DEFINITION_SEEDS)
                .doesNotContainAnyElementsOf(JobDefinitionConfig.DEFERRED_JOB_DEFINITION_SEEDS);
        assertThat(JobDefinitionConfig.JOB_DEFINITION_SEEDS)
                .containsExactlyElementsOf(java.util.stream.Stream.concat(
                        JobDefinitionConfig.BOOTSTRAP_JOB_DEFINITION_SEEDS.stream(),
                        JobDefinitionConfig.DEFERRED_JOB_DEFINITION_SEEDS.stream())
                        .toList());
    }

    @Test
    void createsOneStockPriceAndTransitionSeedPerEnabledSector() {
        assertThat(seeds(JobType.SYNC_STOCK_PRICE).stream().map(this::singleConfiguredSector))
                .containsExactlyInAnyOrderElementsOf(JobDefinitionConfig.ENABLED_SECTOR_CODES);

        for (JobType type : List.of(JobType.SECTOR_TRANSITION_ANALYZE,
                JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES)) {
            assertThat(seeds(type)).allSatisfy(seed -> assertThat(configuredSectors(seed))
                    .containsExactlyElementsOf(JobDefinitionConfig.ENABLED_SECTOR_CODES));
            assertThat(seeds(type).stream().map(this::singleFocusSector))
                    .containsExactlyInAnyOrderElementsOf(JobDefinitionConfig.ENABLED_SECTOR_CODES);
        }
    }

    @Test
    void documentsVerifiedSymbolFeatureAndTransitionOutcomeLineage() {
        JobDefinitionSeed symbolFeatures = onlySeed(JobType.PRECOMPUTE_SYMBOL_FEATURES);
        assertThat(dependencies(symbolFeatures, JobDefinitionConfig.CONFIG_KEY_DEPENDS_ON_DATASETS))
                .containsExactly("symbols", "sectors", "eod")
                .doesNotContain("indicators");

        for (JobDefinitionSeed outcome : seeds(JobType.SECTOR_TRANSITION_EVALUATE_OUTCOMES)) {
            assertThat(dependencies(outcome, JobDefinitionConfig.CONFIG_KEY_DEPENDS_ON_JOBS))
                    .containsExactly(JobType.PRECOMPUTE_SECTOR_FEATURES.name())
                    .doesNotContain(JobType.SECTOR_TRANSITION_ANALYZE.name());
            assertThat(dependencies(outcome, JobDefinitionConfig.CONFIG_KEY_DEPENDS_ON_DATASETS))
                    .containsExactly("sector-transition-predictions", "sector-features")
                    .doesNotContain("sector-transition-decisions", "eod");
            assertThat(dependencies(outcome, JobDefinitionConfig.CONFIG_KEY_PRODUCES_DATASETS))
                    .containsExactly("sector-transition-outcomes");
        }
    }

    @Test
    void normalizesMinuteOverflowWithoutCrossingDayBoundary() {
        assertThat(JobDefinitionConfig.weekdayCron(18, 65)).isEqualTo("0 5 19 * * MON-FRI");
        assertThatThrownBy(() -> JobDefinitionConfig.weekdayCron(23, 60))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("23:59");
        assertThatThrownBy(() -> JobDefinitionConfig.weekdayCron(-1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidTransitionGenerationInputs() {
        assertThatThrownBy(() -> JobDefinitionConfig.generateSectorTransitionSeeds(
                JobType.SYNC_SIGNALS, 20, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Sector Transition job type");
        assertThatThrownBy(() -> JobDefinitionConfig.generateSectorTransitionSeeds(
                JobType.SECTOR_TRANSITION_ANALYZE, 20, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepMinutes");
        assertThatThrownBy(() -> JobDefinitionConfig.generateSectorTransitionSeeds(
                JobType.SECTOR_TRANSITION_ANALYZE, 23, 30, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("23:59");
    }

    private List<JobDefinitionSeed> seeds(JobType type) {
        return JobDefinitionConfig.JOB_DEFINITION_SEEDS.stream()
                .filter(seed -> seed.jobType() == type)
                .toList();
    }

    private JobDefinitionSeed onlySeed(JobType type) {
        return seeds(type).getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<String> configuredSectors(JobDefinitionSeed seed) {
        return (List<String>) seed.config().get(JobDefinitionConfig.CONFIG_KEY_SECTOR_CODES);
    }

    private String singleConfiguredSector(JobDefinitionSeed seed) {
        return configuredSectors(seed).getFirst();
    }

    @SuppressWarnings("unchecked")
    private String singleFocusSector(JobDefinitionSeed seed) {
        return ((List<String>) seed.config().get(JobDefinitionConfig.CONFIG_KEY_FOCUS_SECTOR_CODES)).getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<String> dependencies(JobDefinitionSeed seed, String key) {
        Map<String, Object> dataDependencies = (Map<String, Object>) seed.config()
                .get(JobDefinitionConfig.CONFIG_KEY_DATA_DEPENDENCIES);
        assertThat(dataDependencies.get(JobDefinitionConfig.CONFIG_KEY_DEPENDENCY_MODE))
                .isEqualTo(JobDefinitionConfig.CONFIG_VALUE_DEPENDENCY_MODE_DOCUMENTATION_ONLY);
        return (List<String>) dataDependencies.get(key);
    }
}
