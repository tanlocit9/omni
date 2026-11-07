package com.omni.platform.modules.scheduler.metadata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.omni.platform.modules.scheduler.services.JobService;
import tools.jackson.databind.json.JsonMapper;

/** Fail-closed operational boundaries; real SQL/concurrency is exercised by PostgreSQL tests. */
class OutputSafetyBoundaryTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"negative", "zero", "delay-low", "delay-high", "batch-low", "batch-high"})
    void watchdogRejectsUnsafeSchedulingBounds(String state) {
        var jdbc = mock(JdbcTemplate.class);
        var timeout = state.equals("negative") ? Duration.ofSeconds(-1)
                : state.equals("zero") ? Duration.ZERO : Duration.ofMinutes(30);
        long delay = state.equals("delay-low") ? 59999 : state.equals("delay-high") ? 300001 : 120000;
        int batch = state.equals("batch-low") ? 0 : state.equals("batch-high") ? 1001 : 100;
        assertThatThrownBy(() -> new ExecutionTimeoutWatchdog(jdbc, json,
                mock(JobService.class), mock(PlatformTransactionManager.class), timeout, delay, batch))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-page", "terminal-or-locked", "unpublished", "recent"})
    void watchdogCannotFailWorkAfterTerminalOrPublicationRace(String state) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-04T10:00:00Z");
        JdbcTemplate jdbc = mock(JdbcTemplate.class, call -> {
            if (call.getMethod().getName().equals("queryForList")) {
                String sql = call.getArgument(0);
                if (sql.contains("SELECT e.id FROM"))
                    return state.equals("empty-page") ? List.of() : List.of(id);
                return state.equals("terminal-or-locked") ? List.of() : List.of(Map.of("id", id));
            }
            if (call.getMethod().getName().equals("queryForObject"))
                return state.equals("unpublished") ? null : Timestamp.from(now);
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        var jobs = mock(JobService.class);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var watchdog = new ExecutionTimeoutWatchdog(jdbc, json, jobs, manager,
                Duration.ofMinutes(30), 120000, 1);
        assertThat(watchdog.scan(now)).isZero();
        assertThat(watchdog.scan(now.plusSeconds(1))).isZero();
        verifyNoInteractions(jobs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "missing-id", "missing-output", "fence", "active", "uncertain",
            "dataset", "partition", "schema", "date", "conflict", "live", "eod", "indicators", "signals"})
    void backfillCannotInventProvenanceOrOverrideAnUnsafeBaseline(String state) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class, call -> {
            if (call.getMethod().getName().equals("queryForObject")) {
                if (call.getArgument(1) == Long.class) return state.equals("fence") ? 8L : 7L;
                String sql = call.getArgument(0);
                if (sql.contains("job_execution_histories")) return state.equals("active");
                if (sql.contains("output_attempt_timeouts")) return state.equals("uncertain");
                if (sql.contains("output_ready_pointers")) return state.equals("live");
                if (sql.contains("output_ready_baselines")) return !state.equals("conflict");
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(call);
        });
        String dataset = List.of("indicators", "signals").contains(state) ? state
                : state.equals("dataset") ? "unknown" : "eod";
        Map<String, String> partition = switch (dataset) {
            case "signals" -> Map.of("exchange", "hose", "strategy", "trend", "timeframe", "1d");
            case "indicators" -> Map.of("exchange", "hose", "code", "hpg", "source", "ad_close", "timeframe", "1d");
            default -> state.equals("partition") ? Map.of("exchange", "hose") : Map.of("exchange", "hose", "code", "hpg");
        };
        var output = new OutputRegistrationEvent.Output(dataset, partition,
                "sha256:" + "a".repeat(64), List.of(LocalDate.of(2026, 11, 4)), 1, 128,
                state.equals("schema") ? 2 : 1, "sha256:" + "b".repeat(64),
                List.of(new OutputRegistrationEvent.Column(state.equals("date") ? "close"
                        : dataset.equals("signals") ? "signal_date" : "date", "DATE", false)), List.of());
        var baseline = state.equals("null") ? null : new OutputBackfillService.Baseline(7L,
                state.equals("missing-id") ? null : UUID.randomUUID(),
                state.equals("missing-output") ? null : output);
        var backfill = new OutputBackfillService(jdbc, json);
        if (List.of("live", "eod", "indicators", "signals").contains(state))
            assertThatCode(() -> backfill.commit(baseline)).doesNotThrowAnyException();
        else
            assertThatThrownBy(() -> backfill.commit(baseline)).isInstanceOf(IllegalArgumentException.class);
        if (!List.of("eod", "indicators", "signals", "conflict").contains(state))
            verify(jdbc, never()).update(startsWith("INSERT INTO output_ready_baselines"), any(), any(), any(), any());
    }
}
