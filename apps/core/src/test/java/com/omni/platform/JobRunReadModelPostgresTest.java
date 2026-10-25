package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

/** Executes the operator contract against actual persisted runs, never retained data. */
@Testcontainers
@Timeout(120)
class JobRunReadModelPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static ConfigurableApplicationContext context;
    static SchedulerDiagnosticsFixture f;
    static JsonNode expectations;

    @BeforeAll
    static void start() throws Exception {
        context = PlatformStartupPostgresTest.startApplication(POSTGRES, false);
        f = new SchedulerDiagnosticsFixture(context);
        expectations =
                f.json.readTree(
                        Files.readString(
                                Path.of(
                                        "../../database/diagnostics/p14/v1/read-model-fixtures.json")));
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @BeforeEach
    void reset() {
        f.jdbc.execute("TRUNCATE job_runs,job_execution_histories CASCADE");
    }

    @Test
    void exposesBoundedFrozenPathCountsAndDatabaseCaptureClocks() throws Exception {
        UUID run = f.run(2, true, true);
        Instant before = Instant.now();
        var envelope = f.query("run_steps.sql", f.runParameters(List.of(run)));
        Instant after = Instant.now();
        var model = envelope.get("readModel");
        expect(model, "unfinished-join");
        assertThat(model.get("runs").get(0).get("job_run_id").asText()).isEqualTo(run.toString());
        assertThat(model.get("runs").get(0).get("path_fingerprint").asText()).hasSize(64);
        assertThat(model.get("runs").get(0).get("path_contract_version").asInt()).isEqualTo(1);
        assertThat(envelope.get("rows").size())
                .isEqualTo(3); // Existing v1 diagnostics remain available.
        Instant started = Instant.parse(model.get("captureStartedAt").asText());
        Instant finished = Instant.parse(model.get("captureFinishedAt").asText());
        assertThat(started).isBetween(before, after);
        assertThat(finished).isBetween(started, after);
        assertThat(model.get("captureDurationMs").asDouble()).isGreaterThanOrEqualTo(0);
        // The explicit lease observation clock can be synthetic; it is not capture freshness.
        assertThat(envelope.get("observedAt").asText()).startsWith("2035-01-02");
        assertThat(started).isNotEqualTo(SchedulerDiagnosticsFixture.NOW);
        assertThat(model.get("throughput").get("publishedPerSecond").isNull()).isTrue();
        assertThat(model.get("throughput").get("completedPerSecond").isNull()).isTrue();
    }

    @Test
    void publishedAndPreparationRunningNeverInventWorkerProcessingOrReady() throws Exception {
        UUID run = f.run(1, true, true);
        success(run, 0);
        f.jdbc.update(
                "UPDATE scheduler_outbox_messages SET status='PUBLISHED' WHERE execution_id IN (SELECT id FROM job_execution_histories WHERE job_run_id=?)",
                run);
        var model = read(run);
        expect(model, "published-join");
        for (var step : model.get("runs").get(0).get("steps")) {
            assertThat(step.get("worker_processing").isNull()).isTrue();
            assertThat(step.get("dataset_ready").isNull()).isTrue();
        }
    }

    @Test
    void completedExecutionPathReportsFractionWithoutClaimingDatasetReadiness() throws Exception {
        UUID run = f.run(1, true, false);
        for (int step = 0; step < 3; step++) success(run, step);
        expect(read(run), "completed-chain");
        assertThat(read(run).get("runs").get(0).get("steps").get(2).get("dataset_ready").isNull())
                .isTrue();
    }

    @Test
    void blockedOutboxOverridesSuccessAndKeepsJoinWaiting() throws Exception {
        UUID run = f.run(1, true, true);
        success(run, 0);
        f.jdbc.update(
                "UPDATE scheduler_outbox_messages SET status='BLOCKED' WHERE execution_id IN (SELECT id FROM job_execution_histories WHERE job_run_id=? AND job_run_step_index=0)",
                run);
        expect(read(run), "blocked-join");
    }

    @Test
    void missingClosureAndUnsupportedRunStateStayUnknownWithNullFraction() throws Exception {
        UUID open = f.run(1, false, false);
        expect(read(open), "unknown-membership");
        UUID terminal = f.run(1, true, false);
        f.jdbc.update("UPDATE job_runs SET state='FAILED' WHERE id=?", terminal);
        f.jdbc.update(
                "UPDATE scheduler_outbox_messages SET status='BLOCKED' WHERE execution_id IN (SELECT id FROM job_execution_histories WHERE job_run_id=?)",
                terminal);
        expect(read(terminal), "terminal-run");
    }

    @Test
    void waitReasonsSeparateLiveLeaseRetryFailureAndCandidateEligibility() throws Exception {
        UUID run = f.run(1, true, false);
        f.jdbc.update(
                "UPDATE scheduler_outbox_messages SET claim_token=gen_random_uuid(),claimed_by='read-model-fixture',claim_until=? WHERE execution_id IN (SELECT id FROM job_execution_histories WHERE job_run_id=? AND job_run_step_index=0)",
                java.sql.Timestamp.from(SchedulerDiagnosticsFixture.NOW.plusSeconds(60)),
                run);
        expect(read(run), "live-lease");
        f.jdbc.update(
                "UPDATE scheduler_outbox_messages SET claim_token=NULL,claimed_by=NULL,claim_until=NULL,available_at=? WHERE execution_id IN (SELECT id FROM job_execution_histories WHERE job_run_id=? AND job_run_step_index=0)",
                java.sql.Timestamp.from(SchedulerDiagnosticsFixture.NOW.plusSeconds(60)),
                run);
        expect(read(run), "retry-delayed");
        f.jdbc.update(
                "UPDATE job_execution_histories SET status='FAILED' WHERE job_run_id=? AND job_run_step_index=0",
                run);
        expect(read(run), "failed-chain");
    }

    @Test
    void invalidMissingDuplicateAndOverBoundWindowsCannotMasqueradeAsIdle() throws Exception {
        UUID run = f.run(1, true, false);
        var oversized = new ArrayList<UUID>();
        for (int i = 0; i < 33; i++) oversized.add(UUID.randomUUID());
        for (var ids :
                List.of(
                        List.<UUID>of(),
                        List.of(UUID.randomUUID()),
                        List.of(run, run),
                        oversized)) {
            var envelope = f.query("run_steps.sql", f.runParameters(ids));
            expect(envelope.get("readModel"), "unavailable-window");
            assertThat(envelope.get("readModel").get("runs").isEmpty()).isTrue();
        }
        assertThatThrownBy(
                        () ->
                                f.query(
                                        "run_steps.sql",
                                        java.util.Map.of(
                                                "run_ids",
                                                "not-json",
                                                "observed_at",
                                                SchedulerDiagnosticsFixture.NOW.toString())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void zeroChildClosedStepsStillNeedRootSuccessAndNoQueueDoesNotMeanIdle() throws Exception {
        UUID run = f.run(0, true, false);
        var initial = read(run).get("runs").get(0);
        assertThat(initial.get("eligible").asInt()).isZero();
        assertThat(initial.get("progress").asText()).isEqualTo("UNFINISHED");
        assertThat(initial.get("steps").get(0).get("observation_reason").asText())
                .isEqualTo("EXECUTION_UNFINISHED");
        assertThat(initial.get("steps").get(1).get("predecessor_wait").asBoolean()).isTrue();
        for (int step = 0; step < 3; step++) success(run, step);
        expect(read(run), "completed-chain");
    }

    @Test
    void thirtyTwoRunsRemainIsolatedAndNeverBecomeGlobalBacklog() throws Exception {
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 32; i++) ids.add(f.run(1, true, false));
        var model = f.query("run_steps.sql", f.runParameters(ids)).get("readModel");
        assertThat(model.get("complete").asBoolean()).isTrue();
        assertThat(model.get("runs").size()).isEqualTo(32);
        for (var run : model.get("runs")) {
            assertThat(run.get("eligible").asInt()).isEqualTo(3);
            assertThat(run.get("observed_steps").asInt()).isEqualTo(3);
            assertThat(run.get("steps").size()).isEqualTo(3);
        }
        assertThat(read(ids.getFirst()).get("runs").size()).isEqualTo(1);
    }

    private JsonNode read(UUID run) throws Exception {
        return f.query("run_steps.sql", f.runParameters(List.of(run))).get("readModel");
    }

    private void success(UUID run, int step) {
        f.jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS' WHERE job_run_id=? AND job_run_step_index=?",
                run,
                step);
    }

    private void expect(JsonNode model, String scenario) {
        var expected = expectations.get("cases").get(scenario);
        assertThat(expected).as(scenario).isNotNull();
        for (var entry : expected.properties()) {
            assertThat(model.at(entry.getKey()))
                    .as(scenario + entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }
}
