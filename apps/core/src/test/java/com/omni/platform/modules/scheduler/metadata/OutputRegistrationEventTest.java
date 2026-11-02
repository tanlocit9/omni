package com.omni.platform.modules.scheduler.metadata;

import static org.assertj.core.api.Assertions.assertThat;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"true", "1.0", "1.5", "\"1\"", "null", "18446744073709551616"})
    void coercedStatisticsFailBeforeDatabaseAccess(String value) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var store = new OutputRegistrationStore(jdbc, json);
        for (String field : java.util.List.of("rowCount", "totalBytes", "schemaVersion")) {
            var root = (tools.jackson.databind.node.ObjectNode) json.readTree(fixture());
            var output = (tools.jackson.databind.node.ObjectNode) root.get("registrations").get(0).get("outputs").get(0);
            output.set(field, json.readTree(value));
            assertThatThrownBy(() -> store.accept(json.writeValueAsString(root)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc);
    }

    private String fixture() throws Exception {
        Path path = Path.of("../../libs/contracts/fixtures/output-registration-v1.json");
        if (!Files.exists(path)) path = Path.of("libs/contracts/fixtures/output-registration-v1.json");
        return Files.readString(path);
    }

    @Test
    void sharedFixtureRoundTripsWithExactWorkIdentityAndDateCoverage() throws Exception {
        var event = json.readValue(fixture(), OutputRegistrationEvent.class);
        assertThat(event.registrations()).hasSize(1);
        assertThat(event.registrations().getFirst().outputs().getFirst().coverageDates())
                .containsExactly(java.time.LocalDate.of(2035, 1, 2));
        assertThat(json.readTree(json.writeValueAsString(event))).isEqualTo(json.readTree(fixture()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"1-1-1-1-1", "00000000000000000000000000000004", ""})
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
    @org.junit.jupiter.params.provider.ValueSource(strings = {"0", "\"20350102\"", "\"2035-01-02T00:00:00Z\"", "null"})
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
        String work = json.readValue(fixture, OutputRegistrationEvent.class).registrations().getFirst().atomicWorkId();
        assertThatThrownBy(() -> store.accept(fixture.replace(work, "work:" + "0".repeat(64))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.accept("{\"schemaVersion\":1,\"eventId\":\"00000000-0000-0000-0000-000000000004\",\"registrations\":[]}"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.accept("x".repeat(OutputRegistrationEvent.MAX_BYTES + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.accept(fixture.replace("\"rowCount\": 1", "\"rowCount\": 1, \"path\": \"physical\"")))
                .isInstanceOf(RuntimeException.class);
        verifyNoInteractions(jdbc);
    }
}
