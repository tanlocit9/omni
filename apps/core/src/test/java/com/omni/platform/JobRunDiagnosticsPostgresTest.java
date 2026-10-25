package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Timeout(120)
class JobRunDiagnosticsPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static ConfigurableApplicationContext context;
    static SchedulerDiagnosticsFixture f;

    @BeforeAll
    static void start() {
        context = PlatformStartupPostgresTest.startApplication(POSTGRES, false);
        f = new SchedulerDiagnosticsFixture(context);
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
    void blockedOutboxOverridesSuccessfulAndRunningExecutionsForChainsAndJoins() throws Exception {
        for (boolean completed : List.of(true, false)) {
            for (boolean join : List.of(false, true)) {
                UUID run = f.run(1, true, join);
                if (completed) {
                    f.jdbc.update(
                            "UPDATE job_execution_histories SET status='SUCCESS' WHERE job_run_id=? AND (job_run_step_index=0 OR ? AND job_run_step_index=1)",
                            run,
                            join);
                }
                UUID message =
                        f.jdbc.queryForObject(
                                "SELECT o.id FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id WHERE h.job_run_id=? AND h.job_run_step_index=0",
                                UUID.class,
                                run);
                if (completed) {
                    assertThat(
                                    context.getBean(
                                                    com.omni.platform.modules.scheduler.services
                                                            .SchedulerOutboxService.class)
                                            .markBlocked(
                                                    message,
                                                    SchedulerDiagnosticsFixture.NOW,
                                                    "review regression"))
                            .isTrue();
                    assertThat(
                                    f.jdbc.queryForObject(
                                            "SELECT count(*) FROM job_execution_histories WHERE job_run_id=? AND job_run_step_index=0 AND status<>'SUCCESS'",
                                            Integer.class,
                                            run))
                            .isZero();
                } else {
                    // Observe an existing inconsistent row without fabricating worker failure.
                    f.jdbc.update(
                            "UPDATE scheduler_outbox_messages SET status='BLOCKED' WHERE id=?",
                            message);
                }
                var observed = f.query("run_steps.sql", f.runParameters(List.of(run))).get("rows");
                assertThat(observed.get(0).get("progress").asText()).isEqualTo("FAILED_OR_BLOCKED");
                assertThat(observed.get(join ? 2 : 1).get("predecessor_wait").asBoolean()).isTrue();
                var actual =
                        context.getBean(
                                        com.omni.platform.modules.scheduler.planning
                                                .JobRunDispatchSnapshotReader.class)
                                .capture(SchedulerDiagnosticsFixture.NOW, List.of(run))
                                .runs()
                                .getFirst()
                                .steps()
                                .getFirst();
                assertThat(actual.progress())
                        .isEqualTo(
                                com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot
                                        .Progress.FAILED);
            }
        }
    }

    @Test
    void jsonSyntaxValidButJsonbIncompatiblePayloadsCannotPoisonInventoryPagination()
            throws Exception {
        for (String payload :
                List.of("{\"x\":1e1000000}", "{\"x\":\"\\u0000\"}", "{\"x\":\"\\ud800\"}")) {
            f.jdbc.execute("TRUNCATE job_runs,job_execution_histories CASCADE");
            UUID poison = f.legacy("alias", "PENDING"), valid = f.legacy("alias", "PENDING");
            f.jdbc.update(
                    "UPDATE scheduler_outbox_messages SET payload=? WHERE id=?", payload, poison);
            assertThat(
                            f.jdbc.queryForObject(
                                    "SELECT CAST(? AS text) IS JSON OBJECT",
                                    Boolean.class,
                                    payload))
                    .isTrue();
            var all = f.query("legacy_outbox_inventory.sql", f.legacyParameters("", 400));
            assertThat(all.get("counts").get("INVALID_JSON_OBJECT").asInt()).isEqualTo(1);
            assertThat(all.get("counts").get("LEGACY_ALIAS").asInt()).isEqualTo(1);
            var seen = new java.util.HashSet<String>();
            String cursor = "";
            for (int page = 0; page < 2; page++) {
                var result = f.query("legacy_outbox_inventory.sql", f.legacyParameters(cursor, 1));
                assertThat(result.get("rows").size()).isEqualTo(1);
                seen.add(result.get("rows").get(0).get("message_id").asText());
                assertThat(result.get("hasMore").asBoolean()).isEqualTo(page == 0);
                cursor = result.get("nextCursor").asText();
            }
            assertThat(seen).containsExactlyInAnyOrder(poison.toString(), valid.toString());
        }
    }

    @Test
    void runCountsDoNotMultiplyMessagesAndKeepPublishedSeparateFromCompletion() throws Exception {
        UUID run = f.run(3, true, false);
        f.run(5, true, false); // Another run must not multiply or satisfy the selected cohort.
        var parameters = f.runParameters(List.of(run));
        var before = f.query("run_steps.sql", parameters);
        assertThat(before.get("complete").asBoolean()).isTrue();
        assertThat(before.get("rows").size()).isEqualTo(3);
        var root = before.get("rows").get(0);
        assertThat(root.get("executions").asInt()).isEqualTo(4);
        assertThat(root.get("eligible").asInt()).isEqualTo(3);
        assertThat(before.get("rows").get(2).get("predecessor_wait").asBoolean()).isTrue();
        f.jdbc.update(
                "UPDATE scheduler_outbox_messages SET status='PUBLISHED' WHERE execution_id IN (SELECT id FROM job_execution_histories WHERE job_run_id=? AND job_run_step_index=0)",
                run);
        root = f.query("run_steps.sql", parameters).get("rows").get(0);
        assertThat(root.get("published").asInt()).isEqualTo(3);
        assertThat(root.get("progress").asText()).isEqualTo("UNFINISHED");
        assertThat(root.get("published_but_unfinished").asBoolean()).isTrue();
        f.jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS' WHERE job_run_id=? AND job_run_step_index=0",
                run);
        var after = f.query("run_steps.sql", parameters);
        assertThat(after.get("rows").get(0).get("progress").asText()).isEqualTo("SUCCESS");
        assertThat(after.get("rows").get(1).get("predecessor_wait").asBoolean()).isFalse();
        assertThat(after.get("rows").get(2).get("predecessor_wait").asBoolean()).isTrue();
    }

    @Test
    void joinsFailuresUnknownClosureAndMissingOrOversizedWindowsFailClosed() throws Exception {
        UUID join = f.run(1, true, true), unknown = f.run(1, false, false);
        f.jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS' WHERE job_run_id=? AND job_run_step_index=0",
                join);
        var rows = f.query("run_steps.sql", f.runParameters(List.of(join))).get("rows");
        assertThat(rows.get(2).get("ancestor_count").asInt()).isEqualTo(2);
        assertThat(rows.get(2).get("predecessor_wait").asBoolean()).isTrue();
        f.jdbc.update(
                "UPDATE job_execution_histories SET status='FAILED' WHERE job_run_id=? AND job_run_step_index=1",
                join);
        assertThat(
                        f.query("run_steps.sql", f.runParameters(List.of(join)))
                                .get("rows")
                                .get(1)
                                .get("progress")
                                .asText())
                .isEqualTo("FAILED_OR_BLOCKED");
        assertThat(
                        f.query("run_steps.sql", f.runParameters(List.of(unknown)))
                                .get("rows")
                                .get(0)
                                .get("progress")
                                .asText())
                .isEqualTo("UNKNOWN_MEMBERSHIP");
        for (var ids : List.of(List.<UUID>of(), List.of(UUID.randomUUID()), List.of(join, join)))
            assertThat(f.query("run_steps.sql", f.runParameters(ids)).get("complete").asBoolean())
                    .isFalse();
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 33; i++) ids.add(UUID.randomUUID());
        assertThat(f.query("run_steps.sql", f.runParameters(ids)).get("complete").asBoolean())
                .isFalse();
    }

    @Test
    void legacyPagesClassifyPoisonMissingAndAliasesAcrossAllDeliveryStates() throws Exception {
        f.legacy("alias", "PENDING");
        f.legacy("alias", "PUBLISHED");
        f.legacy("alias", "BLOCKED");
        f.legacy("current", "PENDING");
        f.legacy("malformed", "PENDING");
        f.legacy("array", "PENDING");
        f.legacy("missing", "PENDING");
        f.legacy("invalid", "PENDING");
        f.legacy("absent", "PENDING");
        var all = f.query("legacy_outbox_inventory.sql", f.legacyParameters("", 400));
        assertThat(all.get("counts").get("LEGACY_ALIAS").asInt()).isEqualTo(3);
        assertThat(all.get("counts").get("CURRENT_IDENTITY").asInt()).isEqualTo(1);
        assertThat(all.get("counts").get("INVALID_JSON_OBJECT").asInt()).isEqualTo(2);
        assertThat(all.get("counts").get("MISSING_WORK_EXECUTION").asInt()).isEqualTo(1);
        assertThat(all.get("counts").get("MISSING_WORK_ID").asInt()).isEqualTo(1);
        assertThat(all.get("counts").get("INVALID_WORK_ID").asInt()).isEqualTo(1);
        var page = f.query("legacy_outbox_inventory.sql", f.legacyParameters("", 2));
        assertThat(page.get("rows").size()).isEqualTo(2);
        assertThat(page.get("hasMore").asBoolean()).isTrue();
        var next =
                f.query(
                        "legacy_outbox_inventory.sql",
                        f.legacyParameters(page.get("nextCursor").asText(), 400));
        assertThat(next.get("rows").size()).isEqualTo(7);
        assertThat(next.get("hasMore").asBoolean()).isFalse();
    }

    @Test
    void inventorySurfacesCollisionsAndReadOnlyTransactionRejectsMutation() throws Exception {
        UUID message = f.legacy("alias", "PENDING");
        var row =
                f.query("legacy_outbox_inventory.sql", f.legacyParameters("", 400))
                        .get("rows")
                        .get(0);
        UUID child = UUID.fromString(row.get("work_id").asText());
        f.jdbc.update(
                "INSERT INTO scheduler_outbox_messages(execution_id,message_index,topic,payload) VALUES (?,0,'diagnostic','{}')",
                child);
        var rows = f.query("legacy_outbox_inventory.sql", f.legacyParameters("", 400)).get("rows");
        boolean found = false;
        for (var item : rows)
            if (item.get("message_id").asText().equals(message.toString()))
                found = item.get("identity_state").asText().equals("PROJECTED_UNIQUE_COLLISION");
        assertThat(found).isTrue();
        var read =
                new org.springframework.transaction.support.TransactionTemplate(
                        context.getBean(
                                org.springframework.transaction.PlatformTransactionManager.class));
        read.setReadOnly(true);
        assertThatThrownBy(
                        () ->
                                read.execute(
                                        s ->
                                                f.jdbc.update(
                                                        "DELETE FROM scheduler_outbox_messages WHERE id=?",
                                                        message)))
                .hasMessageContaining("read-only");
        assertThat(
                        f.jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages WHERE id=?",
                                Integer.class,
                                message))
                .isEqualTo(1);
    }

    @Test
    void leaseRetryBlockedAndEmptyMembershipAreReportedWithoutInventingReadiness()
            throws Exception {
        UUID run = f.run(4, true, false), empty = f.run(0, true, false);
        var ids =
                f.jdbc.queryForList(
                        "SELECT o.id FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id WHERE h.job_run_id=? AND h.job_run_step_index=0 ORDER BY o.id",
                        UUID.class,
                        run);
        f.jdbc.update(
                "UPDATE scheduler_outbox_messages SET claim_token=?,claimed_by='diagnostic',claim_until=? WHERE id=?",
                UUID.randomUUID(),
                java.sql.Timestamp.from(SchedulerDiagnosticsFixture.NOW.plusSeconds(30)),
                ids.get(0));
        f.jdbc.update(
                "UPDATE scheduler_outbox_messages SET available_at=? WHERE id=?",
                java.sql.Timestamp.from(SchedulerDiagnosticsFixture.NOW.plusSeconds(30)),
                ids.get(1));
        f.jdbc.update(
                "UPDATE scheduler_outbox_messages SET status='BLOCKED' WHERE id=?", ids.get(2));
        var row = f.query("run_steps.sql", f.runParameters(List.of(run))).get("rows").get(0);
        for (String name : List.of("eligible", "leased", "retry_delayed", "blocked"))
            assertThat(row.get(name).asInt()).as(name).isEqualTo(1);
        assertThat(
                        f.query("run_steps.sql", f.runParameters(List.of(empty)))
                                .get("rows")
                                .get(0)
                                .get("progress")
                                .asText())
                .isEqualTo("UNFINISHED");
        f.jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS' WHERE job_run_id=?", empty);
        assertThat(
                        f.query("run_steps.sql", f.runParameters(List.of(empty)))
                                .get("rows")
                                .get(0)
                                .get("progress")
                                .asText())
                .isEqualTo("SUCCESS");
    }

    @Test
    void aliasParentDefinitionPayloadAndLinkedRunConflictsRemainExplicit() throws Exception {
        for (String expected :
                List.of(
                        "PARENT_CONFLICT",
                        "DEFINITION_CONFLICT",
                        "PAYLOAD_PARENT_CONFLICT",
                        "LINKED_RUN_CONFLICT")) {
            f.jdbc.execute("TRUNCATE job_runs,job_execution_histories CASCADE");
            UUID message = f.legacy("alias", "PENDING");
            var row =
                    f.query("legacy_outbox_inventory.sql", f.legacyParameters("", 400))
                            .get("rows")
                            .get(0);
            UUID child = UUID.fromString(row.get("work_id").asText());
            switch (expected) {
                case "PARENT_CONFLICT" ->
                        f.jdbc.update(
                                "UPDATE job_execution_histories SET parent_log_id=NULL WHERE id=?",
                                child);
                case "DEFINITION_CONFLICT" ->
                        f.jdbc.update(
                                "UPDATE job_execution_histories SET job_id=(SELECT id FROM job_definitions WHERE source='VND' AND cron_expr='diag-b') WHERE id=?",
                                child);
                case "PAYLOAD_PARENT_CONFLICT" ->
                        f.jdbc.update(
                                "UPDATE scheduler_outbox_messages SET payload=replace(payload,?,?) WHERE id=?",
                                row.get("stored_execution_id").asText(),
                                UUID.randomUUID().toString(),
                                message);
                case "LINKED_RUN_CONFLICT" -> {
                    UUID linked = f.run(1, true, false);
                    UUID linkedChild =
                            f.jdbc.queryForObject(
                                    "SELECT id FROM job_execution_histories WHERE job_run_id=? AND job_run_step_index=0 AND parent_log_id IS NOT NULL",
                                    UUID.class,
                                    linked);
                    f.jdbc.update(
                            "UPDATE scheduler_outbox_messages SET payload=replace(payload,?,?) WHERE id=?",
                            child.toString(),
                            linkedChild.toString(),
                            message);
                }
                default -> throw new IllegalStateException();
            }
            boolean found = false;
            for (var item :
                    f.query("legacy_outbox_inventory.sql", f.legacyParameters("", 400)).get("rows"))
                if (item.get("message_id").asText().equals(message.toString()))
                    found = item.get("identity_state").asText().equals(expected);
            assertThat(found).as(expected).isTrue();
        }
    }
}
