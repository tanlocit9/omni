package com.omni.platform.modules.scheduler.dependencies;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MinioManifestReaderTest {
    private MinioClient minioClient;
    private MinioManifestReader reader;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        reader = new MinioManifestReader(minioClient, "analytics", new ObjectMapper());
    }

    @Test
    void resolvesExactLogicalPartitionFromGlobalDocument() throws Exception {
        stubMetadata(validDocument());

        Optional<DatasetManifest> result = reader.readManifest(
                DatasetRef.of("eod", Map.of("exchange", "hose", "code", "hpg")));

        assertThat(result).isPresent();
        DatasetManifest manifest = result.orElseThrow();
        assertThat(manifest.dataset()).isEqualTo("eod");
        assertThat(manifest.path()).isEqualTo("eod/hose/hpg.parquet");
        assertThat(manifest.rowCount()).isEqualTo(1);
        assertThat(manifest.isReady()).isTrue();
    }

    @Test
    void returnsEmptyForMissingLogicalPartition() throws Exception {
        stubMetadata(validDocument());

        assertThat(reader.readManifest(
                DatasetRef.of("eod", Map.of("exchange", "hose", "code", "vnm"))))
                .isEmpty();
    }

    @Test
    void rejectsMalformedGlobalDocument() throws Exception {
        stubMetadata("{not-json");

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
                .isInstanceOf(ManifestReadException.class)
                .hasMessageContaining("_metadata/metadata.json");
    }

    @Test
    void rejectsUnsupportedGlobalVersion() throws Exception {
        stubMetadata(validDocument().replace("\"version\": 1", "\"version\": 2"));

        assertThatThrownBy(() -> reader.readManifest(DatasetRef.of("eod")))
                .isInstanceOf(ManifestReadException.class)
                .hasMessageContaining("invalid global metadata document");
    }

    @Test
    void rejectsUnsafeInternalPath() throws Exception {
        stubMetadata(validDocument().replace("eod/hose/hpg.parquet", "../secret.parquet"));

        assertThatThrownBy(() -> reader.readManifest(
                DatasetRef.of("eod", Map.of("exchange", "hose", "code", "hpg"))))
                .isInstanceOf(ManifestReadException.class)
                .hasMessageContaining("invalid global partition metadata");
    }

    private void stubMetadata(String json) throws Exception {
        ByteArrayInputStream bytes = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        GetObjectResponse response = mock(GetObjectResponse.class, delegatesTo(bytes));
        when(minioClient.getObject(any())).thenReturn(response);
    }

    private String validDocument() {
        return """
                {
                  "version": 1,
                  "generatedAt": "2026-09-01T13:00:00Z",
                  "sourceExecutionId": null,
                  "datasets": [{
                    "name": "eod",
                    "label": "End-of-Day Prices",
                    "dataPrefix": "eod/",
                    "partitionKeys": [],
                    "partitions": [{
                      "values": {"exchange": "hose", "code": "hpg"},
                      "status": "READY",
                      "path": "eod/hose/hpg.parquet",
                      "dataVersion": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "schemaVersion": 1,
                      "schemaHash": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                      "objectCount": 1,
                      "totalBytes": 128,
                      "rowCount": 1,
                      "columnCount": 1,
                      "columns": [{"name": "close", "type": "DOUBLE", "nullable": false}],
                      "minTimestamp": null,
                      "maxTimestamp": null,
                      "inputs": [],
                      "generatedAt": "2026-09-01T13:00:00Z",
                      "sourceExecutionId": null
                    }]
                  }]
                }
                """;
    }
}
