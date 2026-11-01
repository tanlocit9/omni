package com.omni.platform.modules.scheduler.work;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;

class DefinitionConsolidationTest {
    private JobDefinition stock(String cron, List<String> sectors) {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setJobType(JobType.SYNC_STOCK_PRICE);
        job.setSource(DataSource.VND);
        job.setCronExpr(cron);
        job.setConfigJson(Map.of("sectorCodes", sectors, "sectorLevel", 2));
        return job;
    }

    @Test
    void unionsOnlySelectorsAndPreservesOriginalPresetObjects() {
        var first = stock("0 0 18 * * MON-FRI", List.of("BANKS"));
        var second = stock("0 2 18 * * MON-FRI", List.of("STEEL"));
        var group = DefinitionConsolidationService.inventory(List.of(second, first)).getFirst();
        assertThat(group.retained()).isEqualTo(first.getId());
        assertThat(group.configuration()).containsEntry("sectorCodes", List.of("BANKS", "STEEL"));
        assertThat(first.getConfigJson()).containsEntry("sectorCodes", List.of("BANKS"));
        assertThatThrownBy(() -> group.configuration().put("sectorLevel", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allSectorSelectorAbsorbsSubsetsWithoutNarrowing() {
        var first = stock("0 0 18 * * MON-FRI", List.of());
        var second = stock("0 2 18 * * MON-FRI", List.of("BANKS"));
        assertThat(
                        DefinitionConsolidationService.inventory(List.of(first, second))
                                .getFirst()
                                .configuration())
                .containsEntry("sectorCodes", List.of());
    }

    @Test
    void separatesSourceFallbackStrategyReadinessAndClassificationDifferences() {
        var base = stock("0 0 18 * * MON-FRI", List.of("BANKS"));
        var source = stock("0 2 18 * * MON-FRI", List.of("STEEL"));
        source.setSource(DataSource.SSI);
        var fallback = stock("0 4 18 * * MON-FRI", List.of("STEEL"));
        fallback.setFallbackSources(List.of(DataSource.SSI));
        var level = stock("0 6 18 * * MON-FRI", List.of("STEEL"));
        level.setConfigJson(Map.of("sectorCodes", List.of("STEEL"), "sectorLevel", 1));
        var strategy = stock("0 8 18 * * MON-FRI", List.of("STEEL"));
        strategy.setConfigJson(Map.of("sectorLevel", 2, "strategy", "OTHER"));
        var readiness = stock("0 10 18 * * MON-FRI", List.of("STEEL"));
        readiness.setConfigJson(Map.of("sectorLevel", 2, "dataDependencies", List.of("eod")));
        assertThat(
                        DefinitionConsolidationService.inventory(
                                List.of(base, source, fallback, level, strategy, readiness)))
                .isEmpty();
    }

    @Test
    void globalOutputUniverseIsNeverMergedAsASymbolSelector() {
        var first = stock("0 0 18 * * MON-FRI", List.of("BANKS"));
        first.setJobType(JobType.SECTOR_TRANSITION_ANALYZE);
        var second = stock("0 2 18 * * MON-FRI", List.of("STEEL"));
        second.setJobType(JobType.SECTOR_TRANSITION_ANALYZE);
        assertThat(DefinitionConsolidationService.inventory(List.of(first, second))).isEmpty();
    }

    @Test
    void unscheduledInactiveRetiredAndCronlessPresetsCannotJoinAnInventory() {
        var base = stock("0 0 18 * * MON-FRI", List.of());
        for (String state : List.of("inactive", "disabled", "retired", "null-cron", "blank-cron")) {
            var ignored = stock("0 2 18 * * MON-FRI", List.of());
            switch (state) {
                case "inactive" -> ignored.setIsActive(false);
                case "disabled" -> ignored.setScheduleEnabled(false);
                case "retired" -> ignored.setCanonicalDefinitionId(UUID.randomUUID());
                case "null-cron" -> ignored.setCronExpr(null);
                case "blank-cron" -> ignored.setCronExpr(" ");
            }
            assertThat(DefinitionConsolidationService.inventory(List.of(base, ignored)))
                    .as(state)
                    .isEmpty();
        }
    }

    @Test
    void missingConfigurationAndFallbacksNormalizeWithoutNarrowingTheUniverse() {
        var first = stock("0 0 18 * * MON-FRI", List.of());
        var second = stock("0 2 18 * * MON-FRI", List.of());
        first.setConfigJson(null);
        first.setFallbackSources(null);
        second.setConfigJson(Map.of());
        second.setFallbackSources(List.of());
        assertThat(DefinitionConsolidationService.inventory(List.of(first, second)))
                .singleElement()
                .satisfies(
                        group ->
                                assertThat(group.configuration())
                                        .containsEntry("sectorCodes", List.of()));
        first.setJobType(JobType.SYNC_METADATA);
        second.setJobType(JobType.SYNC_METADATA);
        assertThat(DefinitionConsolidationService.inventory(List.of(first, second)))
                .singleElement()
                .satisfies(group -> assertThat(group.configuration()).isEmpty());
    }

    @Test
    void disabledGraphCannotApplyConsolidation() {
        var service = new DefinitionConsolidationService(null, null, null, null, false);
        assertThatThrownBy(() -> service.consolidate(java.time.Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires graph");
    }
}
