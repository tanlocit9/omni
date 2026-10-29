package com.omni.platform.modules.scheduler.expansion;

import static org.assertj.core.api.Assertions.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.ExpandedJobMessage;
import com.omni.platform.modules.scheduler.messaging.JobMessage;
import com.omni.platform.modules.scheduler.messaging.SymbolJobMessage;
import com.omni.platform.modules.scheduler.messaging.SyncSymbolsJobMessage;
import com.omni.platform.modules.scheduler.messaging.IndicatorJobMessage;
import com.omni.platform.modules.scheduler.messaging.SignalJobMessage;
import com.omni.platform.modules.scheduler.messaging.IntradayEodJobMessage;
import com.omni.platform.modules.scheduler.messaging.SectorWaveSymbolFeatureJobMessage;
import com.omni.platform.modules.scheduler.messaging.SectorWaveSectorFeatureJobMessage;
import com.omni.platform.modules.scheduler.messaging.SyncMetadataJobMessage;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration;
import com.omni.platform.shared.executions.WorkType;
import org.junit.jupiter.api.Test;

class GraphWorkExpansionTest {
    private static final Instant ACCEPTED = Instant.parse("2026-11-02T03:00:00Z");

    private JobDefinition job(JobType type, Map<String, Object> config) {
        var job = new JobDefinition();
        job.setId(UUID.randomUUID());
        job.setSource(type == JobType.SYNC_STOCK_PRICE ? DataSource.VND : DataSource.ANALYZER);
        job.setJobType(type);
        job.setCronExpr("custom-cadence");
        job.setConfigJson(config);
        return job;
    }

