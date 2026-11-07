package com.omni.platform.modules.scheduler.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

class OutputRegistrationEventTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void signalModelsAndLegacyCoordinatesRoundTripWithoutColliding() throws Exception {
        Path path =
                Path.of("../../libs/contracts/fixtures/output-registration-signal-models-v1.json");
        if (!Files.exists(path))
            path = Path.of("libs/contracts/fixtures/output-registration-signal-models-v1.json");
        String fixture = Files.readString(path);
        var raw = json.readTree(fixture);
        OutputRegistrationEvent.validateWire(raw);
        var event = json.treeToValue(raw, OutputRegistrationEvent.class);
        assertThat(event.registrations().getFirst().outputs().getFirst().workScope().rowKeys())
                .hasSize(3);
        assertThat(json.readTree(json.writeValueAsString(event))).isEqualTo(raw);
        for (String kind : java.util.List.of("duplicate", "blank", "unknown-coordinate")) {
            var invalid = json.readTree(fixture);
            var keys =
                    (tools.jackson.databind.node.ArrayNode)
                            invalid.get("registrations")
                                    .get(0)
                                    .get("outputs")
                                    .get(0)
                                    .get("workScope")
                                    .get("rowKeys");
            var key = (tools.jackson.databind.node.ObjectNode) keys.get(1);
            switch (kind) {
                case "duplicate" -> keys.set(2, key.deepCopy());
                case "blank" -> key.put("model_version", " ");
                case "unknown-coordinate" -> key.set("model", key.remove("model_version"));
            }
            assertThatThrownBy(
                            () -> {
                                OutputRegistrationEvent.validateWire(invalid);
                                json.treeToValue(invalid, OutputRegistrationEvent.class);
                            })
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"true", "1.0", "1.5", "\"1\"", "null", "18446744073709551616"})
    void coercedStatisticsFailBeforeDatabaseAccess(String value) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = new OutputRegistrationStore(jdbc, json);
        for (String field : java.util.List.of("rowCount", "totalBytes", "schemaVersion")) {
            var root = (tools.jackson.databind.node.ObjectNode) json.readTree(fixture());
            var output =
                    (tools.jackson.databind.node.ObjectNode)
                            root.get("registrations").get(0).get("outputs").get(0);
            output.set(field, json.readTree(value));
            assertThatThrownBy(() -> store.accept(json.writeValueAsString(root)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "valid", "no-alias", "event-conflict", "member-conflict", "unknown-attempt", "work",
            "persisted-alias", "alias", "expansion", "registration-id", "event-id", "attempt-id"})
    void immutableRegistrationMustMatchTheFrozenServerIdentities(String state) throws Exception {
        String payload = fixture();
        var event = json.readValue(payload, OutputRegistrationEvent.class);
        var member = event.registrations().getFirst();
        String different = "work:" + "0".repeat(64);
        var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("workIdentity", state.equals("persisted-alias") ? different : member.atomicWorkId());
        if (!state.equals("no-alias"))
            metadata.put("atomicWorkId", state.equals("alias") ? different : member.atomicWorkId());
        metadata.put("workExpansion", java.util.Map.of("atomicWorkId",
                state.equals("expansion") ? different : member.atomicWorkId()));
        metadata.put("outputCompletion", java.util.Map.of(
                "registrationId", state.equals("registration-id") ? "wrong" : member.registrationId().toString(),
                "eventId", state.equals("event-id") ? "wrong" : event.eventId().toString(),
                "atomicAttemptId", state.equals("attempt-id") ? "wrong" : member.atomicAttemptId().toString()));
        JdbcTemplate jdbc = mock(JdbcTemplate.class, call -> {
            String sql = call.getArguments().length > 0 && call.getArgument(0) instanceof String text ? text : "";
            if (call.getMethod().getName().equals("queryForList") && sql.contains("SELECT a.work_id"))
                return state.equals("unknown-attempt") ? java.util.List.of() : java.util.List.of(java.util.Map.of(
                        "work_id", state.equals("work") ? different : member.atomicWorkId(),
                        "metadata", json.writeValueAsString(metadata)));
            if (call.getMethod().getName().equals("queryForObject"))
                return !(state.equals("event-conflict") && sql.contains("output_registration_events"))
                        && !(state.equals("member-conflict") && sql.contains("output_registrations"));
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        var completion = mock(OutputCompletionGate.class);
        var events = mock(org.springframework.context.ApplicationEventPublisher.class);
        var store = new OutputRegistrationStore(jdbc, json);
        store.configureCompletion(completion, events);
        if (java.util.List.of("valid", "no-alias").contains(state)) {
            assertThatCode(() -> store.accept(payload)).doesNotThrowAnyException();
            org.mockito.Mockito.verify(events).publishEvent(new OutputRegistrationAccepted(member.executionId()));
        } else {
            assertThatThrownBy(() -> store.accept(payload)).isInstanceOf(IllegalArgumentException.class);
            org.mockito.Mockito.verifyNoInteractions(events);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "schema", "members-missing", "members-type", "outputs-missing", "outputs-type",
            "coverage-missing", "coverage-type", "columns-missing", "columns-type", "nullable-missing",
            "nullable-type", "scope-type", "scope-id-missing", "scope-id-type", "keys-missing",
            "keys-type", "key-shape", "key-value", "dataset-null", "dataset-blank", "version-null",
            "version-invalid", "column-name-null", "column-name-long", "inputs-null", "columns-empty"})
    void malformedWireAndSemanticFieldsNeverReachPersistence(String state) throws Exception {
        var root = (tools.jackson.databind.node.ObjectNode) json.readTree(fixture());
        var member = (tools.jackson.databind.node.ObjectNode) root.get("registrations").get(0);
        var output = (tools.jackson.databind.node.ObjectNode) member.get("outputs").get(0);
        var column = (tools.jackson.databind.node.ObjectNode) output.get("columns").get(0);
        switch (state) {
            case "schema" -> root.put("schemaVersion", 2);
            case "members-missing" -> root.remove("registrations");
            case "members-type" -> root.put("registrations", false);
            case "outputs-missing" -> member.remove("outputs");
            case "outputs-type" -> member.put("outputs", false);
            case "coverage-missing" -> output.remove("coverageDates");
            case "coverage-type" -> output.put("coverageDates", false);
            case "columns-missing" -> output.remove("columns");
            case "columns-type" -> output.put("columns", false);
            case "nullable-missing" -> column.remove("nullable");
            case "nullable-type" -> column.put("nullable", "false");
            case "scope-type" -> output.set("workScope", json.readTree("[]"));
            case "scope-id-missing" -> output.set("workScope", json.readTree("{\"rowKeys\":[]}"));
            case "scope-id-type" -> output.set("workScope", json.readTree("{\"atomicWorkId\":1,\"rowKeys\":[]}"));
            case "keys-missing", "keys-type", "key-shape", "key-value" -> {
                String keys = switch (state) {
                    case "keys-type" -> ",\"rowKeys\":{}";
                    case "key-shape" -> ",\"rowKeys\":[[]]";
                    case "key-value" -> ",\"rowKeys\":[{\"date\":1}]";
                    default -> "";
                };
                output.set("workScope", json.readTree("{\"atomicWorkId\":\"work:" + "a".repeat(64) + "\"" + keys + "}"));
            }
            case "dataset-null" -> output.putNull("dataset");
            case "dataset-blank" -> output.put("dataset", "");
            case "version-null" -> output.putNull("dataVersion");
            case "version-invalid" -> output.put("dataVersion", "bad-version");
            case "column-name-null" -> column.putNull("name");
            case "column-name-long" -> column.put("name", "x".repeat(129));
            case "inputs-null" -> output.putNull("inputs");
            case "columns-empty" -> output.set("columns", json.readTree("[]"));
        }
        var jdbc = mock(JdbcTemplate.class);
        var store = new OutputRegistrationStore(jdbc, json);
        String malformed = json.writeValueAsString(root);
        assertThatThrownBy(() -> store.accept(malformed)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(jdbc);
    }

    private String fixture() throws Exception {
        Path path = Path.of("../../libs/contracts/fixtures/output-registration-v1.json");
        if (!Files.exists(path))
            path = Path.of("libs/contracts/fixtures/output-registration-v1.json");
        return Files.readString(path);
    }

    @Test
    void sharedFixtureRoundTripsWithExactWorkIdentityAndDateCoverage() throws Exception {
        var event = json.readValue(fixture(), OutputRegistrationEvent.class);
        assertThat(event.registrations()).hasSize(1);
        assertThat(event.registrations().getFirst().outputs().getFirst().coverageDates())
                .containsExactly(java.time.LocalDate.of(2035, 1, 2));
        assertThat(json.readTree(json.writeValueAsString(event)))
                .isEqualTo(json.readTree(fixture()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"1-1-1-1-1", "00000000000000000000000000000004", ""})
    void abbreviatedUuidCannotReachPersistence(String value) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = new OutputRegistrationStore(jdbc, json);
        var root = (tools.jackson.databind.node.ObjectNode) json.readTree(fixture());
        root.put("eventId", value);
        assertThatThrownBy(() -> store.accept(json.writeValueAsString(root)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"0", "\"20350102\"", "\"2035-01-02T00:00:00Z\"", "null"})
    void nonCalendarBusinessDateCannotReachPersistence(String value) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = new OutputRegistrationStore(jdbc, json);
        var root = (tools.jackson.databind.node.ObjectNode) json.readTree(fixture());
        var item = (tools.jackson.databind.node.ObjectNode) root.get("registrations").get(0);
        item.set("businessDate", json.readTree(value));
        assertThatThrownBy(() -> store.accept(json.writeValueAsString(root)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void invalidWorkHashAndPartialArraysFailBeforeAnyDatabaseMutation() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = new OutputRegistrationStore(jdbc, json);
        String fixture = fixture();
        String work =
                json.readValue(fixture, OutputRegistrationEvent.class)
                        .registrations()
                        .getFirst()
                        .atomicWorkId();
        assertThatThrownBy(() -> store.accept(fixture.replace(work, "work:" + "0".repeat(64))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                store.accept(
                                        "{\"schemaVersion\":1,\"eventId\":\"00000000-0000-0000-0000-000000000004\",\"registrations\":[]}"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.accept("x".repeat(OutputRegistrationEvent.MAX_BYTES + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                store.accept(
                                        fixture.replace(
                                                "\"rowCount\": 1",
                                                "\"rowCount\": 1, \"path\": \"physical\"")))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void sharedWorkRowsFixtureMatchesPythonAndRejectsInvalidCoordinates() throws Exception {
        Path path = Path.of("../../libs/contracts/fixtures/output-registration-work-rows-v1.json");
        if (!Files.exists(path))
            path = Path.of("libs/contracts/fixtures/output-registration-work-rows-v1.json");
        var raw = json.readTree(Files.readString(path));
        OutputRegistrationEvent.validateWire(raw);
        var event = json.treeToValue(raw, OutputRegistrationEvent.class);
        assertThat(json.readTree(json.writeValueAsString(event))).isEqualTo(raw);
        for (String change :
                java.util.List.of("identity", "count", "coverage", "date", "coordinate", "type")) {
            var invalid =
                    (tools.jackson.databind.node.ObjectNode) json.readTree(Files.readString(path));
            var output =
                    (tools.jackson.databind.node.ObjectNode)
                            invalid.get("registrations").get(0).get("outputs").get(0);
            var scope = (tools.jackson.databind.node.ObjectNode) output.get("workScope");
            var row = (tools.jackson.databind.node.ObjectNode) scope.get("rowKeys").get(0);
            switch (change) {
                case "identity" -> scope.put("atomicWorkId", "work:" + "0".repeat(64));
                case "count" -> output.put("rowCount", 2);
                case "coverage" -> row.put("date", "2035-01-03");
                case "date" -> row.put("date", "20350102");
                case "coordinate" -> row.put("symbol_key", "HOSE-VCI");
                case "type" -> row.put("date", 20350102);
            }
            assertThatThrownBy(
                            () -> {
                                OutputRegistrationEvent.validateWire(invalid);
                                json.treeToValue(invalid, OutputRegistrationEvent.class);
                            })
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
