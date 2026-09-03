package com.omni.platform.modules.scheduler.dependencies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.platform.modules.scheduler.dependencies.models.ColumnMetadata;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetInput;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.errors.ErrorResponseException;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Reads logical partitions from the canonical global metadata document. */
@Slf4j
public class MinioManifestReader implements ManifestReader {
    static final String METADATA_PATH = "_metadata/metadata.json";

    private final MinioClient minioClient;
    private final String bucketName;
    private final ObjectMapper objectMapper;

    public MinioManifestReader(MinioClient minioClient, String bucketName, ObjectMapper objectMapper) {
        this.minioClient = minioClient;
        this.bucketName = bucketName;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<DatasetManifest> readManifest(DatasetRef datasetRef) throws ManifestReadException {
        try (InputStream stream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(bucketName).object(METADATA_PATH).build())) {
            JsonNode root = objectMapper.readTree(stream);
            validateRoot(root);
            for (JsonNode dataset : root.path("datasets")) {
                if (!datasetRef.getDataset().equals(dataset.path("name").asText())) {
                    continue;
                }
                for (JsonNode partition : dataset.path("partitions")) {
                    Map<String, String> values = stringMap(partition.path("values"));
                    if (values.equals(datasetRef.getPartition())) {
                        DatasetManifest manifest = mapPartition(datasetRef, partition);
                        validateManifest(manifest);
                        return Optional.of(manifest);
                    }
                }
                return Optional.empty();
            }
            return Optional.empty();
        } catch (ManifestReadException exception) {
            throw exception;
        } catch (ErrorResponseException exception) {
            if ("NoSuchKey".equals(exception.errorResponse().code())) {
                return Optional.empty();
            }
            throw ManifestReadException.ioError(METADATA_PATH, exception);
        } catch (Exception exception) {
            throw ManifestReadException.ioError(METADATA_PATH, exception);
        }
    }

    private DatasetManifest mapPartition(DatasetRef ref, JsonNode node) {
        List<ColumnMetadata> columns = new ArrayList<>();
        for (JsonNode column : node.path("columns")) {
            columns.add(new ColumnMetadata(
                    column.path("name").asText(),
                    column.path("type").asText(),
                    column.path("nullable").asBoolean()));
        }
        List<DatasetInput> inputs = new ArrayList<>();
        for (JsonNode input : node.path("inputs")) {
            inputs.add(new DatasetInput(
                    input.path("dataset").asText(),
                    stringMap(input.path("partition")),
                    input.path("dataVersion").asText()));
        }
        return new DatasetManifest(
                1,
                ref.getDataset(),
                ref.getPartition(),
                node.path("status").asText(),
                node.path("dataVersion").asText(),
                node.path("path").asText(),
                node.path("objectCount").asInt(),
                node.path("totalBytes").asLong(),
                node.path("rowCount").asLong(),
                node.path("columnCount").asInt(),
                columns,
                node.path("schemaVersion").asInt(),
                node.path("schemaHash").asText(),
                nullableText(node, "minTimestamp"),
                nullableText(node, "maxTimestamp"),
                inputs,
                nullableText(node, "sourceExecutionId"),
                node.path("generatedAt").asText());
    }

    private static Map<String, String> stringMap(JsonNode node) {
        if (!node.isObject()) {
            throw ManifestReadException.invalidContract(METADATA_PATH, "partition values must be an object");
        }
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isValueNode()) {
                throw ManifestReadException.invalidContract(METADATA_PATH, "partition values must be scalar");
            }
            values.put(field.getKey(), field.getValue().asText());
        }
        return values;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void validateRoot(JsonNode root) {
        if (!root.isObject() || root.path("version").asInt(-1) != 1 || !root.path("datasets").isArray()) {
            throw ManifestReadException.invalidContract(METADATA_PATH, "invalid global metadata document");
        }
    }

    private static void validateManifest(DatasetManifest manifest) {
        if (!manifest.isReady() || manifest.objectCount() < 1
                || !isSha256(manifest.dataVersion()) || !isSha256(manifest.schemaHash())
                || manifest.columns() == null || manifest.columnCount() != manifest.columns().size()
                || manifest.inputs() == null || manifest.path() == null || manifest.path().isBlank()
                || manifest.path().startsWith("/") || manifest.path().contains("../")) {
            throw ManifestReadException.invalidContract(METADATA_PATH, "invalid global partition metadata");
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
}