    private List<ExpandedJobMessage> expand(JobDefinition job, Map<String, Object> request, Instant at) {
        assertThat(at).isEqualTo(ACCEPTED);
        var parent = new JobExecutionHistory();
        parent.setMetaJson(Map.of("expansionRequest", request));
        var plan = WorkExpansion.resolve(job, parent, at);
        WorkType unit = WorkExpansion.unit(job.getJobType());
        var scope = WorkExpansion.map(plan.config().get("_expansionScope"));
        List<String> keys = switch (unit) {
            case SYMBOL -> List.of("HOSE-HPG", "HNX-SHS");
            case EXCHANGE -> List.of("HOSE", "HNX");
            case SECTOR -> List.of("BANKS", "TECH");
            case GLOBAL -> List.of(String.valueOf(plan.config().getOrDefault("strategy", "SYNC_METADATA")));
        };
        List<ExpandedJobMessage> result = new ArrayList<>();
        for (LocalDate day : plan.days()) {
            for (String key : keys) {
                if (scope.get("type") != null && !"GLOBAL".equals(scope.get("type"))
                        && !key.equals(scope.get("key"))) continue;
                var command = command(job, unit, key, day, plan.config());
                var parameters = WorkExpansion.parameters(command, plan.config());
                result.add(new ExpandedJobMessage(command, job.getJobType().name(), day,
                        parameters, WorkExpansion.identity(job.getJobType(), unit, key, day, parameters)));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private JobMessage command(JobDefinition job, WorkType unit, String key,
                               LocalDate day, Map<String, Object> config) {
        UUID id = job.getId(), execution = UUID.randomUUID(), parent = UUID.randomUUID();
        String source = String.valueOf(config.get("source"));
        String timeframe = String.valueOf(config.getOrDefault("timeframe", "1d"));
        return switch (job.getJobType()) {
            case SYNC_STOCK_PRICE -> new SymbolJobMessage(id, execution, parent, source,
                    unit, key, key, null, null, Map.of());
            case SYNC_SYMBOLS -> new SyncSymbolsJobMessage(id, execution, parent, source,
                    unit, key, key, ACCEPTED, Map.of());
            case SYNC_INDICATORS -> new IndicatorJobMessage(id, execution, parent, source,
                    unit, key, key, timeframe, String.valueOf(config.get("indicatorSource")),
                    (List<String>) config.get("indicators"), Map.of());
            case SYNC_SIGNALS -> new SignalJobMessage(id, execution, parent, source,
                    unit, key, key, timeframe, String.valueOf(config.get("strategy")), Map.of());
            case SYNC_INTRADAY_EOD -> new IntradayEodJobMessage(id, execution, parent, source,
                    unit, key, key, key.split("-", 2)[0], day, "VCI", Map.of());
            case PRECOMPUTE_SYMBOL_FEATURES -> new SectorWaveSymbolFeatureJobMessage(
                    id, execution, parent, source, unit, key, key, timeframe, Map.of());
            case PRECOMPUTE_SECTOR_FEATURES -> new SectorWaveSectorFeatureJobMessage(
                    id, execution, parent, source, unit, key, key, 2, timeframe, Map.of());
            case SYNC_METADATA -> new SyncMetadataJobMessage(id, execution, parent,
                    source, unit, key, null, Map.of());
            default -> throw new IllegalArgumentException("Unsupported test producer");
        };
    }

    private GraphWorkExpansion.Plan plan(JobDefinition target, Map<String, Object> request,
                                          List<JobDefinition> catalog) {
        return GraphWorkExpansion.resolve(target.getId(), request, ACCEPTED, catalog,
                this::expand, (symbol, sector, level) -> sector.equals("BANKS") && symbol.equals("HOSE-HPG"));
    }

    @Test
    void projectsSymbolToExchangeAndUsesAcceptedCurrentCatalogForHistoricalDays() {
        var symbols = job(JobType.SYNC_SYMBOLS, Map.of());
        var stock = job(JobType.SYNC_STOCK_PRICE, Map.of());
        var indicators = job(JobType.SYNC_INDICATORS, Map.of());
        var result = plan(indicators, Map.of("scope", Map.of("type", "SYMBOL", "key", "HOSE-HPG"),
                "dateFrom", "2026-10-30", "dateTo", "2026-11-02"), List.of(indicators, stock, symbols));
        assertThat(result.targets()).hasSize(2);
        assertThat(result.works()).hasSize(5);
        assertThat(result.dependencies()).hasSize(4);
        assertThat(result.works()).filteredOn(w -> w.jobType() == JobType.SYNC_SYMBOLS)
                .singleElement().satisfies(w -> {
                    assertThat(w.workKey()).isEqualTo("HOSE");
                    assertThat(w.businessDate()).isEqualTo(LocalDate.parse("2026-11-02"));
                });
        assertThat(result.definitions()).hasSameSizeAs(result.works());
    }

    @Test
    void targetOnlyParametersDoNotFragmentAncestorsAndUpstreamSourceStaysOwnedByPreset() {
        var target = job(JobType.SYNC_INDICATORS, Map.of());
        var stock = job(JobType.SYNC_STOCK_PRICE, Map.of("source", "VND"));
        var symbols = job(JobType.SYNC_SYMBOLS, Map.of());
        var catalog = List.of(target, stock, symbols);
        var first = plan(target, Map.of("parameters", Map.of("targetOnly", "one")), catalog);
        var second = plan(target, Map.of("parameters", Map.of("targetOnly", "two")), catalog);
        assertThat(first.targets()).doesNotContainAnyElementsOf(second.targets());
        assertThat(first.works()).filteredOn(w -> w.jobType() == JobType.SYNC_STOCK_PRICE)
                .containsExactlyElementsOf(second.works().stream()
                        .filter(w -> w.jobType() == JobType.SYNC_STOCK_PRICE).toList())
                .allSatisfy(w -> assertThat(w.parameters()).containsEntry("source", "VND")
                        .doesNotContainKey("targetOnly"));
        var reversed = new ArrayList<>(catalog);
        Collections.reverse(reversed);
        assertThat(plan(target, Map.of("parameters", Map.of("targetOnly", "one")), reversed)).isEqualTo(first);
        assertThatThrownBy(() -> first.works().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resolvesStrategyEdgesFromEffectiveParametersRatherThanCronAndPreservesRealDifferences() {
        var target = job(JobType.SYNC_SIGNALS, Map.of("strategy", "TREND_MOMENTUM_V1"));
        var catalog = List.of(target, job(JobType.SYNC_SYMBOLS, Map.of()),
                job(JobType.SYNC_STOCK_PRICE, Map.of()), job(JobType.SYNC_INDICATORS, Map.of()),
                job(JobType.SYNC_INTRADAY_EOD, Map.of()));
        var ordinary = plan(target, Map.of(), catalog);
        var confirmed = plan(target, Map.of("parameters", Map.of("strategy", "CONFIRMED_TREND_EQUALS")), catalog);
        assertThat(ordinary.works()).noneMatch(w -> w.jobType() == JobType.SYNC_INTRADAY_EOD);
        assertThat(confirmed.works()).anyMatch(w -> w.jobType() == JobType.SYNC_INTRADAY_EOD);
        assertThat(StaticTopologyDeclaration.upstreamTypes(JobType.SYNC_SIGNALS,
                Map.of("strategy", "ICHIMOKU_V1"))).doesNotContain(JobType.SYNC_INTRADAY_EOD);
        assertThatThrownBy(() -> StaticTopologyDeclaration.upstreamTypes(JobType.SYNC_SIGNALS,
                Map.of("strategy", "UNKNOWN"))).hasMessageContaining("Unsupported");
    }

    @Test
    void deduplicatesEquivalentDefinitionsInsidePlanButDoesNotConflateSources() {
        var target = job(JobType.SYNC_INDICATORS, Map.of());
        var stock = job(JobType.SYNC_STOCK_PRICE, Map.of("source", "VND"));
        var equivalent = job(JobType.SYNC_STOCK_PRICE, Map.of("source", "VND"));
        var different = job(JobType.SYNC_STOCK_PRICE, Map.of("source", "VCI"));
        var result = plan(target, Map.of(), List.of(target, stock, equivalent, different,
                job(JobType.SYNC_SYMBOLS, Map.of())));
        assertThat(result.works()).filteredOn(w -> w.jobType() == JobType.SYNC_STOCK_PRICE).hasSize(4);
        assertThat(result.definitions().values()).anyMatch(ids -> ids.size() == 2);
    }

    @Test
    void supportsSectorAndGlobalCapabilitiesAndFailsWhenScopedAncestorIsMissing() {
        var sectors = job(JobType.PRECOMPUTE_SECTOR_FEATURES, Map.of());
        var catalog = List.of(sectors, job(JobType.PRECOMPUTE_SYMBOL_FEATURES, Map.of()),
                job(JobType.SYNC_STOCK_PRICE, Map.of()), job(JobType.SYNC_SYMBOLS, Map.of()));
        var request = Map.<String, Object>of("scope", Map.of("type", "SECTOR", "key", "BANKS"));
        assertThat(plan(sectors, request, catalog).works()).noneMatch(w -> w.workKey().equals("HNX-SHS"));
        assertThatThrownBy(() -> plan(sectors,
                Map.of("scope", Map.of("type", "SECTOR", "key", "TECH")), catalog))
                .hasMessageContaining("Missing scoped ancestor");
        var global = job(JobType.SYNC_METADATA, Map.of());
        assertThat(plan(global, Map.of(), List.of(global)).works()).singleElement()
                .satisfies(w -> assertThat(w.workType()).isEqualTo(WorkType.GLOBAL));
    }

    @Test
    void rejectsMissingInactiveDuplicateCatalogAndTamperedExpansionRatherThanPartialPlans() {
        var target = job(JobType.SYNC_INDICATORS, Map.of());
        assertThatThrownBy(() -> plan(target, Map.of(), List.of(target)))
                .hasMessageContaining("Missing scoped ancestor");
        assertThatThrownBy(() -> plan(target, Map.of(), List.of(target, target)))
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> plan(target, Map.of(), Collections.nCopies(1025, target)))
                .hasMessageContaining("BOUND");
        target.setIsActive(false);
        assertThatThrownBy(() -> plan(target, Map.of(), List.of(target))).hasMessageContaining("Inactive");
        target.setIsActive(true);
        assertThatThrownBy(() -> GraphWorkExpansion.resolve(target.getId(), Map.of(), ACCEPTED,
                List.of(target), (job, request, at) -> {
                    var original = expand(job, request, at).getFirst();
                    return List.of(new ExpandedJobMessage(original.command(), original.jobType(),
                            original.businessDate(), original.parameters(), "work:invalid"));
                }, (symbol, sector, level) -> true)).hasMessageContaining("Invalid expanded");
        assertThat(plan(target, Map.of("dateFrom", "2026-10-31", "dateTo", "2026-11-01"),
                List.of(target)).works()).isEmpty();
    }
}
