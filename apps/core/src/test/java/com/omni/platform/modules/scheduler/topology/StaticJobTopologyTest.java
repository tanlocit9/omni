package com.omni.platform.modules.scheduler.topology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.DefinitionMapping;
import com.omni.platform.modules.scheduler.topology.StaticTopologyDeclaration.Edge;

class StaticJobTopologyTest {

    private static final TopologyNodeKey ROOT = new TopologyNodeKey("root");
    private static final TopologyNodeKey MIDDLE = new TopologyNodeKey("middle");
    private static final TopologyNodeKey LEAF = new TopologyNodeKey("leaf");

    @Test
    void buildsCanonicalTopologyFromEverySeedAndMapsSharedJobTypes() {
        StaticJobTopology topology = new StaticJobTopologyProvider().topology();

        assertThat(topology.nodes()).hasSize(JobType.values().length);
        assertThat(topology.roots()).contains(
                TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS),
                TopologyNodeKey.forJobType(JobType.SYNC_METADATA));
        assertThat(topology.directUpstream(TopologyNodeKey.forJobType(JobType.SYNC_INDICATORS)))
                .containsExactly(TopologyNodeKey.forJobType(JobType.SYNC_STOCK_PRICE));
        assertThat(topology.descendants(TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS), 2))
                .contains(
                        TopologyNodeKey.forJobType(JobType.SYNC_STOCK_PRICE),
                        TopologyNodeKey.forJobType(JobType.SYNC_INTRADAY_EOD));

        JobDefinitionConfig.JOB_DEFINITION_SEEDS.stream()
                .filter(seed -> seed.jobType() == JobType.SYNC_STOCK_PRICE)
                .forEach(seed -> assertThat(topology.nodeFor(JobDefinitionIdentity.from(seed)))
                        .isEqualTo(TopologyNodeKey.forJobType(JobType.SYNC_STOCK_PRICE)));
    }

    @Test
    void providesDeterministicBidirectionalTraversalOrderAndMermaidOutput() {
        StaticJobTopology topology = chain();

        assertThat(topology.topologicalOrder()).containsExactly(ROOT, MIDDLE, LEAF);
        assertThat(topology.roots()).containsExactly(ROOT);
        assertThat(topology.directDownstream(ROOT)).containsExactly(MIDDLE);
        assertThat(topology.directUpstream(LEAF)).containsExactly(MIDDLE);
        assertThat(topology.descendants(ROOT, 1)).containsExactly(MIDDLE);
        assertThat(topology.descendants(ROOT, 2)).containsExactly(MIDDLE, LEAF);
        assertThat(topology.ancestors(LEAF, 2)).containsExactly(MIDDLE, ROOT);
        assertThat(topology.toMermaid()).isEqualTo("""
                flowchart TD
                    n_root[\"root\"]
                    n_middle[\"middle\"]
                    n_leaf[\"leaf\"]
                    n_root --> n_middle
                    n_middle --> n_leaf
                """);
    }

    @Test
    void rejectsInvalidKeysUnknownNodesDuplicatesSelfEdgesCyclesAndBounds() {
        assertThatThrownBy(() -> new TopologyNodeKey("NOT STABLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid topology node key");
        assertThatThrownBy(() -> StaticJobTopology.create(declaration(
                List.of(ROOT),
                List.of(new Edge(ROOT, LEAF)),
                List.of(mapping(JobType.SYNC_SYMBOLS, ROOT)))))
                .hasMessageContaining("Unknown edge downstream");
        assertThatThrownBy(() -> StaticJobTopology.create(declaration(
                List.of(ROOT, ROOT), List.of(), List.of(mapping(JobType.SYNC_SYMBOLS, ROOT)))))
                .hasMessageContaining("Duplicate topology node");
        assertThatThrownBy(() -> StaticJobTopology.create(declaration(
                List.of(ROOT), List.of(new Edge(ROOT, ROOT)), List.of(mapping(JobType.SYNC_SYMBOLS, ROOT)))))
                .hasMessageContaining("Self edge");
        assertThatThrownBy(() -> StaticJobTopology.create(declaration(
                List.of(ROOT, LEAF),
                List.of(new Edge(ROOT, LEAF), new Edge(LEAF, ROOT)),
                List.of(mapping(JobType.SYNC_SYMBOLS, ROOT), mapping(JobType.SYNC_METADATA, LEAF)))))
                .hasMessageContaining("cycle");
        assertThatThrownBy(() -> StaticJobTopology.create(chainDeclaration(), 2, 2))
                .hasMessageContaining("node bound");
        assertThatThrownBy(() -> StaticJobTopology.create(chainDeclaration(), 3, 1))
                .hasMessageContaining("edge bound");
    }

    @Test
    void rejectsUnmappedNodesAmbiguousDefinitionsUnknownLookupsAndDepthOverflow() {
        assertThatThrownBy(() -> StaticJobTopology.create(declaration(
                List.of(ROOT, LEAF),
                List.of(new Edge(ROOT, LEAF)),
                List.of(mapping(JobType.SYNC_SYMBOLS, ROOT)))))
                .hasMessageContaining("without definition mappings")
                .hasMessageContaining("leaf");

        DefinitionMapping duplicate = mapping(JobType.SYNC_SYMBOLS, ROOT);
        assertThatThrownBy(() -> StaticJobTopology.create(declaration(
                List.of(ROOT), List.of(), List.of(duplicate, duplicate))))
                .hasMessageContaining("Ambiguous definition mapping");

        StaticJobTopology topology = chain();
        assertThatThrownBy(() -> topology.directDownstream(new TopologyNodeKey("unknown")))
                .hasMessageContaining("Unknown topology node");
        assertThatThrownBy(() -> topology.nodeFor(identity(JobType.SYNC_SIGNALS)))
                .hasMessageContaining("Unknown job definition mapping");
        assertThatThrownBy(() -> topology.descendants(ROOT,
                StaticJobTopology.DEFAULT_MAX_TRAVERSAL_DEPTH + 1))
                .hasMessageContaining("Traversal depth");
    }

    @Test
    void rejectsMalformedSeedDependencyMetadata() {
        assertThatThrownBy(() -> StaticJobTopologyProvider.declare(List.of(seed(Map.of()))))
                .hasMessageContaining("Missing dataDependencies");
        assertThatThrownBy(() -> StaticJobTopologyProvider.declare(List.of(seed(Map.of(
                JobDefinitionConfig.CONFIG_KEY_DATA_DEPENDENCIES, Map.of())))))
                .hasMessageContaining("Missing dependsOnJobs");
        assertThatThrownBy(() -> StaticJobTopologyProvider.declare(List.of(seed(Map.of(
                JobDefinitionConfig.CONFIG_KEY_DATA_DEPENDENCIES,
                Map.of(JobDefinitionConfig.CONFIG_KEY_DEPENDS_ON_JOBS, List.of(42)))))))
                .hasMessageContaining("Invalid job dependency");
        assertThatThrownBy(() -> StaticJobTopologyProvider.declare(List.of(seed(Map.of(
                JobDefinitionConfig.CONFIG_KEY_DATA_DEPENDENCIES,
                Map.of(JobDefinitionConfig.CONFIG_KEY_DEPENDS_ON_JOBS, List.of("UNKNOWN_JOB")))))))
                .hasMessageContaining("Unknown job dependency");
    }

    private JobDefinitionSeed seed(Map<String, Object> config) {
        return new JobDefinitionSeed(
                DataSource.ANALYZER,
                List.of(),
                JobType.SYNC_SIGNALS,
                "Test signals",
                "0 0 0 * * *",
                config);
    }

    private StaticJobTopology chain() {
        return StaticJobTopology.create(chainDeclaration());
    }

    private StaticTopologyDeclaration chainDeclaration() {
        return declaration(
                List.of(LEAF, ROOT, MIDDLE),
                List.of(new Edge(MIDDLE, LEAF), new Edge(ROOT, MIDDLE)),
                List.of(
                        mapping(JobType.SYNC_SYMBOLS, ROOT),
                        mapping(JobType.SYNC_STOCK_PRICE, MIDDLE),
                        mapping(JobType.SYNC_INDICATORS, LEAF)));
    }

    private StaticTopologyDeclaration declaration(
            List<TopologyNodeKey> nodes,
            List<Edge> edges,
            List<DefinitionMapping> mappings) {
        return new StaticTopologyDeclaration(nodes, edges, mappings);
    }

    private DefinitionMapping mapping(JobType jobType, TopologyNodeKey node) {
        return new DefinitionMapping(identity(jobType), node);
    }

    private JobDefinitionIdentity identity(JobType jobType) {
        return new JobDefinitionIdentity(DataSource.ANALYZER, jobType, "0 0 0 * * *");
    }
}
