package com.omni.platform.modules.scheduler.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Handoff;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeEdge;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.StepKey;

class StaticDefinitionDependenciesTest {
    private static final List<JobDefinitionSeed> SEEDS = JobDefinitionConfig.JOB_DEFINITION_SEEDS;

    @Test
    void preservesEveryJsonBaselineDefinitionEdgeAndTopologyIncludingExplicitRoots() {
        var typed = StaticDefinitionDependencies.canonical(SEEDS);
        var legacy = JsonSeedDependencyFixture.fromSeeds(SEEDS);
        assertThat(typed.predecessors()).isEqualTo(legacy.predecessors()).hasSize(SEEDS.size());
        assertThat(typed.declaration()).isEqualTo(legacy.declaration());
        assertThat(typed.topology().toMermaid()).isEqualTo(legacy.topology().toMermaid());
        for (JobDefinitionSeed seed : SEEDS) {
            var upstream = typed.predecessors().get(JobDefinitionIdentity.from(seed));
            if (seed.jobType() == JobType.SYNC_SYMBOLS || seed.jobType() == JobType.SYNC_METADATA) {
                assertThat(upstream).isEmpty();
            } else {
                assertThat(upstream).isNotEmpty();
            }
        }
        assertThat(new StaticJobTopologyProvider().definitionDependencies().predecessors())
                .isEqualTo(typed.predecessors());
    }

