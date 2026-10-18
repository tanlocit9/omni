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

class RequiredPathResolverTest {

    private static final Scope SCOPE =
            new Scope(ScopeType.SECTOR, "VCI:BANKS", LocalDate.of(2026, 10, 15));
    private static final JobDefinitionSeed A = seed(JobType.SYNC_SYMBOLS, "a");
    private static final JobDefinitionSeed B = seed(JobType.SYNC_STOCK_PRICE, "b", A.jobType());
    private static final JobDefinitionSeed C = seed(JobType.SYNC_INDICATORS, "c", A.jobType());
    private static final JobDefinitionSeed D =
            seed(JobType.SYNC_SIGNALS, "d", B.jobType(), C.jobType());
    private static final List<JobDefinitionSeed> DIAMOND = List.of(A, B, C, D);

    @Test
    void resolvesDiamondDependencyFirstWithoutUnrelatedRootAndIsDeterministic() {
        JobDefinitionSeed unrelated = seed(JobType.SYNC_METADATA, "other");
        List<JobDefinitionSeed> seeds = List.of(D, unrelated, C, B, A);
        var result = resolve(seeds, D, handoff(seeds), 64, 20);
        assertThat(result.contractVersion()).isEqualTo(1);
        assertThat(result.steps())
                .extracting(step -> step.key().definition())
                .containsExactly(id(A), id(C), id(B), id(D));
        assertThat(result.steps().get(3).predecessors()).containsExactly(key(C), key(B));
        assertThat(result)
                .isEqualTo(resolve(List.of(A, B, C, D, unrelated), D, handoff(seeds), 64, 20));
        assertThatThrownBy(() -> result.steps().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(resolve(seeds, unrelated, handoff(seeds), 0, 1).steps()).hasSize(1);
    }

    @Test
    void usesDefinitionSpecificDependenciesNotNodeUnion() {
        JobDefinitionSeed alternate = seed(D.jobType(), "alternate", A.jobType());
        List<JobDefinitionSeed> seeds = List.of(A, B, C, D, alternate);
        assertThat(resolve(seeds, alternate, handoff(seeds), 64, 20).steps())
                .extracting(step -> step.key().definition())
                .containsExactly(id(A), id(alternate));
    }

    @Test
    void supportsMultipleRootsAtJoin() {
        JobDefinitionSeed root = seed(C.jobType(), "root");
        List<JobDefinitionSeed> seeds = List.of(A, B, root, D);
        assertThat(resolve(seeds, D, handoff(seeds), 64, 20).steps()).hasSize(4);
        assertThat(resolve(seeds, root, handoff(seeds), 0, 1).steps()).hasSize(1);
    }

    @Test
    void requiresExplicitMultipleDefinitionBindingAndAcceptsMany() {
        JobDefinitionSeed b2 = seed(B.jobType(), "b2", A.jobType());
        List<JobDefinitionSeed> seeds = List.of(A, B, b2, C, D);
        Handoff base = handoff(seeds);
        assertThatThrownBy(() -> resolve(seeds, D, base, 64, 20))
                .hasMessageContaining("AMBIGUOUS_DEFINITION");
        Handoff bound =
                new Handoff(
                        Map.of(node(B), List.of(id(b2), id(B))),
                        base.capabilities(),
                        base.mappings());
        assertThat(resolve(seeds, D, bound, 64, 20).steps())
                .extracting(step -> step.key().definition())
                .contains(id(B), id(b2))
                .hasSize(5);
        for (List<JobDefinitionIdentity> invalid :
                List.of(List.<JobDefinitionIdentity>of(), List.of(id(B), id(B)), List.of(id(A)))) {
            Handoff bad =
                    new Handoff(Map.of(node(B), invalid), base.capabilities(), base.mappings());
            assertThatThrownBy(() -> resolve(seeds, D, bad, 64, 20))
                    .hasMessageContaining("INVALID_BINDING");
        }
    }

    @Test
    void failsClosedOnMissingCapabilityMappingOrDateExpansion() {
        Handoff base = handoff(DIAMOND);
        Handoff noCapabilities = new Handoff(Map.of(), Map.of(), base.mappings());
        assertThatThrownBy(() -> resolve(DIAMOND, D, noCapabilities, 64, 20))
                .hasMessageContaining("UNSUPPORTED_SCOPE");
        assertThatThrownBy(
                        () ->
                                resolve(
                                        DIAMOND,
                                        D,
                                        new Handoff(Map.of(), base.capabilities(), Map.of()),
                                        64,
                                        20))
                .hasMessageContaining("UNSUPPORTED_SCOPE");
        Map<ScopeEdge, Scope> dates = new HashMap<>(base.mappings());
        dates.replaceAll(
                (edge, scope) ->
                        new Scope(scope.type(), scope.key(), scope.businessDate().minusDays(1)));
        assertThatThrownBy(
                        () ->
                                resolve(
                                        DIAMOND,
                                        D,
                                        new Handoff(Map.of(), base.capabilities(), dates),
                                        64,
                                        20))
                .hasMessageContaining("Cross-date");
    }

    @Test
    void enforcesClosureBoundsRatherThanReturningTruncatedPath() {
        assertThatThrownBy(() -> resolve(DIAMOND, D, handoff(DIAMOND), 1, 20))
                .hasMessageContaining("PATH_BOUND_EXCEEDED");
        assertThatThrownBy(() -> resolve(DIAMOND, D, handoff(DIAMOND), 64, 3))
                .hasMessageContaining("PATH_BOUND_EXCEEDED");
        assertThatThrownBy(() -> resolve(DIAMOND, D, handoff(DIAMOND), 65, 20))
                .hasMessageContaining("INVALID_BOUNDS");
        assertThatThrownBy(() -> resolve(DIAMOND, D, handoff(DIAMOND), 64, 0))
                .hasMessageContaining("INVALID_BOUNDS");
    }

    @Test
    void reusesTopologyValidationForDuplicatesMissingDefinitionsAndCycles() {
        assertThatThrownBy(
                        () ->
                                new RequiredPathResolver(
                                        JsonSeedDependencyFixture.fromSeeds(List.of(A, A))))
                .hasMessageContaining("Ambiguous");
        assertThatThrownBy(
                        () ->
                                new RequiredPathResolver(
                                        JsonSeedDependencyFixture.fromSeeds(List.of(B))))
                .hasMessageContaining("Unknown");
        JobDefinitionSeed cycle = seed(A.jobType(), "a", B.jobType());
        assertThatThrownBy(
                        () ->
                                new RequiredPathResolver(
                                        JsonSeedDependencyFixture.fromSeeds(List.of(cycle, B))))
                .hasMessageContaining("cycle");
        assertThatThrownBy(
                        () ->
                                resolve(
                                        DIAMOND,
                                        seed(JobType.SYNC_METADATA, "unknown"),
                                        handoff(DIAMOND),
                                        64,
                                        20))
                .hasMessageContaining("Unknown job definition");
    }

    @Test
    void supportsExplicitScopeMappingWithoutImplicitIdentityOrFanout() {
        Scope global = new Scope(ScopeType.GLOBAL, "VCI:ALL", SCOPE.businessDate());
        Map<JobDefinitionIdentity, Set<Scope>> capabilities =
                Map.of(id(A), Set.of(global), id(B), Set.of(SCOPE));
        Handoff handoff =
                new Handoff(Map.of(), capabilities, Map.of(new ScopeEdge(key(B), id(A)), global));
        var result = resolve(List.of(A, B), B, handoff, 1, 2);
        assertThat(result.steps().getFirst().key().scope()).isEqualTo(global);
        assertThat(result.steps().getLast().key().scope()).isEqualTo(SCOPE);
        assertThatThrownBy(() -> new Scope(ScopeType.SYMBOL, " ", SCOPE.businessDate()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void canonicalSignalStrategiesDoNotInheritConfirmedIntradayDependency() {
        List<JobDefinitionSeed> seeds = JobDefinitionConfig.JOB_DEFINITION_SEEDS;
        Handoff base = handoff(seeds);
        Map<TopologyNodeKey, List<JobDefinitionIdentity>> bindings = new HashMap<>();
        for (JobDefinitionSeed seed : seeds) {
            bindings.computeIfAbsent(node(seed), ignored -> new ArrayList<>()).add(id(seed));
        }
        Handoff explicit = new Handoff(bindings, base.capabilities(), base.mappings());
        for (JobDefinitionSeed target : seeds) {
            if (target.jobType() == JobType.SYNC_SIGNALS) {
                var path = resolve(seeds, target, explicit, 64, 1024);
                boolean confirmed =
                        target.config()
                                .get(JobDefinitionConfig.CONFIG_KEY_SIGNAL_STRATEGY)
                                .equals(JobDefinitionConfig.SIGNAL_STRATEGY_CONFIRMED_TREND_EQUALS);
                assertThat(
                                path.steps().stream()
                                        .anyMatch(
                                                step ->
                                                        step.key().definition().jobType()
                                                                == JobType.SYNC_INTRADAY_EOD))
                        .isEqualTo(confirmed);
            }
        }
    }

    @Test
    void longerJoinPathCannotHideDepthOverflowAfterShorterVisit() {
        JobDefinitionSeed chain = seed(B.jobType(), "b", C.jobType());
        JobDefinitionSeed join = seed(D.jobType(), "d", A.jobType(), B.jobType());
        List<JobDefinitionSeed> seeds = List.of(A, C, chain, join);
        assertThatThrownBy(() -> resolve(seeds, join, handoff(seeds), 2, 20))
                .hasMessageContaining("PATH_BOUND_EXCEEDED");
        assertThat(resolve(seeds, join, handoff(seeds), 3, 4).steps()).hasSize(4);
    }

    @Test
    void rejectsInvalidContractScopeCatalogAndTraversalBoundaries() {
        var path = resolve(List.of(A), A, handoff(List.of(A)), 0, 1);
        for (String invalid : List.of(" padded", "x".repeat(257))) {
            assertThatThrownBy(() -> new Scope(ScopeType.SYMBOL, invalid, SCOPE.businessDate()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RequiredPathResolver.RequiredPath(2, key(A), path.steps()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RequiredPathResolver.RequiredPath(1, key(A), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RequiredPathResolver.RequiredPath(
                                        1,
                                        key(A),
                                        java.util.Collections.nCopies(
                                                1025, path.steps().getFirst())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new RequiredPathResolver(
                                        JsonSeedDependencyFixture.fromSeeds(
                                                java.util.Collections.nCopies(1025, A))))
                .hasMessageContaining("PATH_BOUND_EXCEEDED");
        assertThatThrownBy(() -> resolve(List.of(A), A, handoff(List.of(A)), -1, 1))
                .hasMessageContaining("INVALID_BOUNDS");
        assertThatThrownBy(() -> resolve(List.of(A), A, handoff(List.of(A)), 0, 1025))
                .hasMessageContaining("INVALID_BOUNDS");
        Handoff base = handoff(List.of(A));
        Handoff unknown =
                new Handoff(Map.of(node(B), List.of(id(B))), base.capabilities(), base.mappings());
        assertThatThrownBy(() -> resolve(List.of(A), A, unknown, 0, 1))
                .hasMessageContaining("INVALID_BINDING");
    }

    private static RequiredPathResolver.RequiredPath resolve(
            List<JobDefinitionSeed> seeds,
            JobDefinitionSeed target,
            Handoff handoff,
            int depth,
            int steps) {
        return new RequiredPathResolver(JsonSeedDependencyFixture.fromSeeds(seeds))
                .resolveRequiredPath(id(target), SCOPE, handoff, depth, steps);
    }

    // Test fixtures assert support explicitly; these are not runtime capability defaults.
    private static Handoff handoff(List<JobDefinitionSeed> seeds) {
        Map<JobDefinitionIdentity, Set<Scope>> capabilities = new HashMap<>();
        Map<ScopeEdge, Scope> mappings = new HashMap<>();
        for (JobDefinitionSeed seed : seeds) {
            capabilities.put(id(seed), Set.of(SCOPE));
            for (JobDefinitionSeed upstream : seeds) {
                mappings.put(new ScopeEdge(key(seed), id(upstream)), SCOPE);
            }
        }
        return new Handoff(Map.of(), capabilities, mappings);
    }

    private static JobDefinitionSeed seed(JobType type, String cron, JobType... dependencies) {
        List<String> names = new ArrayList<>();
        for (JobType dependency : dependencies) {
            names.add(dependency.name());
        }
        return new JobDefinitionSeed(
                DataSource.ANALYZER,
                List.of(),
                type,
                type.name(),
                cron,
                Map.of(
                        JobDefinitionConfig.CONFIG_KEY_DATA_DEPENDENCIES,
                        Map.of(JobDefinitionConfig.CONFIG_KEY_DEPENDS_ON_JOBS, names)));
    }

    private static JobDefinitionIdentity id(JobDefinitionSeed seed) {
        return JobDefinitionIdentity.from(seed);
    }

    private static TopologyNodeKey node(JobDefinitionSeed seed) {
        return TopologyNodeKey.forJobType(seed.jobType());
    }

    private static StepKey key(JobDefinitionSeed seed) {
        return new StepKey(id(seed), SCOPE);
    }
}
