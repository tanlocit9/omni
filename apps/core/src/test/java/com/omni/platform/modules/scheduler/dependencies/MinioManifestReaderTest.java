package com.omni.platform.modules.scheduler.dependencies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinioManifestReaderTest {

    private static final Path SHARED_EOD_FIXTURE = Path.of(
        "..", "..", "libs", "py-common", "tests", "storage", "fixtures",
        "sample-manifest-eod.json"
    );

    private MinioClient minioClient;
    private MinioManifestReader reader;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        reader = new MinioManifestReader(minioClient, "analytics", new ObjectMapper());
    }

    @Test
    void buildsCanonicalPathWithSortedPartitions() {
        DatasetRef ref = DatasetRef.of(
            "indicators",
            Map.of("timeframe", "1d", "code", "hpg", "exchange", "hose")
        );

        assertThat(reader.buildManifestPath(ref)).isEqualTo(
            "_metadata/datasets/indicators/code=hpg/exchange=hose/timeframe=1d/READY.json"
        );
    }

    @Test
    void buildsCanonicalDefaultPartitionPath() {
        assertThat(reader.buildManifestPath(DatasetRef.of("market-calendar")))
            .isEqualTo("_metadata/datasets/market-calendar/_default/READY.json");
    }

    @Test
    void readsCanonicalSharedFixtureAndIgnoresAdditiveFields() throws Exception {
        stubManifest(Files.readString(SHARED_EOD_FIXTURE));

        Optional<DatasetManifest> result = reader.readManifest(
            DatasetRef.of("eod", Map.of("exchange", "hose"))
        );

        assertThat(result).isPresent();
        DatasetManifest manifest = result.orElseThrow();
        assertThat(manifest.dataset()).isEqualTo("eod");
        assertThat(manifest.objectCount()).isEqualTo(3);
        assertThat(manifest.totalBytes()).isEqualTo(1_048_576L);
        assertThat(manifest.columns()).hasSize(15);
        assertThat(manifest.inputs()).isEmpty();
        assertThat(manifest.isReady()).isTrue();
    }

    @Test
    void classifiesMalformedJsonAsParsingFailure() throws Exception {
        stubManifest("{not-json");

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("Failed to parse manifest JSON")
            .hasMessageContaining("_default/READY.json");
    }

    @Test
    void rejectsUnsupportedEnvelopeVersion() throws Exception {
        stubManifest(validMinimalManifest().replace("\"version\": 1", "\"version\": 2"));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("Unsupported manifest version=2");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() throws Exception {
        stubManifest(validMinimalManifest().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("Unsupported manifest schemaVersion=2");
    }

    @Test
    void rejectsReadyManifestWithoutPhysicalObjects() throws Exception {
        stubManifest(validMinimalManifest().replace("\"objectCount\": 1", "\"objectCount\": 0"));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("READY manifest requires objectCount >= 1");
    }

    @Test
    void rejectsReadyManifestWithInvalidChecksum() throws Exception {
        stubManifest(validMinimalManifest().replace(
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "not-a-checksum"
        ));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("READY manifest requires a valid dataVersion");
    }

    @Test
    void rejectsUnsafePartitionSegment() throws Exception {
        stubManifest(validMinimalManifest().replace(
            "\"partition\": {}",
            "\"partition\": {\"exchange\": \"../hose\"}"
        ));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("partition keys and values must be lowercase path-safe identifiers");
    }

    @Test
    void rejectsTraversalInLogicalPath() throws Exception {
        stubManifest(validMinimalManifest().replace(
            "\"path\": \"eod/data.parquet\"",
            "\"path\": \"../eod/data.parquet\""
        ));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("path must be a relative traversal-safe logical data path");
    }

    @Test
    void rejectsUnsupportedStatus() throws Exception {
        stubManifest(validMinimalManifest().replace("\"status\": \"READY\"", "\"status\": \"UNKNOWN\""));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("unsupported manifest status");
    }

    @Test
    void rejectsBlankGeneratedTimestamp() throws Exception {
        stubManifest(validMinimalManifest().replace(
            "\"generatedAt\": \"2026-08-20T00:00:00Z\"",
            "\"generatedAt\": \"\""
        ));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("generatedAt is required");
    }

    @Test
    void rejectsColumnWithoutType() throws Exception {
        stubManifest(validMinimalManifest().replace("\"type\": \"double\"", "\"type\": \"\""));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("columns require non-empty name and type");
    }

    @Test
    void rejectsLineageWithInvalidDataVersion() throws Exception {
        stubManifest(validMinimalManifest().replace(
            "\"inputs\": []",
            """
            "inputs": [{
              "dataset": "eod",
              "partition": {"exchange": "hose"},
              "dataVersion": "invalid"
            }]
            """
        ));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
            .isInstanceOf(ManifestReadException.class)
            .hasMessageContaining("inputs require a safe dataset, partition, and valid dataVersion");
    }

    private void stubManifest(String json) throws Exception {
        ByteArrayInputStream bytes = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        GetObjectResponse response = mock(GetObjectResponse.class, delegatesTo(bytes));
        when(minioClient.getObject(any())).thenReturn(response);
    }

    private String validMinimalManifest() {
        return """
            {
              "version": 1,
              "dataset": "eod",
              "partition": {},
              "status": "READY",
              "dataVersion": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "path": "eod/data.parquet",
              "objectCount": 1,
              "totalBytes": 128,
              "rowCount": 1,
              "columnCount": 1,
              "columns": [{"name": "close", "type": "double", "nullable": false}],
              "schemaVersion": 1,
              "schemaHash": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "minTimestamp": null,
              "maxTimestamp": null,
              "inputs": [],
              "sourceExecutionId": null,
              "generatedAt": "2026-08-20T00:00:00Z"
            }
            """;
    }
}
