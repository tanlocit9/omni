package com.omni.platform.modules.scheduler.metadata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class OutputReadinessProviderTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Environment environment = mock(Environment.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private OutputReadinessProvider provider;

    @BeforeEach
    void configure() {
        when(environment.getProperty(anyString(), anyString()))
                .thenAnswer(call -> call.getArgument(1));
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);
        provider = new OutputReadinessProvider(jdbc, json, environment);
    }

    private JsonNode fixture() throws Exception {
        Path path = Path.of("../../libs/contracts/fixtures/output-readiness-page-v1.json");
        if (!Files.exists(path))
            path = Path.of("libs/contracts/fixtures/output-readiness-page-v1.json");
        return json.readTree(Files.readString(path));
    }

    private ObjectNode output() throws Exception {
        var item = fixture().get("items").get(0);
        var manifest = item.get("manifest");
        var output = json.createObjectNode();
        output.set("dataset", item.get("dataset"));
        output.set("partition", manifest.get("values"));
        for (String field :
                List.of(
                        "dataVersion",
                        "schemaVersion",
                        "schemaHash",
                        "coverageDates",
                        "totalBytes",
                        "rowCount",
                        "columns",
                        "inputs")) output.set(field, manifest.get(field));
        return output;
    }

    private Map<String, Object> row(ObjectNode output) {
        var row = new LinkedHashMap<String, Object>();
        row.put("payload", json.writeValueAsString(output));
        row.put("execution_id", null); // Historical baseline must not acquire invented provenance.
        row.put("promoted_at", Timestamp.from(Instant.parse("2026-11-02T12:00:00Z")));
        return row;
    }

    @Test
    void sharedPageFixtureFreezesPathsDatesSchemaAndNullProvenance() throws Exception {
        when(jdbc.queryForList(anyString(), anyInt(), anyInt())).thenReturn(List.of(row(output())));
        JsonNode actual = json.readTree(json.writeValueAsString(provider.page(0, 200, null)));
        assertThat(actual).isEqualTo(fixture());
    }

    @Test
    void pagingChecksRevisionAndBoundsBeforeReadingRows() {
        assertThatThrownBy(() -> provider.page(-1, 200, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.page(100001, 200, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.page(0, 201, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.page(0, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.page(0, 200, 6L))
                .isInstanceOf(ResponseStatusException.class);
        verify(jdbc, never()).queryForList(anyString(), anyInt(), anyInt());
    }

    @Test
    void emptyPageReturnsAStationaryTerminalCursor() {
        when(jdbc.queryForList(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        assertThat(provider.page(20, 10, 7L))
                .containsEntry("nextOffset", 20)
                .containsEntry("hasMore", false)
                .containsEntry("items", List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void largeValidOutputsStopAtThePythonPageBudgetWithoutSkippingRows() throws Exception {
        var output = output();
        var inputs = json.createArrayNode();
        for (int i = 0; i < 128; i++) {
            var input = json.createObjectNode();
            input.put("dataset", "eod");
            input.set("partition", json.createObjectNode().put("code", "x".repeat(127)));
            input.put("dataVersion", "sha256:" + "a".repeat(64));
            inputs.add(input);
        }
        output.set("inputs", inputs);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 201; i++) rows.add(row(output));
        when(jdbc.queryForList(anyString(), anyInt(), anyInt())).thenReturn(rows);
        var page = provider.page(50, 200, 7L);
        var items = (List<Map<String, Object>>) page.get("items");
        assertThat(items.size()).isBetween(1, 199);
        assertThat(page)
                .containsEntry("nextOffset", 50 + items.size())
                .containsEntry("hasMore", true);
        assertThat(json.writeValueAsBytes(page).length)
                .isLessThanOrEqualTo(OutputReadinessProvider.MAX_PAGE_BYTES);
    }

    @Test
    void pathConfigurationCannotEscapeSnapshotStorage() throws Exception {
        when(environment.getProperty(eq("stock-data.paths.output-snapshots.base"), anyString()))
                .thenReturn("../");
        when(jdbc.queryForList(anyString(), anyInt(), anyInt())).thenReturn(List.of(row(output())));
        assertThatThrownBy(() -> provider.page(0, 200, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotPatternCannotDropVersionOrPartitionIdentity() throws Exception {
        when(environment.getProperty(eq("stock-data.paths.output-snapshots.pattern"), anyString()))
                .thenReturn("{dataset}.parquet");
        when(jdbc.queryForList(anyString(), anyInt(), anyInt())).thenReturn(List.of(row(output())));
        assertThatThrownBy(() -> provider.page(0, 200, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("partition_hash");
    }

    @Test
    void registeredProducerProvenanceAndDerivedDatasetRoutesRemainIntact() throws Exception {
        for (String dataset : List.of("indicators", "signals")) {
            var output = output();
            output.put("dataset", dataset);
            var partition = (ObjectNode) output.get("partition");
            partition.put("timeframe", "1d");
            if (dataset.equals("indicators")) partition.put("source", "ad_close");
            else {
                partition.remove("code");
                partition.put("strategy", "trend_momentum_v1");
            }
            var row = row(output);
            row.put(
                    "execution_id",
                    java.util.UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
            when(jdbc.queryForList(anyString(), anyInt(), anyInt())).thenReturn(List.of(row));
            JsonNode page = json.valueToTree(provider.page(0, 200, null));
            var manifest = page.get("items").get(0).get("manifest");
            assertThat(manifest.get("sourceExecutionId").asText())
                    .isEqualTo(row.get("execution_id").toString());
            assertThat(manifest.get("canonicalPath").asText())
                    .isEqualTo(
                            dataset.equals("signals")
                                    ? "signals/trend_momentum_v1/1d/upcom.parquet"
                                    : "indicators/ad_close/1d/upcom/hd6.parquet");
            assertThat(manifest.get("minTimestamp").isNull()).isEqualTo(dataset.equals("signals"));
        }
    }
}
