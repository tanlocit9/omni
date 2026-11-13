package com.omni.platform.modules.scheduler.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class WorkerCompletionEventTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private tools.jackson.databind.JsonNode fixture() throws Exception {
        var path = Path.of("../../libs/contracts/fixtures/worker-completion-v1.json");
        if (!Files.exists(path))
            path = Path.of("libs/contracts/fixtures/worker-completion-v1.json");
        return json.readTree(Files.readString(path));
    }

    @Test
    void pairedFixturePreservesTerminalResultAndExactRegistration() throws Exception {
        var raw = fixture();
        WorkerCompletionEvent.validateWire(raw);
        var event = json.treeToValue(raw, WorkerCompletionEvent.class);
        assertThat(
                        event.registration()
                                .registrations()
                                .getFirst()
                                .outputs()
                                .getFirst()
                                .storageVersionId())
                .isEqualTo("version-0001");
        assertThat(event.status().status()).isEqualTo("SUCCESS");
        assertThat(json.readTree(json.writeValueAsString(event))).isEqualTo(raw);
        ((tools.jackson.databind.node.ObjectNode) raw.get("status")).put("status", "ERROR");
        ((tools.jackson.databind.node.ObjectNode) raw).putNull("registration");
        assertThat(json.treeToValue(raw, WorkerCompletionEvent.class).registration()).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "schema",
                "kind",
                "running",
                "missing",
                "failed",
                "uuid",
                "counter",
                "clock",
                "null-status",
                "unknown",
                "null-event",
                "null-attempt",
                "null-work-type",
                "null-work-key",
                "blank-work-key",
                "null-start",
                "null-end",
                "negative-duration",
                "null-counter",
                "negative-counter",
                "event-mismatch",
                "attempt-mismatch",
                "execution-mismatch",
                "type-mismatch",
                "key-mismatch",
                "schema-overflow",
                "parent-uuid"
            })
    void invalidTerminalEnvelopeIsRejected(String scenario) throws Exception {
        var raw = (tools.jackson.databind.node.ObjectNode) fixture();
        var status = (tools.jackson.databind.node.ObjectNode) raw.get("status");
        switch (scenario) {
            case "schema" -> raw.put("schemaVersion", true);
            case "kind" -> raw.put("kind", "OTHER");
            case "running" -> status.put("status", "RUNNING");
            case "missing" -> raw.putNull("registration");
            case "failed" -> status.put("status", "ERROR");
            case "uuid" -> raw.put("eventId", "1-1-1-1-1");
            case "counter" -> status.put("recordsProcessed", 1.5);
            case "clock" -> status.put("finishedAt", "2035-01-01T00:00:00Z");
            case "null-status" -> raw.putNull("status");
            case "null-event" -> raw.putNull("eventId");
            case "null-attempt" -> raw.putNull("atomicAttemptId");
            case "null-work-type" -> status.putNull("workType");
            case "null-work-key" -> status.putNull("workKey");
            case "blank-work-key" -> status.put("workKey", " ");
            case "null-start" -> status.putNull("startedAt");
            case "null-end" -> status.putNull("finishedAt");
            case "negative-duration" -> status.put("durationMs", -1);
            case "null-counter" -> status.putNull("recordsProcessed");
            case "negative-counter" -> status.put("recordsProcessed", -1);
            case "event-mismatch" -> raw.put("eventId", "00000000-0000-0000-0000-000000000099");
            case "attempt-mismatch" ->
                    raw.put("atomicAttemptId", "00000000-0000-0000-0000-000000000099");
            case "execution-mismatch" ->
                    status.put("executionId", "00000000-0000-0000-0000-000000000099");
            case "type-mismatch" -> status.put("workType", "EXCHANGE");
            case "key-mismatch" -> status.put("workKey", "OTHER");
            case "schema-overflow" -> raw.put("schemaVersion", 4294967297L);
            case "parent-uuid" -> status.put("parentExecutionId", "1-1-1-1-1");
            default -> raw.put("extra", true);
        }
        assertThatThrownBy(
                        () -> {
                            WorkerCompletionEvent.validateWire(raw);
                            json.readerFor(WorkerCompletionEvent.class)
                                    .with(
                                            tools.jackson.databind.DeserializationFeature
                                                    .FAIL_ON_UNKNOWN_PROPERTIES)
                                    .readValue(raw.toString());
                        })
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void missingOrOversizedRecordNeverAccessesPersistence() {
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var store =
                new WorkerCompletionStore(
                        jdbc,
                        json,
                        org.mockito.Mockito.mock(OutputRegistrationStore.class),
                        org.mockito.Mockito.mock(
                                com.omni.platform.modules.scheduler.services.JobService.class),
                        org.mockito.Mockito.mock(OutputCompletionGate.class));
        assertThatThrownBy(() -> store.accept(null)).hasMessageContaining("256 KiB");
        assertThatThrownBy(() -> store.accept("x".repeat(262145))).hasMessageContaining("256 KiB");
        org.mockito.Mockito.verifyNoInteractions(jdbc);
    }

    @Test
    void consumerRoutesNewCompletionAndQueuedLegacyRegistrationSeparately() throws Exception {
        var registrations = org.mockito.Mockito.mock(OutputRegistrationStore.class);
        var completions = org.mockito.Mockito.mock(WorkerCompletionStore.class);
        var consumer = new OutputRegistrationConsumer(registrations, completions, json);
        var raw = fixture();
        consumer.accept(raw.toString());
        consumer.accept(raw.get("registration").toString());
        org.mockito.Mockito.verify(completions).accept(raw.toString());
        org.mockito.Mockito.verify(registrations).accept(raw.get("registration").toString());
        org.mockito.Mockito.clearInvocations(registrations, completions);
        assertThatThrownBy(() -> consumer.accept(null)).hasMessageContaining("256 KiB");
        assertThatThrownBy(() -> consumer.accept("x".repeat(262145)))
                .hasMessageContaining("256 KiB");
        org.mockito.Mockito.verifyNoInteractions(registrations, completions);
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "[]", "\"invalid\"", "\"2147483648\"", "\" 1\""})
    void invalidMetadataCounterCannotSilentlyLoseTerminalStatus(String value) throws Exception {
        var raw = fixture();
        ((tools.jackson.databind.node.ObjectNode) raw.get("status").get("metaJson"))
                .set("recordsInserted", json.readTree(value));
        assertThatThrownBy(() -> json.treeToValue(raw, WorkerCompletionEvent.class))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "1", "\"1\"", "1.0"})
    void supportedLegacyCountersRetainTerminalStatus(String value) throws Exception {
        var raw = fixture();
        ((tools.jackson.databind.node.ObjectNode) raw.get("status").get("metaJson"))
                .set("recordsProcessed", json.readTree(value));
        assertThat(json.treeToValue(raw, WorkerCompletionEvent.class).status().status())
                .isEqualTo("SUCCESS");
    }
}