    @Test
    void canonicalEdgesIgnoreJsonAndPreserveOrderAndSeedConfiguration() {
        var before = SEEDS.stream().map(JobDefinitionSeed::config).toList();
        var stripped =
                SEEDS.stream()
                        .map(
                                seed ->
                                        new JobDefinitionSeed(
                                                seed.source(),
                                                seed.fallbackSources(),
                                                seed.jobType(),
                                                seed.title(),
                                                seed.cronExpr(),
                                                Map.of()))
                        .toList();
        var reversed = new ArrayList<>(stripped);
        java.util.Collections.reverse(reversed);
        var typed = StaticDefinitionDependencies.canonical(SEEDS);
        assertThat(StaticDefinitionDependencies.canonical(reversed).declaration())
                .isEqualTo(typed.declaration());
        assertThat(SEEDS.stream().map(JobDefinitionSeed::config).toList()).isEqualTo(before);
        assertThatThrownBy(() -> typed.predecessors().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(
                        () ->
                                typed.predecessors()
                                        .values()
                                        .iterator()
                                        .next()
                                        .add(identity(JobType.SYNC_SYMBOLS, "x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void typedResolverMatchesJsonBaselineForEveryTargetAndScopeWithoutRuntimeDefaults() {
        var typed = new RequiredPathResolver(StaticDefinitionDependencies.canonical(SEEDS));
        var legacy = new RequiredPathResolver(JsonSeedDependencyFixture.fromSeeds(SEEDS));
        for (ScopeType type : ScopeType.values()) {
            Scope scope = new Scope(type, "test:" + type, LocalDate.of(2026, 10, 17));
            Handoff handoff = explicitHandoff(scope);
            for (JobDefinitionSeed seed : SEEDS) {
                var target = JobDefinitionIdentity.from(seed);
                assertThat(typed.resolveRequiredPath(target, scope, handoff, 64, 1024))
                        .isEqualTo(legacy.resolveRequiredPath(target, scope, handoff, 64, 1024));
            }
        }
        var seed = SEEDS.getFirst();
        Scope scope = new Scope(ScopeType.SYMBOL, "HOSE:HPG", LocalDate.of(2026, 10, 17));
        assertThatThrownBy(
                        () ->
                                typed.resolveRequiredPath(
                                        JobDefinitionIdentity.from(seed),
                                        scope,
                                        new Handoff(Map.of(), Map.of(), Map.of()),
                                        64,
                                        1024))
                .hasMessageContaining("UNSUPPORTED_SCOPE");
    }

    @Test
    void isolatesSignalStrategiesAndRejectsBindingOutsideDeclaredDefinitionEdges() {
        var root = identity(JobType.SYNC_SYMBOLS, "root");
        var selected = identity(JobType.SYNC_STOCK_PRICE, "selected");
        var other = identity(JobType.SYNC_STOCK_PRICE, "other");
        var target = identity(JobType.SYNC_SIGNALS, "target");
        var declaration =
                new StaticDefinitionDependencies(
                        Map.of(
                                root,
                                List.of(),
                                selected,
                                List.of(root),
                                other,
                                List.of(root),
                                target,
                                List.of(selected)));
        Scope scope = new Scope(ScopeType.GLOBAL, "test", LocalDate.of(2026, 10, 17));
        Map<JobDefinitionIdentity, Set<Scope>> capabilities = new HashMap<>();
        Map<ScopeEdge, Scope> mappings = new HashMap<>();
        declaration
                .predecessors()
                .forEach(
                        (definition, upstream) -> {
                            capabilities.put(definition, Set.of(scope));
                            upstream.forEach(
                                    value ->
                                            mappings.put(
                                                    new ScopeEdge(
                                                            new StepKey(definition, scope), value),
                                                    scope));
                        });
        var resolver = new RequiredPathResolver(declaration);
        var valid = new Handoff(Map.of(), capabilities, mappings);
        assertThat(resolver.resolveRequiredPath(target, scope, valid, 64, 10).steps())
                .extracting(step -> step.key().definition())
                .containsExactly(root, selected, target);
        var invalid =
                new Handoff(
                        Map.of(
                                TopologyNodeKey.forJobType(JobType.SYNC_STOCK_PRICE),
                                List.of(other)),
                        capabilities,
                        mappings);
        assertThatThrownBy(() -> resolver.resolveRequiredPath(target, scope, invalid, 64, 10))
                .hasMessageContaining("INVALID_BINDING");

        var canonical = StaticDefinitionDependencies.canonical(SEEDS);
        for (JobDefinitionSeed signal :
                SEEDS.stream().filter(seed -> seed.jobType() == JobType.SYNC_SIGNALS).toList()) {
            var types =
                    canonical.predecessors().get(JobDefinitionIdentity.from(signal)).stream()
                            .map(JobDefinitionIdentity::jobType)
                            .distinct()
                            .toList();
            if (JobDefinitionConfig.SIGNAL_STRATEGY_CONFIRMED_TREND_EQUALS.equals(
                    signal.config().get(JobDefinitionConfig.CONFIG_KEY_SIGNAL_STRATEGY))) {
                assertThat(types).contains(JobType.SYNC_INTRADAY_EOD);
            } else {
                assertThat(types).doesNotContain(JobType.SYNC_INTRADAY_EOD);
            }
        }
    }

    @Test
    void rejectsUnknownDuplicateSelfCyclicAndOversizedDeclarations() {
        var root = identity(JobType.SYNC_SYMBOLS, "root");
        var child = identity(JobType.SYNC_STOCK_PRICE, "child");
        assertThatThrownBy(() -> new StaticDefinitionDependencies(Map.of(child, List.of(root))))
                .hasMessageContaining("Unknown");
        assertThatThrownBy(
                        () ->
                                new StaticDefinitionDependencies(
                                        Map.of(root, List.of(), child, List.of(root, root))))
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> new StaticDefinitionDependencies(Map.of(root, List.of(root))))
                .hasMessageContaining("Self edge");
        assertThatThrownBy(
                        () ->
                                new StaticDefinitionDependencies(
                                        Map.of(root, List.of(child), child, List.of(root))))
                .hasMessageContaining("cycle");
        assertThatThrownBy(
                        () ->
                                StaticDefinitionDependencies.canonical(
                                        List.of(SEEDS.getFirst(), SEEDS.getFirst())))
                .hasMessageContaining("Ambiguous");
        assertThatThrownBy(
                        () ->
                                StaticDefinitionDependencies.canonical(
                                        SEEDS.stream()
                                                .filter(
                                                        seed ->
                                                                seed.jobType()
                                                                        != JobType.SYNC_SYMBOLS)
                                                .toList()))
                .hasMessageContaining("Unknown");
        for (var signal :
                List.of(
                        new JobDefinitionSeed(
                                DataSource.ANALYZER,
                                List.of(),
                                JobType.SYNC_SIGNALS,
                                "Unknown strategy",
                                "0 15 19 * * MON-FRI",
                                Map.of()),
                        new JobDefinitionSeed(
                                DataSource.VCI,
                                List.of(),
                                JobType.SYNC_SIGNALS,
                                "Unknown source",
                                "0 0 19 * * MON-FRI",
                                Map.of()))) {
            assertThatThrownBy(() -> StaticDefinitionDependencies.canonical(List.of(signal)))
                    .hasMessageContaining("Unknown signal definition");
        }
        Map<JobDefinitionIdentity, List<JobDefinitionIdentity>> oversized = new HashMap<>();
        for (int i = 0; i <= RequiredPathResolver.MAX_STEPS; i++) {
            oversized.put(identity(JobType.SYNC_SYMBOLS, "cron-" + i), List.of());
        }
        assertThatThrownBy(() -> new StaticDefinitionDependencies(oversized))
                .hasMessageContaining("PATH_BOUND_EXCEEDED");
    }

    // Explicit test-only support and all-definition binding; no producer capability is activated.
    private Handoff explicitHandoff(Scope scope) {
        Map<TopologyNodeKey, List<JobDefinitionIdentity>> bindings = new HashMap<>();
        Map<JobDefinitionIdentity, Set<Scope>> capabilities = new HashMap<>();
        Map<ScopeEdge, Scope> mappings = new HashMap<>();
        for (JobDefinitionSeed seed : SEEDS) {
            var identity = JobDefinitionIdentity.from(seed);
            bindings.computeIfAbsent(
                            TopologyNodeKey.forJobType(seed.jobType()),
                            ignored -> new ArrayList<>())
                    .add(identity);
            capabilities.put(identity, Set.of(scope));
            for (JobDefinitionSeed upstream : SEEDS) {
                mappings.put(
                        new ScopeEdge(
                                new StepKey(identity, scope), JobDefinitionIdentity.from(upstream)),
                        scope);
            }
        }
        return new Handoff(bindings, capabilities, mappings);
    }

    private static JobDefinitionIdentity identity(JobType type, String cron) {
        return new JobDefinitionIdentity(DataSource.ANALYZER, type, cron);
    }
}
