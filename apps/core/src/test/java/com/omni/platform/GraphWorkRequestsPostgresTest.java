package com.omni.platform;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.config.SchedulerProperties;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.runs.JobRunPreparationService;
import com.omni.platform.modules.scheduler.services.SchedulerClaimService;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.modules.scheduler.work.GraphWorkRequestService;
import com.omni.platform.modules.scheduler.work.DefinitionConsolidationService;
import tools.jackson.databind.json.JsonMapper;

/** Real graph preparation, atomic acquire, barriers and retained history. Source-only in this handoff. */
@Testcontainers
@Timeout(180)
class GraphWorkRequestsPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final Instant NOW = Instant.parse("2035-01-02T12:00:00Z");
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static JobRunPreparationService preparation;
    static GraphWorkRequestService graph;
    static SchedulerClaimService claims;
    static JobDefinitionRepository definitions;

    @BeforeAll
    static void start() {
        start(true);
    }

    static void start(boolean graphEnabled) {
        context =
                PlatformStartupPostgresTest.startApplication(
                        POSTGRES,
                        true,
                        ctx ->
                                ctx.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "graph-work-test",
                                                        Map.of(
                                                                "app.scheduler.job-run.graph-expansion-enabled",
                                                                graphEnabled))));
        jdbc = context.getBean(JdbcTemplate.class);
        preparation = context.getBean(JobRunPreparationService.class);
        graph = context.getBean(GraphWorkRequestService.class);
        claims = context.getBean(SchedulerClaimService.class);
        definitions = context.getBean(JobDefinitionRepository.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) context.close();
    }

    @BeforeEach
    void reset() {
        jdbc.execute(
                "TRUNCATE job_runs,job_execution_histories,symbols,atomic_work_slots,job_definition_consolidations,output_registration_events,output_ready_baselines CASCADE");
        jdbc.update(
                "UPDATE job_definitions SET is_active=true,schedule_enabled=true,canonical_definition_id=NULL,"
                        + "claim_token=NULL,claimed_by=NULL,claimed_at=NULL,claim_until=NULL,next_run='2035-02-01'");
        var json = context.getBean("jsonMapper", JsonMapper.class);
        for (var seed : JobDefinitionConfig.JOB_DEFINITION_SEEDS)
            jdbc.update(
                    "UPDATE job_definitions SET config_json=?::jsonb,fallback_sources=?::jsonb WHERE source=? AND job_type=? AND cron_expr=?",
                    json.writeValueAsString(seed.config()),
                    json.writeValueAsString(seed.fallbackSources()),
                    seed.source().name(),
                    seed.jobType().name(),
                    seed.cronExpr());
        String sector = JobDefinitionConfig.ENABLED_SECTOR_CODES.getFirst();
        jdbc.update(
                "INSERT INTO symbols(code,exchange,is_active,meta_json) VALUES ('AAA','HOSE',true,jsonb_build_object('sectorLv2Code',?::text))",
                sector);
    }

    JobDefinition target(JobType type) {
        return definitions.findAll().stream()
                .filter(d -> d.getJobType() == type)
                .filter(
                        d ->
                                type != JobType.SYNC_STOCK_PRICE
                                        || com.omni.platform.modules.scheduler.constants
                                                .JobConfigMapper.toStockPriceConfig(
                                                        d.getConfigJson())
                                                .filters()
                                                .sectorCodes()
                                                .contains(
                                                        JobDefinitionConfig.ENABLED_SECTOR_CODES
                                                                .getFirst()))
                .findFirst()
                .orElseThrow();
    }

    SchedulerClaim claim(JobDefinition definition) {
        return claims.claimJobDefinition(definition.getId(), NOW, "operator").orElseThrow();
    }

    UUID prepare(JobDefinition target, String key) {
        return preparation.prepareManual(
                target,
                claim(target),
                NOW,
                "operator",
                key,
                "I9 regression",
                Map.of("scope", Map.of("type", "SYMBOL", "key", "HOSE-AAA")),
                Map.of());
    }

    UUID request(UUID execution) {
        return jdbc.queryForObject(
                "SELECT id FROM atomic_work_requests WHERE execution_id=?", UUID.class, execution);
    }

    int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void backfillEnrollsItsDatedPriceIndicatorChainWithoutEnablingRealtimeOrChangingCron() {
        var actual = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(graph);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "backfillEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "registrationConsumerEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "enabled", false);
        try {
            jdbc.update("UPDATE job_definitions SET schedule_enabled=false");
            var target = target(JobType.SYNC_INDICATORS);
            var nextRun = jdbc.queryForObject("SELECT next_run FROM job_definitions WHERE id=?", java.sql.Timestamp.class, target.getId());
            var day = NOW.atZone(com.omni.platform.modules.scheduler.expansion.WorkExpansion.ZONE).toLocalDate();
            String expansion = JsonMapper.builder().build().writeValueAsString(Map.of("dateFrom", day.toString(), "dateTo", day.toString(),
                    "sectorCodes", List.of(JobDefinitionConfig.ENABLED_SECTOR_CODES.getFirst()), "sectorLevel", 2, "force", true));
            var request = new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                    com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode.BACKFILL,
                    "operator", "backfill-chain", "Backfill verification", null, expansion);
            var execution = graph.prepareBackfill(request, target.getId(), NOW, Map.of("force", true));
            assertThat(graph.isEnabled()).isFalse();
            assertThat(jdbc.queryForObject("SELECT mode FROM atomic_work_requests WHERE execution_id=?",
                    String.class, execution)).isEqualTo("BACKFILL");
            assertThat(count("atomic_work_attempts")).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT next_run FROM job_definitions WHERE id=?", java.sql.Timestamp.class, target.getId())).isEqualTo(nextRun);
            assertThat(count("output_completion_contracts")).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM job_execution_histories WHERE meta_json ? 'backfillPolicy'",
                    Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduler_outbox_messages WHERE status='PUBLISHED'",
                    Integer.class)).isZero();
            assertThat(graph.prepareBackfill(request, target.getId(), NOW, Map.of("force", true))).isEqualTo(execution);
            assertThat(count("atomic_work_attempts")).isEqualTo(3);
            var changed = new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                    request.mode(), request.actor(), request.idempotencyKey(), request.reason(), null,
                    expansion.replace("\"force\":true", "\"force\":false"));
            assertThatThrownBy(() -> graph.prepareBackfill(changed, target.getId(), NOW, Map.of("force", false)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(actual, "backfillEnabled", false);
            org.springframework.test.util.ReflectionTestUtils.setField(actual, "registrationConsumerEnabled", false);
            org.springframework.test.util.ReflectionTestUtils.setField(actual, "enabled", true);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedTargetKeepsHealthyMembersAdmissibleUntilEveryMemberSettles(boolean unfinishedAncestor) {
        jdbc.update("INSERT INTO symbols(code,exchange,is_active,meta_json) VALUES ('BBB','HOSE',true,jsonb_build_object('sectorLv2Code',?::text))",
                JobDefinitionConfig.ENABLED_SECTOR_CODES.getFirst());
        var target = target(JobType.SYNC_INDICATORS);
        var request = new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode.MANUAL,
                "operator", "settlement", "Request settlement verification", null, "{}");
        UUID root = graph.prepare(request, target.getId(), claim(target), NOW, Map.of());
        UUID requestId = request(root);
        var members = jdbc.queryForList(
                "SELECT a.execution_id,m.is_target FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id WHERE m.request_id=? ORDER BY a.execution_id",
                requestId);
        var targets = members.stream().filter(m -> Boolean.TRUE.equals(m.get("is_target"))).toList();
        assertThat(targets).hasSize(2);
        UUID failed = (UUID) targets.getFirst().get("execution_id");
        UUID unfinished = (UUID) (unfinishedAncestor
                ? members.stream().filter(m -> !Boolean.TRUE.equals(m.get("is_target"))).findFirst().orElseThrow()
                : targets.getLast()).get("execution_id");
        jdbc.update("UPDATE job_execution_histories SET status='SUCCESS' WHERE id IN (SELECT a.execution_id FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id WHERE m.request_id=?)", requestId);
        jdbc.update("UPDATE job_execution_histories SET status='FAILED' WHERE id=?", failed);
        jdbc.update("UPDATE job_execution_histories SET status='PENDING' WHERE id=?", unfinished);
        graph.reconcile();
        var progress = graph.inspect(requestId);
        assertThat(progress).containsEntry("hasFailure", true).containsEntry("isSettled", false).containsEntry("unfinishedMembers", 1L);
        assertThat(jdbc.queryForObject("SELECT state FROM atomic_work_requests WHERE id=?", String.class, requestId)).isEqualTo("PREPARED");
        if (!unfinishedAncestor)
            assertThat(jdbc.queryForObject("SELECT atomic_work_admissible(?)", Boolean.class, unfinished)).isTrue();
        jdbc.update("UPDATE job_execution_histories SET status='SUCCESS' WHERE id=?", unfinished);
        for (int i = 0; i < 3; i++) graph.reconcile();
        assertThat(graph.inspect(requestId)).containsEntry("hasFailure", true).containsEntry("isSettled", true).containsEntry("unfinishedMembers", 0L);
        assertThat(jdbc.queryForObject("SELECT state FROM atomic_work_requests WHERE id=?", String.class, requestId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM job_execution_histories WHERE id=?", String.class, root)).isEqualTo("FAILED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rootCountsOnlyTargetsAndPreservesUnknownCounters(boolean missingCounter) {
        jdbc.update("INSERT INTO symbols(code,exchange,is_active,meta_json) VALUES ('BBB','HOSE',true,jsonb_build_object('sectorLv2Code',?::text))",
                JobDefinitionConfig.ENABLED_SECTOR_CODES.getFirst());
        var definition = target(JobType.SYNC_INDICATORS);
        var counterRequest = new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode.MANUAL,
                "operator", "target-counters", "Target counter regression", null, "{}");
        UUID root = graph.prepare(counterRequest, definition.getId(), claim(definition), NOW, Map.of());
        UUID requestId = request(root);
        jdbc.update("UPDATE job_execution_histories SET status='SUCCESS',records_synced=1000,records_skipped=100 "
                + "WHERE id IN (SELECT a.execution_id FROM atomic_work_request_members m "
                + "JOIN atomic_work_attempts a ON a.id=m.attempt_id WHERE m.request_id=?)", requestId);
        var targets = jdbc.queryForList("SELECT a.execution_id FROM atomic_work_request_members m "
                + "JOIN atomic_work_attempts a ON a.id=m.attempt_id WHERE m.request_id=? AND m.is_target "
                + "ORDER BY a.execution_id", UUID.class, requestId);
        assertThat(targets).hasSize(2);
        jdbc.update("UPDATE job_execution_histories SET records_synced=7,records_skipped=0 WHERE id=?", targets.getFirst());
        jdbc.update("UPDATE job_execution_histories SET records_synced=?,records_skipped=3 WHERE id=?",
                missingCounter ? null : 11, targets.getLast());
        for (int pass = 0; pass < 3; pass++) graph.reconcile();
        var result = jdbc.queryForMap("SELECT status,records_synced,records_skipped FROM job_execution_histories WHERE id=?", root);
        assertThat(result).containsEntry("status", "SUCCESS")
                .containsEntry("records_synced", missingCounter ? null : 18)
                .containsEntry("records_skipped", 3);
        // A later reconciliation must not rewrite the closed root's counters.
        jdbc.update("UPDATE job_execution_histories SET records_synced=999 WHERE id=?", targets.getFirst());
        graph.reconcile();
        assertThat(jdbc.queryForMap("SELECT status,records_synced,records_skipped FROM job_execution_histories WHERE id=?", root))
                .isEqualTo(result);
    }

    @Test
    void workerReceiptMovesPreparedMemberToRunningButCannotReopenTerminalAttempt() {
        UUID root = prepare(target(JobType.SYNC_INDICATORS), "worker-receipt");
        var row = jdbc.queryForMap(
                "SELECT e.id,e.job_id,e.parent_log_id,e.status,e.started_at,e.meta_json->>'workKey' AS work_key "
                        + "FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                        + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE m.request_id=? AND m.is_target",
                request(root));
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("started_at")).isNull();
        var jobs = context.getBean(JobService.class);
        var running = new com.omni.platform.modules.scheduler.messaging.JobStatusMessage(
                row.get("job_id").toString(), row.get("id").toString(), row.get("parent_log_id").toString(),
                com.omni.platform.shared.executions.WorkType.SYMBOL, row.get("work_key").toString(),
                "RUNNING", Map.of("workerStarted", true), null, NOW.plusSeconds(1), null, 0, null, 0);
        jobs.applyStatus(running);
        assertThat(jdbc.queryForObject("SELECT status FROM job_execution_histories WHERE id=?", String.class, row.get("id"))).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT finished_at FROM job_execution_histories WHERE id=?", java.sql.Timestamp.class, row.get("id"))).isNull();
        jobs.applyStatus(new com.omni.platform.modules.scheduler.messaging.JobStatusMessage(
                running.jobDefinitionId(), running.executionId(), running.parentExecutionId(), running.workType(), running.workKey(),
                "ERROR", Map.of(), null, running.startedAt(), NOW.plusSeconds(2), 1000, "provider error", 0));
        jobs.applyStatus(running);
        assertThat(jdbc.queryForObject("SELECT status FROM job_execution_histories WHERE id=?", String.class, row.get("id"))).isEqualTo("FAILED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"disabled", "consumer", "planning", "scheduled", "legacy", "sector", "empty", "partial", "force"})
    void backfillRefusesUnsafeOrEmptyInputsWithoutDurableWork(String reason) {
        var actual = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(graph);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "backfillEnabled", !reason.equals("disabled"));
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "registrationConsumerEnabled", !reason.equals("consumer"));
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "planningEnabled", !reason.equals("planning"));
        try {
            jdbc.update("UPDATE job_definitions SET schedule_enabled=false");
            var target = target(JobType.SYNC_INDICATORS);
            if (reason.equals("scheduled")) jdbc.update("UPDATE job_definitions SET schedule_enabled=true WHERE id=?", target.getId());
            if (reason.equals("partial")) {
                jdbc.update("UPDATE job_definitions SET config_json=config_json || '{\"exchanges\":[\"HOSE\"]}'::jsonb WHERE id=?", target.getId());
                jdbc.update("INSERT INTO symbols(code,exchange,is_active,meta_json) VALUES ('BBB','HNX',true,jsonb_build_object('sectorLv2Code',?::text))",
                        JobDefinitionConfig.ENABLED_SECTOR_CODES.getFirst());
            }
            if (reason.equals("legacy")) jdbc.update(
                    "INSERT INTO job_execution_histories(id,job_id,parent_log_id,used_source,status,started_at,meta_json) VALUES (?,?,?,'VCI','RUNNING',?,'{}'::jsonb)",
                    UUID.randomUUID(), target.getId(), UUID.randomUUID(), java.sql.Timestamp.from(NOW));
            var businessDay = NOW.atZone(com.omni.platform.modules.scheduler.expansion.WorkExpansion.ZONE).toLocalDate();
            var day = (reason.equals("empty") ? businessDay.minusDays(2) : businessDay).toString();
            String expansion = JsonMapper.builder().build().writeValueAsString(Map.of("dateFrom", day, "dateTo", day,
                    "sectorCodes", List.of(reason.equals("sector") ? "UNKNOWN" : JobDefinitionConfig.ENABLED_SECTOR_CODES.getFirst()),
                    "sectorLevel", 2, "force", true));
            var request = new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                    com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode.BACKFILL, "operator", "unsafe-" + reason,
                    "Backfill rejection verification", null, expansion);
            assertThatThrownBy(() -> graph.prepareBackfill(request, target.getId(), NOW, Map.of("force", !reason.equals("force"))))
                    .isInstanceOfAny(IllegalStateException.class, IllegalArgumentException.class)
                    .hasMessageContaining(switch (reason) {
                        case "disabled", "consumer", "planning" -> "BACKFILL_DISABLED_OR_REGISTRATION_UNAVAILABLE";
                        case "scheduled", "legacy" -> "BACKFILL_REQUIRES_PAUSED_AND_DRAINED_LEGACY_WRITERS";
                        case "sector" -> "No active symbols";
                        case "empty" -> "no target work";
                        case "partial" -> "INCOMPLETE_TARGET_MEMBERSHIP";
                        default -> "force policy";
                    });
            assertThat(count("atomic_work_requests")).isZero();
            assertThat(count("atomic_work_attempts")).isZero();
            assertThat(count("output_completion_contracts")).isZero();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(actual, "backfillEnabled", false);
            org.springframework.test.util.ReflectionTestUtils.setField(actual, "registrationConsumerEnabled", false);
            org.springframework.test.util.ReflectionTestUtils.setField(actual, "planningEnabled", true);
        }
    }

    @Test
    void diagnosticsPreserveSharedIdentityAndReadOnlyPagination() throws Exception {
        var indicator = target(JobType.SYNC_INDICATORS);
        prepare(indicator, "diagnostics-first");
        prepare(indicator, "diagnostics-second");
        var mapper = context.getBean("jsonMapper", JsonMapper.class);
        String sql =
                new org.springframework.core.io.ClassPathResource("diagnostics/p14/snapshot.sql")
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                        .replaceAll(":'([a-z_]+)'", ":$1");
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(
                        context.getBean(
                                org.springframework.transaction.PlatformTransactionManager.class));
        tx.setReadOnly(true);
        tx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        java.util.function.Function<String, tools.jackson.databind.JsonNode> page =
                cursor ->
                        tx.execute(
                                status -> {
                                    jdbc.execute("SET LOCAL statement_timeout='5s'");
                                    return mapper.readTree(
                                            new org.springframework.jdbc.core.namedparam
                                                            .NamedParameterJdbcTemplate(jdbc)
                                                    .queryForObject(
                                                            sql,
                                                            Map.of(
                                                                    "since",
                                                                    NOW.minusSeconds(3600)
                                                                            .toString(),
                                                                    "until",
                                                                    NOW.plusSeconds(1).toString(),
                                                                    "observed_at",
                                                                    NOW.plusSeconds(1).toString(),
                                                                    "after_id",
                                                                    cursor,
                                                                    "row_limit",
                                                                    1),
                                                            String.class));
                                });
        var first = page.apply("");
        assertThat(first.get("requests").size()).isEqualTo(1);
        assertThat(first.get("hasMoreRequests").asBoolean()).isTrue();
        assertThat(first.get("attempts").size()).isEqualTo(3);
        for (var attempt : first.get("attempts")) {
            assertThat(attempt.get("ownerCount").asInt()).isEqualTo(2);
            assertThat(attempt.get("identityMatches").asBoolean()).isTrue();
            assertThat(attempt.get("workerProcessing").isNull()).isTrue();
            assertThat(attempt.get("datasetReady").isNull()).isTrue();
        }
        var second = page.apply(first.get("nextCursor").asText());
        assertThat(second.get("hasMoreRequests").asBoolean()).isFalse();
        assertThat(second.get("requests").get(0).get("graphRequestId"))
                .isNotEqualTo(first.get("requests").get(0).get("graphRequestId"));
        var diagnostics =
                context.getBean(
                        com.omni.platform.modules.scheduler.work.AtomicWorkDiagnosticsService
                                .class);
        var detail = diagnostics.work(first.get("attempts").get(0).get("atomicWorkId").asText());
        assertThat((List<?>) detail.get("attempts")).hasSize(1);
        assertThat((List<?>) detail.get("owners")).hasSize(2);
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
        assertThat(count("atomic_work_requests")).isEqualTo(2);
    }

    @Test
    void diagnosticHttpReadsRequireAuthenticationAndKeepEmptyDistinctFromErrors() throws Exception {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        String base = "http://127.0.0.1:" + port + "/api/v1/jobs/atomic-work";
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            for (String path :
                    List.of(
                            "/snapshot",
                            "/published-unfinished",
                            "/work?atomicWorkId=work:" + "a".repeat(64))) {
                var denied =
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build();
                assertThat(
                                client.send(
                                                denied,
                                                java.net.http.HttpResponse.BodyHandlers.ofString())
                                        .statusCode())
                        .isEqualTo(401);
            }
            for (String path : List.of("/snapshot", "/published-unfinished")) {
                var allowed =
                        java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
                                .header("X-Omni-User", "test-operator")
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build();
                var response =
                        client.send(allowed, java.net.http.HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(
                                context.getBean("jsonMapper", JsonMapper.class)
                                        .readTree(response.body())
                                        .get("version")
                                        .asInt())
                        .isEqualTo(2);
            }
            var invalid =
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create(base + "/snapshot?limit=101"))
                            .header("X-Omni-User", "test-operator")
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
            assertThat(
                            client.send(invalid, java.net.http.HttpResponse.BodyHandlers.ofString())
                                    .statusCode())
                    .isEqualTo(400);
        }
    }

    @Test
    void diagnosticHttpDetailAndConsolidationReadsExposeDurableRowsAndMissingRequestIs404()
            throws Exception {
        UUID root = prepare(target(JobType.SYNC_INDICATORS), "http-detail");
        UUID requestId = request(root);
        String workId =
                jdbc.queryForObject(
                        "SELECT work_id FROM atomic_work_request_members WHERE request_id=? ORDER BY work_id LIMIT 1",
                        String.class,
                        requestId);
        var changes = context.getBean(DefinitionConsolidationService.class).consolidate(NOW);
        assertThat(changes).isNotEmpty();
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        String base = "http://127.0.0.1:" + port + "/api/v1/jobs/atomic-work";
        var json = context.getBean("jsonMapper", JsonMapper.class);
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            for (String path :
                    List.of(
                            "/requests/" + requestId,
                            "/requests/" + UUID.randomUUID(),
                            "/work?atomicWorkId=" + workId,
                            "/consolidations")) {
                var response =
                        client.send(
                                java.net.http.HttpRequest.newBuilder(
                                                java.net.URI.create(base + path))
                                        .header("X-Omni-User", "test-operator")
                                        .timeout(Duration.ofSeconds(10))
                                        .GET()
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString());
                if (path.startsWith("/requests/") && !path.endsWith(requestId.toString())) {
                    assertThat(response.statusCode()).isEqualTo(404);
                    continue;
                }
                assertThat(response.statusCode()).isEqualTo(200);
                var body = json.readTree(response.body());
                if (path.startsWith("/requests/")) {
                    assertThat(body.path("request").path("id").asText())
                            .isEqualTo(requestId.toString());
                    assertThat(body.path("members").size()).isEqualTo(3);
                } else if (path.startsWith("/work")) {
                    assertThat(body.path("attempts").size()).isEqualTo(1);
                    assertThat(body.path("owners").size()).isEqualTo(1);
                } else {
                    assertThat(body.size()).isEqualTo(changes.size());
                    assertThat(body.get(0).path("inventory").isArray()).isTrue();
                    assertThat(body.get(0).path("inventory").size()).isGreaterThan(1);
                }
            }
        }
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
        assertThat(count("atomic_work_requests")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sharedSectorJoinWaitsForEveryExactBranchAndPropagatesFailure(boolean failedBranch) {
        String sector = JobDefinitionConfig.ENABLED_SECTOR_CODES.getFirst();
        jdbc.update(
                "INSERT INTO symbols(code,exchange,is_active,meta_json) VALUES ('BBB','HOSE',true,jsonb_build_object('sectorLv2Code',?::text))",
                sector);
        var target = target(JobType.PRECOMPUTE_SECTOR_FEATURES);
        var scope = Map.<String, Object>of("scope", Map.of("type", "SECTOR", "key", sector));
        UUID first =
                preparation.prepareManual(
                        target,
                        claim(target),
                        NOW,
                        "operator",
                        "sector-owner-a",
                        "join",
                        scope,
                        Map.of());
        int attempts = count("atomic_work_attempts");
        UUID second =
                preparation.prepareManual(
                        target,
                        claim(target),
                        NOW,
                        "operator",
                        "sector-owner-b",
                        "join",
                        scope,
                        Map.of());
        assertThat(count("atomic_work_attempts")).isEqualTo(attempts);
        UUID sectorAttempt =
                jdbc.queryForObject(
                        "SELECT attempt_id FROM atomic_work_request_members WHERE request_id=? AND is_target",
                        UUID.class,
                        request(first));
        UUID execution =
                jdbc.queryForObject(
                        "SELECT execution_id FROM atomic_work_attempts WHERE id=?",
                        UUID.class,
                        sectorAttempt);
        var upstream =
                jdbc.queryForList(
                        "SELECT u.execution_id FROM atomic_work_dependencies d JOIN atomic_work_attempts u "
                                + "ON u.id=d.upstream_attempt_id WHERE d.attempt_id=? ORDER BY u.id",
                        UUID.class,
                        sectorAttempt);
        assertThat(upstream).hasSize(2);
        var bindings =
                jdbc.queryForList(
                        "SELECT upstream_attempt_id FROM atomic_work_dependencies WHERE attempt_id=? ORDER BY upstream_attempt_id",
                        UUID.class,
                        sectorAttempt);
        // Finish all other ancestors, leaving exactly one actual join branch incomplete.
        jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS' WHERE id IN "
                        + "(SELECT execution_id FROM atomic_work_attempts WHERE id<>?) AND id<>?",
                sectorAttempt,
                upstream.getLast());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT atomic_work_admissible(?)", Boolean.class, execution))
                .isFalse();
        graph.reconcile();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM atomic_work_requests WHERE id=?",
                                String.class,
                                request(second)))
                .isEqualTo("PREPARED");
        assertThat(graph.cancel(request(first), "operator", NOW)).isTrue();
        jdbc.update(
                "UPDATE job_execution_histories SET status=? WHERE id=?",
                failedBranch ? "ERROR" : "SUCCESS",
                upstream.getLast());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT atomic_work_admissible(?)", Boolean.class, execution))
                .isEqualTo(!failedBranch);
        if (!failedBranch) {
            jdbc.update(
                    "UPDATE job_execution_histories SET status='SUCCESS' WHERE id=?", execution);
        }
        for (int pass = 0; pass < 3; pass++) graph.reconcile();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                execution))
                .isEqualTo(failedBranch ? "BLOCKED" : "SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM atomic_work_requests WHERE id=?",
                                String.class,
                                request(second)))
                .isEqualTo(failedBranch ? "FAILED" : "SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM atomic_work_requests WHERE id=?",
                                String.class,
                                request(first)))
                .isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForList(
                                "SELECT upstream_attempt_id FROM atomic_work_dependencies WHERE attempt_id=? ORDER BY upstream_attempt_id",
                                UUID.class,
                                sectorAttempt))
                .isEqualTo(bindings);
        assertThat(count("atomic_work_attempts")).isEqualTo(attempts);
    }

    @Test
    void overlappingRequestsShareIndependentAncestorRunsAndCancellationKeepsOtherOwner() {
        var indicator = target(JobType.SYNC_INDICATORS);
        UUID first = prepare(indicator, "first"), second = prepare(indicator, "second");
        assertThat(second).isNotEqualTo(first);
        assertThat(count("atomic_work_attempts")).isEqualTo(3); // catalog, stock, indicator
        assertThat(count("job_runs")).isEqualTo(3);
        assertThat(count("atomic_work_request_members")).isEqualTo(6);
        assertThat(graph.cancel(request(first), "wrong-owner", NOW)).isFalse();
        assertThat(graph.cancel(request(first), "operator", NOW)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_attempts a WHERE atomic_work_admissible(a.execution_id)",
                                Integer.class))
                .isEqualTo(1);
        graph.reconcile();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM atomic_work_attempts a JOIN job_execution_histories e ON e.id=a.execution_id "
                        + "WHERE e.status IN ('FAILED','BLOCKED')", Integer.class)).isZero();
        assertThat(graph.inspect(request(second)).get("members")).asList().hasSize(3);
        assertThat(prepare(indicator, "second")).isEqualTo(second);
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
        assertThat(graph.cancel(request(second), "operator", NOW)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_attempts a WHERE atomic_work_admissible(a.execution_id)",
                                Integer.class))
                .isZero();
        prepare(indicator, "reattach");
        assertThat(count("atomic_work_attempts")).isEqualTo(6); // Closed owners cannot donate attempts.
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE atomic_work_request_members SET is_target=false WHERE request_id=?",
                                        request(second)))
                .hasMessageContaining("immutable");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE atomic_work_attempts SET upstream_attempt_ids='[]'"))
                .hasMessageContaining("immutable");
    }

    @Test
    void concurrentDifferentTargetsAcquireCommonAncestorsOnlyOnce() throws Exception {
        var indicator = target(JobType.SYNC_INDICATORS);
        var features = target(JobType.PRECOMPUTE_SYMBOL_FEATURES);
        var firstLease = claim(indicator);
        var secondLease = claim(features);
        var expansion =
                Map.<String, Object>of("scope", Map.of("type", "SYMBOL", "key", "HOSE-AAA"));
        var first =
                CompletableFuture.supplyAsync(
                        () ->
                                preparation.prepareManual(
                                        indicator,
                                        firstLease,
                                        NOW,
                                        "operator",
                                        "concurrent-a",
                                        "I9",
                                        expansion,
                                        Map.of()));
        var second =
                CompletableFuture.supplyAsync(
                        () ->
                                preparation.prepareManual(
                                        features,
                                        secondLease,
                                        NOW,
                                        "operator",
                                        "concurrent-b",
                                        "I9",
                                        expansion,
                                        Map.of()));
        CompletableFuture.allOf(first, second).get(90, TimeUnit.SECONDS);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_attempts a JOIN job_execution_histories e ON e.id=a.execution_id "
                                        + "JOIN job_definitions d ON d.id=e.job_id WHERE d.job_type='SYNC_STOCK_PRICE'",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT max(n) FROM (SELECT count(*) n FROM atomic_work_attempts GROUP BY work_id) x",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void barrierIsRecheckedAtClaimAndUpstreamFailurePropagatesWithoutChangingOldAttempts() {
        UUID execution = prepare(target(JobType.SYNC_INDICATORS), "failure");
        UUID downstream =
                jdbc.queryForObject(
                        "SELECT a.execution_id FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id WHERE m.request_id=? AND m.is_target",
                        UUID.class,
                        request(execution));
        UUID message =
                jdbc.queryForObject(
                        "SELECT id FROM scheduler_outbox_messages WHERE execution_id=?",
                        UUID.class,
                        downstream);
        assertThat(
                        context.getBean(SchedulerOutboxService.class)
                                .claimEligible(
                                        message, NOW, "test", Duration.ofMinutes(2), Map.of()))
                .isNull();
        jdbc.update(
                "UPDATE job_execution_histories SET status='FAILED',finished_at=? WHERE id IN "
                        + "(SELECT a.execution_id FROM atomic_work_attempts a WHERE a.upstream_attempt_ids='[]'::jsonb)",
                java.sql.Timestamp.from(NOW));
        for (int i = 0; i < 8; i++) graph.reconcile();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM atomic_work_requests WHERE execution_id=?",
                                String.class,
                                execution))
                .isEqualTo("FAILED");
        int old = count("atomic_work_attempts");
        var target = target(JobType.SYNC_INDICATORS);
        graph.retry(request(execution), "operator", "retry-new-key", claim(target), NOW);
        assertThat(count("atomic_work_attempts")).isGreaterThan(old);
        assertThat(prepare(target, "failure")).isEqualTo(execution);
    }

    @Test
    void completionAndFreshRequestDoNotMistakeSuccessForAnInfiniteFreshnessCache() {
        UUID first = prepare(target(JobType.SYNC_INDICATORS), "done");
        jdbc.update(
                "UPDATE job_execution_histories SET status='SUCCESS',finished_at=? WHERE id IN (SELECT execution_id FROM atomic_work_attempts)",
                java.sql.Timestamp.from(NOW));
        for (int i = 0; i < 3; i++) graph.reconcile();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM atomic_work_requests WHERE execution_id=?",
                                String.class,
                                first))
                .isEqualTo("SUCCESS");
        prepare(target(JobType.SYNC_INDICATORS), "refresh");
        assertThat(count("atomic_work_attempts")).isEqualTo(6);
        assertThat(count("scheduler_outbox_messages")).isEqualTo(6);
    }

    @Test
    void failedExpansionRollsBackAllDurableIntentAndPreservesClaimForRecovery() {
        var target = target(JobType.SYNC_INDICATORS);
        var lease = claim(target);
        assertThatThrownBy(
                        () ->
                                preparation.prepareManual(
                                        target,
                                        lease,
                                        NOW,
                                        "operator",
                                        "invalid",
                                        "I9",
                                        Map.of("dateFrom", "2035-02-01", "dateTo", "2035-02-01"),
                                        Map.of()))
                .hasMessageContaining("Invalid");
        assertThat(count("atomic_work_requests")).isZero();
        assertThat(count("atomic_work_attempts")).isZero();
        assertThat(count("job_runs")).isZero();
        assertThat(count("scheduler_outbox_messages")).isZero();
    }

    @Test
    void consolidationPreservesHistoricalOffsetsAndRollsBackWithoutRewindingCadence() {
        var service = context.getBean(DefinitionConsolidationService.class);
        var stock = target(JobType.SYNC_STOCK_PRICE);
        UUID history =
                jdbc.queryForObject(
                        "INSERT INTO job_execution_histories(job_id,used_source,status,meta_json,new_offset,finished_at) "
                                + "VALUES (?,'VND','SUCCESS','{\"workType\":\"SYMBOL\",\"workKey\":\"HOSE-AAA\"}','2035-01-01',now()) RETURNING id",
                        UUID.class,
                        stock.getId());
        var applied = service.consolidate(NOW);
        assertThat(applied).isNotEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_definitions WHERE NOT schedule_enabled",
                                Integer.class))
                .isPositive();
        UUID retained =
                jdbc.queryForObject(
                        "SELECT retained_definition_id FROM job_definition_history_aliases WHERE definition_id=?",
                        UUID.class,
                        stock.getId());
        assertThat(
                        context.getBean(JobExecutionHistoryRepository.class)
                                .findLastOffset(retained, "HOSE-AAA"))
                .contains("2035-01-01");
        String before =
                jdbc.queryForObject(
                        "SELECT job_id::text FROM job_execution_histories WHERE id=?",
                        String.class,
                        history);
        for (UUID id : applied) assertThat(service.rollback(id, NOW)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT job_id::text FROM job_execution_histories WHERE id=?",
                                String.class,
                                history))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_definitions WHERE canonical_definition_id IS NOT NULL",
                                Integer.class))
                .isZero();
    }

    @Test
    void expiredTokenCanPersistWaitingAndUncoverReadyRows() {
        prepare(target(JobType.SYNC_INDICATORS), "lease");
        UUID message =
                jdbc.queryForObject(
                        "SELECT o.id FROM scheduler_outbox_messages o JOIN atomic_work_attempts a ON a.execution_id=o.execution_id WHERE a.upstream_attempt_ids='[]'::jsonb",
                        UUID.class);
        jdbc.update(
                "UPDATE scheduler_outbox_messages SET claim_token=?,claimed_by='dead',claim_until=now()-interval '1 minute' WHERE id=?",
                UUID.randomUUID(),
                message);
        assertThat(
                        context.getBean(SchedulerOutboxService.class)
                                .markWaiting(message, NOW.plusSeconds(30), "WAITING_DATA"))
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT claim_token IS NULL AND dependency_reason='WAITING_DATA' FROM scheduler_outbox_messages WHERE id=?",
                                Boolean.class,
                                message))
                .isTrue();
        var metadata = target(JobType.SYNC_METADATA);
        UUID root =
                preparation.prepareManual(
                        metadata,
                        claim(metadata),
                        NOW,
                        "operator",
                        "ready-behind-waiting",
                        "I9",
                        Map.of(),
                        Map.of());
        UUID ready =
                jdbc.queryForObject(
                        "SELECT a.execution_id FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id WHERE m.request_id=? AND m.is_target",
                        UUID.class,
                        request(root));
        assertThat(context.getBean(SchedulerOutboxService.class).findCandidates(NOW, 1))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.executionId()).isEqualTo(ready));
    }

    @Test
    void expiredTokenCanPersistBlockedAndPropagateItsFailure() {
        UUID root = prepare(target(JobType.SYNC_INDICATORS), "blocked-lease");
        UUID message =
                jdbc.queryForObject(
                        "SELECT o.id FROM scheduler_outbox_messages o JOIN atomic_work_attempts a ON a.execution_id=o.execution_id WHERE a.upstream_attempt_ids='[]'::jsonb",
                        UUID.class);
        jdbc.update(
                "UPDATE scheduler_outbox_messages SET claim_token=?,claimed_by='dead',claim_until=now()-interval '1 minute' WHERE id=?",
                UUID.randomUUID(),
                message);
        assertThat(
                        context.getBean(SchedulerOutboxService.class)
                                .markBlocked(message, NOW, "NO_PROVIDER"))
                .isTrue();
        for (int i = 0; i < 8; i++) graph.reconcile();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM atomic_work_requests WHERE execution_id=?",
                                String.class,
                                root))
                .isEqualTo("FAILED");
    }

    @Test
    void rolledBackConsolidationRejectsNewerOperatorConfig() {
        var service = context.getBean(DefinitionConsolidationService.class);
        UUID change = service.consolidate(NOW).getFirst();
        UUID retained =
                jdbc.queryForObject(
                        "SELECT retained_definition_id FROM job_definition_consolidations WHERE id=?",
                        UUID.class,
                        change);
        jdbc.update(
                "UPDATE job_definitions SET config_json=config_json || '{\"sectorLevel\":1}'::jsonb WHERE id=?",
                retained);
        assertThatThrownBy(() -> service.rollback(change, NOW)).hasMessageContaining("conflict");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM job_definition_consolidations WHERE id=?",
                                String.class,
                                change))
                .isEqualTo("APPLIED");
    }

    @Test
    void requestKeyCannotSilentlyChangeAcceptedParameters() {
        var target = target(JobType.SYNC_INDICATORS);
        prepare(target, "fixed-key");
        var lease = claim(target);
        assertThatThrownBy(
                        () ->
                                preparation.prepareManual(
                                        target,
                                        lease,
                                        NOW,
                                        "operator",
                                        "fixed-key",
                                        "I9",
                                        Map.of(
                                                "scope",
                                                Map.of("type", "SYMBOL", "key", "HOSE-AAA"),
                                                "parameters",
                                                Map.of("indicatorSource", "close")),
                                        Map.of()))
                .hasMessageContaining("IDEMPOTENCY_CONFLICT");
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
    }

    @Test
    void restartReplaysStoredMembershipWithoutAnotherExpansion() {
        var target = target(JobType.SYNC_INDICATORS);
        UUID original = prepare(target, "restart-key");
        UUID request = request(original);
        stop();
        start();
        assertThat(graph.inspect(request).get("members")).asList().hasSize(3);
        assertThat(prepare(target(JobType.SYNC_INDICATORS), "restart-key")).isEqualTo(original);
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
    }

    @Test
    void workDiagnosticsRetainTheExistingAuthenticationBoundary() throws Exception {
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var response =
                    client.send(
                            java.net.http.HttpRequest.newBuilder(
                                            java.net.URI.create(
                                                    "http://127.0.0.1:"
                                                            + port
                                                            + "/api/v1/jobs/atomic-work/consolidations"))
                                    .GET()
                                    .build(),
                            java.net.http.HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isIn(401, 403);
        }
    }

    @Test
    void graphSchedulerClaimsAllUncoveredSeedStrategies() {
        jdbc.update("UPDATE job_definitions SET next_run=?", java.sql.Timestamp.from(NOW));
        var catalog = definitions.findAll();
        var expected =
                catalog.stream()
                        .filter(
                                definition ->
                                        definition.getJobType() != JobType.SYNC_SIGNALS
                                                || !"TREND_MOMENTUM_V1"
                                                        .equals(
                                                                definition
                                                                        .getConfigJson()
                                                                        .get("strategy")))
                        .map(JobDefinition::getId)
                        .toList();
        var selected = new ArrayList<SchedulerClaim>();
        int limit = context.getBean(SchedulerProperties.class).claim().batchSize();
        for (int page = 0; page < catalog.size(); page++) {
            var batch = claims.claimDueJobs(NOW);
            assertThat(batch).hasSizeLessThanOrEqualTo(limit);
            selected.addAll(batch);
            if (batch.isEmpty()) break;
        }
        assertThat(selected)
                .extracting(SchedulerClaim::jobDefinitionId)
                .containsExactlyInAnyOrderElementsOf(expected);
        var signals =
                selected.stream()
                        .map(lease -> definitions.findById(lease.jobDefinitionId()).orElseThrow())
                        .filter(definition -> definition.getJobType() == JobType.SYNC_SIGNALS)
                        .map(definition -> definition.getConfigJson().get("strategy"))
                        .toList();
        assertThat(signals).containsExactlyInAnyOrder("ICHIMOKU_V1", "CONFIRMED_TREND_EQUALS");
    }

    @Test
    void narrowIndicatorGraphDoesNotRemoveTheBroadStockSchedule() {
        var stock = target(JobType.SYNC_STOCK_PRICE);
        var indicator = target(JobType.SYNC_INDICATORS);
        jdbc.update("UPDATE job_definitions SET schedule_enabled=false");
        jdbc.update(
                "UPDATE job_definitions SET schedule_enabled=true,next_run=?,config_json=?::jsonb WHERE id=?",
                java.sql.Timestamp.from(NOW),
                "{\"exchanges\":[\"HOSE\"]}",
                stock.getId());
        jdbc.update(
                "UPDATE job_definitions SET schedule_enabled=true,next_run=?,config_json=?::jsonb WHERE id=?",
                java.sql.Timestamp.from(NOW),
                "{\"scope\":{\"type\":\"SYMBOL\",\"key\":\"HOSE-VCB\"}}",
                indicator.getId());
        jdbc.update("INSERT INTO symbols(code,exchange,is_active) VALUES ('VCB','HOSE',true)");
        var plan = preparation.planGraphExpansion(indicator.getId(), Map.of(), NOW);
        assertThat(plan.works())
                .filteredOn(work -> work.jobType() == JobType.SYNC_STOCK_PRICE)
                .isNotEmpty()
                .allSatisfy(work -> assertThat(work.workKey()).isEqualTo("HOSE-VCB"));
        assertThat(claims.claimDueJobs(NOW))
                .extracting(SchedulerClaim::jobDefinitionId)
                .containsExactlyInAnyOrder(stock.getId(), indicator.getId());
    }

    @Test
    void retryRequiresOwnedTerminalRequestAndANewKey() {
        var target = target(JobType.SYNC_INDICATORS);
        UUID root = prepare(target, "owned-retry");
        var lease = claim(target);
        assertThatThrownBy(() -> graph.retry(request(root), "intruder", "new-key", lease, NOW))
                .hasMessageContaining("owned terminal");
        assertThatThrownBy(() -> graph.retry(request(root), "operator", "new-key", lease, NOW))
                .hasMessageContaining("owned terminal");
        assertThat(graph.cancel(request(root), "operator", NOW)).isTrue();
        assertThatThrownBy(() -> graph.retry(request(root), "operator", "owned-retry", lease, NOW))
                .hasMessageContaining("new request key");
        UUID retried = graph.retry(request(root), "operator", "fresh-retry", lease, NOW);
        assertThat(retried).isNotEqualTo(root);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT meta_json->'requestMetadata'->>'retryOf' FROM job_execution_histories WHERE id=?",
                                String.class,
                                retried))
                .isEqualTo(request(root).toString());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM atomic_work_requests WHERE execution_id=?",
                                String.class,
                                root))
                .isEqualTo("CANCELLED");
    }

    @Test
    void changedTargetAndStaleClaimCannotReplayOrCreateAnyIntent() {
        var target = target(JobType.SYNC_INDICATORS);
        prepare(target, "target-bound");
        int before = count("atomic_work_requests");
        var other = target(JobType.PRECOMPUTE_SYMBOL_FEATURES);
        var otherLease = claim(other);
        assertThatThrownBy(
                        () ->
                                preparation.prepareManual(
                                        other,
                                        otherLease,
                                        NOW,
                                        "operator",
                                        "target-bound",
                                        "test",
                                        Map.of(
                                                "scope",
                                                Map.of("type", "SYMBOL", "key", "HOSE-AAA")),
                                        Map.of()))
                .hasMessageContaining("IDEMPOTENCY_CONFLICT");
        var valid = claim(target);
        var stale =
                new SchedulerClaim(
                        target.getId(),
                        UUID.randomUUID(),
                        valid.claimedBy(),
                        valid.claimedAt(),
                        valid.claimUntil(),
                        valid.scheduledFor());
        assertThatThrownBy(
                        () ->
                                preparation.prepareManual(
                                        target,
                                        stale,
                                        NOW,
                                        "operator",
                                        "stale-claim",
                                        "test",
                                        Map.of(),
                                        Map.of()))
                .hasMessageContaining("CLAIM_STALE");
        assertThat(count("atomic_work_requests")).isEqualTo(before);
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
    }

    @Test
    void consolidationRejectsActiveLeaseAndRollbackDoesNotOverrideOperatorState() {
        var service = context.getBean(DefinitionConsolidationService.class);
        var stock = target(JobType.SYNC_STOCK_PRICE);
        var activeLease = claim(stock);
        assertThatThrownBy(() -> service.consolidate(NOW)).hasMessageContaining("active lease");
        assertThat(count("job_definition_consolidations")).isZero();
        releaseLease(activeLease);
        UUID change = service.consolidate(NOW).getFirst();
        UUID retained =
                jdbc.queryForObject(
                        "SELECT retained_definition_id FROM job_definition_consolidations WHERE id=?",
                        UUID.class,
                        change);
        var rollbackLease = claim(definitions.findById(retained).orElseThrow());
        assertThatThrownBy(() -> service.rollback(change, NOW))
                .hasMessageContaining("identity/lease conflict");
        releaseLease(rollbackLease);
        jdbc.update("UPDATE job_definitions SET schedule_enabled=false WHERE id=?", retained);
        assertThatThrownBy(() -> service.rollback(change, NOW))
                .hasMessageContaining("identity/lease conflict");
        jdbc.update("UPDATE job_definitions SET schedule_enabled=true WHERE id=?", retained);
        assertThat(service.rollback(change, NOW)).isTrue();
        assertThat(service.rollback(change, NOW)).isFalse();
        assertThat(service.rollback(UUID.randomUUID(), NOW)).isFalse();
    }

    @Test
    void overlappingDateRangesShareOnlyTheirIntersectionAndReplayKeepsCancelledMembership() {
        var target = target(JobType.SYNC_INDICATORS);
        var firstRange =
                Map.<String, Object>of(
                        "scope",
                        Map.of("type", "SYMBOL", "key", "HOSE-AAA"),
                        "dateFrom",
                        "2034-12-29",
                        "dateTo",
                        "2035-01-01");
        var secondRange =
                Map.<String, Object>of(
                        "scope",
                        Map.of("type", "SYMBOL", "key", "HOSE-AAA"),
                        "dateFrom",
                        "2035-01-01",
                        "dateTo",
                        "2035-01-02");
        UUID first =
                preparation.prepareManual(
                        target,
                        claim(target),
                        NOW,
                        "operator",
                        "range-a",
                        "overlap",
                        firstRange,
                        Map.of());
        UUID second =
                preparation.prepareManual(
                        target,
                        claim(target),
                        NOW,
                        "operator",
                        "range-b",
                        "overlap",
                        secondRange,
                        Map.of());
        assertThat(count("atomic_work_attempts")).isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM (SELECT attempt_id FROM atomic_work_request_members "
                                        + "GROUP BY attempt_id HAVING count(DISTINCT request_id)=2) shared",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_request_members WHERE request_id=?",
                                Integer.class,
                                request(first)))
                .isEqualTo(5);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_request_members WHERE request_id=?",
                                Integer.class,
                                request(second)))
                .isEqualTo(5);
        String accepted =
                jdbc.queryForObject(
                        "SELECT graph_json::text FROM atomic_work_requests WHERE id=?",
                        String.class,
                        request(first));
        assertThat(graph.cancel(request(first), "operator", NOW)).isTrue();
        jdbc.update("INSERT INTO symbols(code,exchange,is_active) VALUES ('NEW','HOSE',true)");
        jdbc.update(
                "UPDATE job_definitions SET config_json=config_json || '{\"timeframe\":\"1h\"}'::jsonb WHERE id=?",
                target.getId());
        assertThat(
                        preparation.prepareManual(
                                target,
                                claim(target),
                                NOW,
                                "operator",
                                "range-a",
                                "overlap",
                                firstRange,
                                Map.of()))
                .isEqualTo(first);
        assertThat(count("atomic_work_attempts")).isEqualTo(7);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT graph_json::text FROM atomic_work_requests WHERE id=?",
                                String.class,
                                request(first)))
                .isEqualTo(accepted);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM atomic_work_requests WHERE id=?",
                                String.class,
                                request(first)))
                .isEqualTo("CANCELLED");
    }

    @Test
    void noOwnerWorkStaysInadmissibleAndANewRequestCreatesIndependentAttempts() {
        var target = target(JobType.SYNC_INDICATORS);
        UUID first = prepare(target, "parking-first"), second = prepare(target, "parking-second");
        assertThat(graph.cancel(request(first), "operator", NOW)).isTrue();
        assertThat(graph.cancel(request(second), "operator", NOW)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_attempts WHERE atomic_work_admissible(execution_id)",
                                Integer.class))
                .isZero();
        UUID catalogExecution =
                jdbc.queryForObject(
                        "SELECT a.execution_id FROM atomic_work_attempts a JOIN job_execution_histories h ON h.id=a.execution_id JOIN job_definitions d ON d.id=h.job_id WHERE d.job_type='SYNC_SYMBOLS'",
                        UUID.class);
        UUID message =
                jdbc.queryForObject(
                        "SELECT id FROM scheduler_outbox_messages WHERE execution_id=?",
                        UUID.class,
                        catalogExecution);
        assertThat(
                        context.getBean(SchedulerOutboxService.class)
                                .claimEligible(
                                        message, NOW, "parked", Duration.ofMinutes(1), Map.of()))
                .isNull();
        UUID replacement = prepare(target, "parking-new-owner");
        assertThat(replacement).isNotIn(first, second);
        assertThat(count("atomic_work_attempts")).isEqualTo(6);
        assertThat(count("scheduler_outbox_messages")).isEqualTo(6);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT atomic_work_admissible(?)",
                                Boolean.class,
                                catalogExecution))
                .isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM atomic_work_attempts WHERE atomic_work_admissible(execution_id)",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void retainedDataRolloutRehearsalDrainsGraphAndRestoresLegacyWithoutRewritingHistory()
            throws Exception {
        // This seeds terminal fixture state to test control-plane cutover. Actual worker
        // completion/offsets are independently exercised by the four real-worker workflows.
        stop();
        start(false);
        try {
            UUID legacy = prepare(target(JobType.SYNC_INDICATORS), "rollout-retained-v1");
            UUID legacyRun =
                    jdbc.queryForObject(
                            "SELECT job_run_id FROM job_execution_histories WHERE id=?",
                            UUID.class,
                            legacy);
            jdbc.update("UPDATE job_execution_histories SET status='SUCCESS'");
            jdbc.update(
                    "UPDATE scheduler_outbox_messages SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP");
            jdbc.update("UPDATE job_runs SET state='SUCCESS'");
            String frozen =
                    jdbc.queryForObject(
                            "SELECT to_jsonb(r)::text FROM job_runs r WHERE id=?",
                            String.class,
                            legacyRun);
            stop();
            start(true);
            var consolidation = context.getBean(DefinitionConsolidationService.class);
            var changes = consolidation.consolidate(NOW);
            assertThat(changes).isNotEmpty();
            String sector = JobDefinitionConfig.ENABLED_SECTOR_CODES.getFirst();
            for (int index = 0; index < 99; index++) {
                jdbc.update(
                        "INSERT INTO symbols(code,exchange,is_active,meta_json) VALUES (?,'HOSE',true,jsonb_build_object('sectorLv2Code',?::text))",
                        "R" + index,
                        sector);
            }
            var target = target(JobType.SYNC_INDICATORS);
            Instant began = Instant.now();
            UUID graphRoot =
                    preparation.prepareManual(
                            target,
                            claim(target),
                            NOW,
                            "operator",
                            "rollout-wide",
                            "bounded catalog rehearsal",
                            Map.of(),
                            Map.of());
            long elapsed = Duration.between(began, Instant.now()).toMillis();
            assertThat(elapsed)
                    .isLessThan(
                            110_000); // Below the real 120-second preparation transaction budget.
            int attempts = count("atomic_work_attempts");
            assertThat(attempts).isGreaterThanOrEqualTo(201);
            assertThat(
                            preparation.prepareManual(
                                    target,
                                    claim(target),
                                    NOW,
                                    "operator",
                                    "rollout-wide",
                                    "replay",
                                    Map.of(),
                                    Map.of()))
                    .isEqualTo(graphRoot);
            assertThat(count("atomic_work_attempts")).isEqualTo(attempts);
            jdbc.update(
                    "UPDATE job_execution_histories SET status='SUCCESS' WHERE id IN (SELECT execution_id FROM atomic_work_attempts)");
            jdbc.update(
                    "UPDATE scheduler_outbox_messages SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP");
            for (int pass = 0; pass < 6; pass++) graph.reconcile();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT state FROM atomic_work_requests WHERE execution_id=?",
                                    String.class,
                                    graphRoot))
                    .isEqualTo("SUCCESS");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM job_runs WHERE state NOT IN ('SUCCESS','FAILED')",
                                    Integer.class))
                    .isZero();
            for (UUID id : changes) assertThat(consolidation.rollback(id, NOW)).isTrue();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT to_jsonb(r)::text FROM job_runs r WHERE id=?",
                                    String.class,
                                    legacyRun))
                    .isEqualTo(frozen);
            stop();
            start(false);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT to_jsonb(r)::text FROM job_runs r WHERE id=?",
                                    String.class,
                                    legacyRun))
                    .isEqualTo(frozen);
            int before = count("atomic_work_attempts");
            UUID fallback = prepare(target(JobType.SYNC_INDICATORS), "rollout-legacy-after-drain");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT job_run_id FROM job_execution_histories WHERE id=?",
                                    UUID.class,
                                    fallback))
                    .isNotNull();
            assertThat(count("atomic_work_attempts")).isEqualTo(before);
            var evidence =
                    Map.of(
                            "catalogSymbols",
                            100,
                            "atomicAttempts",
                            attempts,
                            "preparationMs",
                            elapsed,
                            "consolidationsRolledBack",
                            changes.size(),
                            "legacyFrozenSnapshotPreserved",
                            true,
                            "legacyAfterDrainCreated",
                            true);
            var path =
                    java.nio.file.Path.of(
                            "../../.agent/check-results/P14-I8-I9-close-20261101/local-rollout.json");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(
                    path,
                    context.getBean("jsonMapper", JsonMapper.class).writeValueAsString(evidence));
        } finally {
            stop();
            start(true);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void outputRegistrationShadowIsAtomicIdempotentAndCannotChangeExecutionReadiness() {
        prepare(target(JobType.SYNC_STOCK_PRICE), "registration-shadow");
        var row =
                jdbc.queryForMap(
                        "SELECT a.id,a.execution_id,a.work_id,e.meta_json::text AS metadata "
                                + "FROM atomic_work_attempts a JOIN job_execution_histories e ON e.id=a.execution_id "
                                + "WHERE e.meta_json->'workExpansion'->>'jobType'='SYNC_STOCK_PRICE' LIMIT 1");
        var mapper = context.getBean("jsonMapper", JsonMapper.class);
        Map<String, Object> metadata = mapper.readValue(row.get("metadata").toString(), Map.class);
        Map<String, Object> expansion = (Map<String, Object>) metadata.get("workExpansion");
        var item = new java.util.LinkedHashMap<String, Object>();
        item.put("registrationId", UUID.randomUUID().toString());
        item.put("executionId", row.get("execution_id").toString());
        item.put("atomicAttemptId", row.get("id").toString());
        item.put("atomicWorkId", row.get("work_id"));
        for (String field : List.of("jobType", "workType", "workKey", "businessDate", "parameters"))
            item.put(field, expansion.get(field));
        item.put("requiredOutputs", List.of());
        item.put("outputs", List.of());
        item.put("validEmptyReason", "NO_PROVIDER_OUTPUT");
        var event = new java.util.LinkedHashMap<String, Object>();
        event.put("schemaVersion", 1);
        event.put("eventId", UUID.randomUUID().toString());
        event.put("registrations", List.of(item));
        var store =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class);
        String before =
                jdbc.queryForObject(
                        "SELECT status FROM job_execution_histories WHERE id=?",
                        String.class,
                        row.get("execution_id"));
        int originalEvents = count("output_registration_events");
        int originalMembers = count("output_registrations");
        String payload = mapper.writeValueAsString(event);
        store.accept(payload);
        store.accept(payload);
        assertThat(count("output_registration_events")).isEqualTo(originalEvents + 1);
        assertThat(count("output_registrations")).isEqualTo(originalMembers + 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM job_execution_histories WHERE id=?",
                                String.class,
                                row.get("execution_id")))
                .isEqualTo(before);

        item.put("validEmptyReason", "CHANGED_IMMUTABLE_ITEM");
        assertThatThrownBy(() -> store.accept(mapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class);
        event.put("eventId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> store.accept(mapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("output_registration_events")).isEqualTo(originalEvents + 1);

        item.put("validEmptyReason", "NO_PROVIDER_OUTPUT");
        var unknown = new java.util.LinkedHashMap<>(item);
        unknown.put("registrationId", UUID.randomUUID().toString());
        unknown.put("executionId", UUID.randomUUID().toString());
        unknown.put("atomicAttemptId", UUID.randomUUID().toString());
        event.put("registrations", List.of(item, unknown));
        assertThatThrownBy(() -> store.accept(mapper.writeValueAsString(event)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("output_registration_events")).isEqualTo(originalEvents + 1);
        assertThat(count("output_registrations")).isEqualTo(originalMembers + 1);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "DELETE FROM output_registrations WHERE execution_id=?",
                                        row.get("execution_id")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    private record CompletionFixture(
            UUID execution,
            UUID attempt,
            UUID parent,
            UUID job,
            String event,
            com.omni.platform.modules.scheduler.messaging.JobStatusMessage status) {}

    @SuppressWarnings("unchecked")
    private CompletionFixture completionFixture(String key, boolean empty) {
        return completionFixture(key, empty, target(JobType.SYNC_STOCK_PRICE));
    }

    @SuppressWarnings("unchecked")
    private CompletionFixture completionFixture(
            String key, boolean empty, JobDefinition requested) {
        prepare(requested, key);
        var row =
                jdbc.queryForMap(
                        "SELECT a.id,a.execution_id,a.work_id,e.parent_log_id,e.job_id,e.meta_json::text AS metadata "
                                + "FROM atomic_work_attempts a JOIN job_execution_histories e ON e.id=a.execution_id "
                                + "WHERE e.meta_json->'workExpansion'->>'jobType'='SYNC_STOCK_PRICE' "
                                + "AND EXISTS(SELECT 1 FROM atomic_work_request_members m JOIN atomic_work_requests r ON r.id=m.request_id "
                                + "WHERE m.attempt_id=a.id AND r.idempotency_key=?) LIMIT 1", key);
        var mapper = context.getBean("jsonMapper", JsonMapper.class);
        Map<String, Object> metadata = mapper.readValue(row.get("metadata").toString(), Map.class);
        Map<String, Object> expansion = (Map<String, Object>) metadata.get("workExpansion");
        var logical =
                new com.omni.platform.modules.scheduler.metadata.OutputRegistrationEvent
                        .LogicalOutput("eod", Map.of("exchange", "hose", "code", "aaa"));
        UUID execution = (UUID) row.get("execution_id");
        UUID attempt = (UUID) row.get("id");
        context.getBean(com.omni.platform.modules.scheduler.metadata.OutputCompletionGate.class)
                .enroll(execution, attempt, List.of(logical), List.of("TEST_VALID_EMPTY"));
        var item = new java.util.LinkedHashMap<String, Object>();
        item.put("registrationId", UUID.randomUUID().toString());
        item.put("executionId", execution.toString());
        item.put("atomicAttemptId", attempt.toString());
        item.put("atomicWorkId", row.get("work_id"));
        for (String field : List.of("jobType", "workType", "workKey", "businessDate", "parameters"))
            item.put(field, expansion.get(field));
        item.put("requiredOutputs", empty ? List.of() : List.of(logical));
        item.put("validEmptyReason", empty ? "TEST_VALID_EMPTY" : null);
        item.put(
                "outputs",
                empty
                        ? List.of()
                        : List.of(
                                new java.util.LinkedHashMap<>(
                                        Map.of(
                                                "dataset",
                                                "eod",
                                                "partition",
                                                logical.partition(),
                                                "dataVersion",
                                                "sha256:" + "a".repeat(64),
                                                "coverageDates",
                                                List.of("2035-01-02"),
                                                "rowCount",
                                                1,
                                                "totalBytes",
                                                128,
                                                "schemaVersion",
                                                1,
                                                "schemaHash",
                                                "sha256:" + "b".repeat(64),
                                                "columns",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "date",
                                                                "type",
                                                                "DATE",
                                                                "nullable",
                                                                false)),
                                                "inputs",
                                                List.of()))));
        if (!empty)
            ((Map<String, Object>) ((List<?>) item.get("outputs")).getFirst())
                    .put(
                            "workScope",
                            Map.of(
                                    "atomicWorkId",
                                    row.get("work_id"),
                                    "rowKeys",
                                    List.of(Map.of("date", "2035-01-02"))));
        String event =
                mapper.writeValueAsString(
                        Map.of(
                                "schemaVersion",
                                1,
                                "eventId",
                                UUID.randomUUID().toString(),
                                "registrations",
                                List.of(item)));
        var status =
                new com.omni.platform.modules.scheduler.messaging.JobStatusMessage(
                        row.get("job_id").toString(),
                        execution.toString(),
                        row.get("parent_log_id").toString(),
                        com.omni.platform.shared.executions.WorkType.SYMBOL,
                        expansion.get("workKey").toString(),
                        "SUCCESS",
                        Map.of(),
                        null,
                        NOW,
                        NOW.plusSeconds(1),
                        1000,
                        null,
                        1);
        return new CompletionFixture(
                execution,
                attempt,
                (UUID) row.get("parent_log_id"),
                (UUID) row.get("job_id"),
                event,
                status);
    }

    private String completionState(UUID execution) {
        return jdbc.queryForObject(
                "SELECT status FROM job_execution_histories WHERE id=?", String.class, execution);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completionRequiresBothStreamsInEitherOrderAndRetainsReplay(boolean statusFirst) {
        var f = completionFixture("completion-order", false);
        var jobs = context.getBean(JobService.class);
        var store =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class);
        if (statusFirst) jobs.applyStatus(f.status());
        else store.accept(f.event());
        assertThat(completionState(f.execution())).isIn("PENDING", "RUNNING");
        if (statusFirst) {
            assertThat(count("output_completion_statuses")).isEqualTo(1);
            store.accept(f.event());
        } else jobs.applyStatus(f.status());
        assertThat(completionState(f.execution())).isEqualTo("SUCCESS");
        assertThat(completionState(f.parent())).isEqualTo("SUCCESS");
        jobs.applyStatus(f.status());
        store.accept(f.event());
        assertThat(count("output_completion_statuses")).isEqualTo(1);
        assertThat(count("output_registrations")).isEqualTo(1);
        assertThat(count("output_registration_outputs")).isEqualTo(1);
        assertThat(count("output_registration_coverage")).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationAndStatusConvergeOnOneCompletion() throws Exception {
        var f = completionFixture("completion-concurrent", false);
        var jobs = context.getBean(JobService.class);
        var store =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var status =
                CompletableFuture.runAsync(
                        () -> {
                            ready.countDown();
                            try {
                                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                            jobs.applyStatus(f.status());
                        });
        var registration =
                CompletableFuture.runAsync(
                        () -> {
                            ready.countDown();
                            try {
                                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                            store.accept(f.event());
                        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        CompletableFuture.allOf(status, registration).get(30, TimeUnit.SECONDS);
        assertThat(completionState(f.execution())).isEqualTo("SUCCESS");
        assertThat(count("output_completion_statuses")).isEqualTo(1);
        assertThat(count("output_registrations")).isEqualTo(1);
    }

    @Test
    void validEmptyCompletesOnlyForAnApprovedServerReason() {
        var f = completionFixture("completion-empty", true);
        var store =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class);
        assertThatThrownBy(() -> store.accept(f.event().replace("TEST_VALID_EMPTY", "UNAPPROVED")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("output_registration_events")).isZero();
        context.getBean(JobService.class).applyStatus(f.status());
        store.accept(f.event());
        assertThat(completionState(f.execution())).isEqualTo("SUCCESS");
        // Registration acceptance alone is not READY; valid-empty supplies no output coverage.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT jsonb_array_length(payload->'outputs') FROM output_registrations "
                                        + "WHERE execution_id=?",
                                Integer.class,
                                f.execution()))
                .isZero();
    }

    @Test
    void workerCannotReplaceServerRequiredOutputs() {
        var f = completionFixture("completion-declaration", false);
        var store =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class);
        assertThatThrownBy(() -> store.accept(f.event().replace("\"eod\"", "\"signals\"")))
                .isInstanceOf(RuntimeException.class);
        assertThat(count("output_registration_events")).isZero();
        assertThat(count("output_registrations")).isZero();
    }

    @Test
    void invalidParentCannotCreatePendingCompletionEvidence() {
        var f = completionFixture("completion-parent", false);
        var s = f.status();
        var wrong =
                new com.omni.platform.modules.scheduler.messaging.JobStatusMessage(
                        s.jobDefinitionId(),
                        s.executionId(),
                        UUID.randomUUID().toString(),
                        s.workType(),
                        s.workKey(),
                        s.status(),
                        s.metaJson(),
                        s.newOffset(),
                        s.startedAt(),
                        s.finishedAt(),
                        s.durationMs(),
                        null,
                        1);
        assertThatThrownBy(() -> context.getBean(JobService.class).applyStatus(wrong))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(count("output_completion_statuses")).isZero();
    }

    @Test
    void watchdogUsesPublishedDeadlineAndLateStreamsCannotReopenTimeout() {
        var f = completionFixture("completion-timeout", false);
        var jobs = context.getBean(JobService.class);
        jobs.applyStatus(f.status());
        var watchdog =
                new com.omni.platform.modules.scheduler.metadata.ExecutionTimeoutWatchdog(
                        jdbc,
                        context.getBean("jsonMapper", JsonMapper.class),
                        jobs,
                        context.getBean(PlatformTransactionManager.class),
                        Duration.ofMinutes(30),
                        120000,
                        10);
        assertThat(watchdog.scan(NOW.plus(Duration.ofDays(1))))
                .isZero(); // Unpublished WAITING is excluded.
        jdbc.update(
                "UPDATE scheduler_outbox_messages SET status='PUBLISHED',published_at=? WHERE execution_id=?",
                java.sql.Timestamp.from(NOW),
                f.execution());
        assertThat(watchdog.scan(NOW.plusSeconds(1799))).isZero();
        assertThat(watchdog.scan(NOW.plusSeconds(1800))).isEqualTo(1);
        assertThat(completionState(f.execution())).isEqualTo("FAILED");
        assertThat(completionState(f.parent())).isEqualTo("FAILED");
        assertThat(watchdog.scan(NOW.plusSeconds(1801))).isZero();
        context.getBean(com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class)
                .accept(f.event());
        jobs.applyStatus(f.status());
        assertThat(completionState(f.execution())).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT ownership_state FROM output_attempt_timeouts WHERE execution_id=?",
                                String.class,
                                f.execution()))
                .isEqualTo("UNCERTAIN");
    }

    @Test
    void productionEnrollmentFreezesIdsBeforeOutboxCanDispatch() {
        var original =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputDeclarations.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                graph,
                "outputDeclarations",
                new com.omni.platform.modules.scheduler.metadata.OutputDeclarations(true, true));
        try {
            prepare(target(JobType.SYNC_STOCK_PRICE), "enrolled-production");
            assertThat(count("output_completion_contracts")).isEqualTo(1);
            var row =
                    jdbc.queryForMap(
                            "SELECT o.payload::jsonb->'metadata'->'outputCompletion' AS declaration,o.status,c.atomic_attempt_id "
                                    + "FROM scheduler_outbox_messages o JOIN output_completion_contracts c ON c.execution_id=o.execution_id");
            assertThat(row.get("declaration").toString())
                    .contains("eventId", "registrationId", row.get("atomic_attempt_id").toString());
            assertThat(row.get("status")).isEqualTo("PENDING");
            var first =
                    jdbc.queryForObject(
                            "SELECT execution_id FROM output_completion_contracts", UUID.class);
            var admission =
                    new com.omni.platform.modules.scheduler.metadata.OutputWriteAdmission(
                            jdbc, true);
            assertThat(admission.waiting(first)).isFalse();
            var other =
                    jdbc.queryForMap(
                            "SELECT a.id,a.execution_id FROM atomic_work_attempts a "
                                    + "WHERE a.execution_id<>? LIMIT 1",
                            first);
            var logical =
                    new com.omni.platform.modules.scheduler.metadata.OutputRegistrationEvent
                            .LogicalOutput("eod", Map.of("exchange", "hose", "code", "aaa"));
            context.getBean(com.omni.platform.modules.scheduler.metadata.OutputCompletionGate.class)
                    .enroll(
                            (UUID) other.get("execution_id"),
                            (UUID) other.get("id"),
                            List.of(logical),
                            List.of());
            assertThat(admission.waiting((UUID) other.get("execution_id"))).isTrue();
            jdbc.update("UPDATE job_execution_histories SET status='FAILED' WHERE id=?", first);
            assertThat(admission.waiting((UUID) other.get("execution_id"))).isFalse();
            jdbc.update(
                    "INSERT INTO output_attempt_timeouts(execution_id,published_at,timed_out_at) VALUES (?,?,?)",
                    first,
                    java.sql.Timestamp.from(NOW),
                    java.sql.Timestamp.from(NOW.plusSeconds(1800)));
            assertThat(admission.waiting((UUID) other.get("execution_id"))).isTrue();
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    graph, "outputDeclarations", original);
        }
    }

    @Test
    void baselineHasNullProvenanceAndCannotReplaceRegisteredReady() {
        var mapper = context.getBean("jsonMapper", JsonMapper.class);
        var backfill =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputBackfillService.class);
        var provider =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputReadinessProvider.class);
        var partition = Map.of("exchange", "hose", "code", "aaa");
        var output =
                new com.omni.platform.modules.scheduler.metadata.OutputRegistrationEvent.Output(
                        "eod",
                        partition,
                        "sha256:" + "c".repeat(64),
                        List.of(java.time.LocalDate.of(2035, 1, 2)),
                        1,
                        128,
                        1,
                        "sha256:" + "b".repeat(64),
                        List.of(
                                new com.omni.platform.modules.scheduler.metadata
                                        .OutputRegistrationEvent.Column("date", "DATE", false)),
                        List.of());
        long fence = backfill.begin();
        backfill.commit(
                new com.omni.platform.modules.scheduler.metadata.OutputBackfillService.Baseline(
                        fence, UUID.randomUUID(), output));
        var ref = com.omni.platform.modules.scheduler.dependencies.DatasetRef.of("eod", partition);
        assertThat(provider.readManifest(ref).orElseThrow().sourceExecutionId()).isNull();
        assertThat(
                        provider.coversDate(
                                ref, output.dataVersion(), java.time.LocalDate.of(2035, 1, 2)))
                .isTrue();
        assertThat(
                        provider.coversDate(
                                ref, output.dataVersion(), java.time.LocalDate.of(2035, 1, 3)))
                .isFalse();
        var f = completionFixture("baseline-precedence", false);
        assertThatThrownBy(
                        () ->
                                backfill.commit(
                                        new com.omni.platform.modules.scheduler.metadata
                                                .OutputBackfillService.Baseline(
                                                fence, UUID.randomUUID(), output)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation changed");
        context.getBean(com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class)
                .accept(f.event());
        context.getBean(JobService.class).applyStatus(f.status());
        var promotion =
                new com.omni.platform.modules.scheduler.metadata.OutputReadinessStore(
                        jdbc, true, true);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(tx -> promotion.promote(f.execution()));
        assertThat(provider.readManifest(ref).orElseThrow().sourceExecutionId())
                .isEqualTo(f.execution().toString());
        var workId =
                jdbc.queryForObject(
                        "SELECT work_id FROM atomic_work_attempts WHERE id=?",
                        String.class,
                        f.attempt());
        var identities =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputWorkReadiness.class);
        assertThat(identities.read(workId))
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item)
                                    .containsEntry("atomicWorkId", workId)
                                    .containsEntry("businessDate", "2035-01-02")
                                    .containsEntry("atomicAttemptId", f.attempt().toString())
                                    .containsEntry("dataVersion", "sha256:" + "a".repeat(64));
                        });
        assertThat(identities.read("work:" + "f".repeat(64))).isEmpty();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM output_work_ready WHERE atomic_work_id=?",
                                Integer.class,
                                workId))
                .isEqualTo(1);
        // A frozen approval whose upstream work/version is unavailable cannot inherit partition
        // READY.
        String before =
                jdbc.queryForObject(
                        "SELECT meta_json::text FROM job_execution_histories WHERE id=?",
                        String.class,
                        f.execution());
        var stale =
                Map.of(
                        "dataset",
                        "eod",
                        "partition",
                        partition,
                        "dataVersion",
                        "sha256:" + "f".repeat(64),
                        "atomicWorkId",
                        "work:" + "f".repeat(64),
                        "businessDate",
                        "2035-01-02",
                        "atomicAttemptId",
                        UUID.randomUUID().toString());
        jdbc.update(
                "UPDATE job_execution_histories SET meta_json=jsonb_set(meta_json,'{approvedInputs}',?::jsonb) WHERE id=?",
                mapper.writeValueAsString(List.of(stale)),
                f.execution());
        // Provenance is separate from availability: changing an approval does not erase output
        // rows.
        assertThat(identities.read(workId)).hasSize(1);
        jdbc.update(
                "UPDATE job_execution_histories SET meta_json=?::jsonb WHERE id=?",
                before,
                f.execution());
        assertThat(identities.read(workId)).hasSize(1);
        jdbc.update(
                "DELETE FROM output_ready_pointers WHERE dataset='eod' AND partition_values=?::jsonb",
                mapper.writeValueAsString(partition));
        assertThat(identities.read(workId))
                .hasSize(1); // Physical catalog pointer is not work READY authority.
        jdbc.update(
                "DELETE FROM output_ready_rows WHERE dataset='eod' AND partition_values=?::jsonb",
                mapper.writeValueAsString(partition));
        assertThat(identities.read(workId)).isEmpty();
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(tx -> promotion.promote(f.execution()));
        assertThat(identities.read(workId)).hasSize(1);
        var readyRow =
                jdbc.queryForMap("SELECT * FROM output_work_ready WHERE atomic_work_id=?", workId);
        jdbc.update(
                "INSERT INTO output_registration_outputs(registration_id,output_index,dataset,partition_values,data_version,payload) "
                        + "SELECT registration_id,1,dataset,partition_values,data_version,payload FROM output_registration_outputs WHERE registration_id=? AND output_index=0",
                readyRow.get("registration_id"));
        jdbc.update(
                "INSERT INTO output_ready_rows(dataset,partition_values,row_key,registration_id,output_index,generation) "
                        + "VALUES ('eod',?::jsonb,'{\"date\":\"2035-01-03\"}'::jsonb,?,1,999)",
                mapper.writeValueAsString(partition),
                readyRow.get("registration_id"));
        assertThat(identities.read(workId)).hasSize(1); // Another date cannot invalidate this work.
        jdbc.update(
                "UPDATE output_ready_rows SET output_index=1 WHERE dataset='eod' AND partition_values=?::jsonb "
                        + "AND row_key='{\"date\":\"2035-01-02\"}'::jsonb",
                mapper.writeValueAsString(partition));
        assertThat(identities.read(workId)).isEmpty(); // Its own coordinate was superseded.
        jdbc.update(
                "UPDATE output_ready_rows SET output_index=0 WHERE dataset='eod' AND partition_values=?::jsonb "
                        + "AND row_key='{\"date\":\"2035-01-02\"}'::jsonb",
                mapper.writeValueAsString(partition));
        backfill.commit(
                new com.omni.platform.modules.scheduler.metadata.OutputBackfillService.Baseline(
                        backfill.begin(), UUID.randomUUID(), output));
        assertThat(provider.readManifest(ref).orElseThrow().dataVersion())
                .isEqualTo("sha256:" + "a".repeat(64));
    }

    @Test
    void scopedCompletionRejectsMissingScopeAndUnapprovedInputsBeforeMutation() {
        var fixture = completionFixture("scope-contract", false);
        var mapper = context.getBean("jsonMapper", JsonMapper.class);
        var store =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class);
        for (String change : List.of("missing-scope", "other-date", "unapproved-input")) {
            var root = (tools.jackson.databind.node.ObjectNode) mapper.readTree(fixture.event());
            var output =
                    (tools.jackson.databind.node.ObjectNode)
                            root.get("registrations").get(0).get("outputs").get(0);
            if (change.equals("missing-scope")) output.remove("workScope");
            if (change.equals("other-date")) {
                output.set("coverageDates", mapper.valueToTree(List.of("2035-01-03")));
                ((tools.jackson.databind.node.ObjectNode)
                                output.get("workScope").get("rowKeys").get(0))
                        .put("date", "2035-01-03");
            }
            if (change.equals("unapproved-input"))
                output.set(
                        "inputs",
                        mapper.valueToTree(
                                List.of(
                                        Map.of(
                                                "dataset",
                                                "eod",
                                                "partition",
                                                Map.of("exchange", "hose", "code", "aaa"),
                                                "dataVersion",
                                                "sha256:" + "d".repeat(64)))));
            assertThatThrownBy(() -> store.accept(mapper.writeValueAsString(root)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(count("output_registration_events")).isZero();
            assertThat(count("output_registrations")).isZero();
        }
    }

    @Test
    void frozenInputBindingAndPrivateCatalogUseWorkRowsAfterThePhysicalPointerChanges() {
        var f = completionFixture("frozen-row-proof", false, target(JobType.SYNC_INDICATORS));
        var child =
                jdbc.queryForMap(
                        "SELECT a.id,a.execution_id FROM atomic_work_attempts a JOIN job_execution_histories e ON e.id=a.execution_id "
                                + "WHERE e.meta_json->'workExpansion'->>'jobType'='SYNC_INDICATORS' LIMIT 1");
        var partition = Map.of("exchange", "hose", "code", "aaa");
        context.getBean(com.omni.platform.modules.scheduler.metadata.OutputCompletionGate.class)
                .enroll(
                        (UUID) child.get("execution_id"),
                        (UUID) child.get("id"),
                        List.of(
                                new com.omni.platform.modules.scheduler.metadata
                                        .OutputRegistrationEvent.LogicalOutput(
                                        "indicators",
                                        Map.of(
                                                "exchange",
                                                "hose",
                                                "code",
                                                "aaa",
                                                "source",
                                                "ad_close",
                                                "timeframe",
                                                "1d"))),
                        List.of());
        context.getBean(com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class)
                .accept(f.event());
        context.getBean(JobService.class).applyStatus(f.status());
        var promotion =
                new com.omni.platform.modules.scheduler.metadata.OutputReadinessStore(
                        jdbc, true, true);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(tx -> promotion.promote(f.execution()));
        var provider =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputReadinessProvider.class);
        var page = provider.page(0, 200, null);
        assertThat((List<?>) page.get("items")).hasSize(1);
        assertThat(page.toString())
                .contains("workScope", "eod/hose/aaa.parquet")
                .doesNotContain("_output-snapshots/");
        var mapper = context.getBean("jsonMapper", JsonMapper.class);
        jdbc.update(
                "DELETE FROM output_ready_pointers WHERE dataset='eod' AND partition_values=?::jsonb",
                mapper.writeValueAsString(partition));
        var readiness =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputWorkReadiness.class);
        var ref = com.omni.platform.modules.scheduler.dependencies.DatasetRef.of("eod", partition);
        var bindings =
                readiness.bind(
                        (UUID) child.get("execution_id"),
                        Map.of(ref, "obsolete-whole-file-version"));
        assertThat(bindings).hasSize(1).containsValue("sha256:" + "a".repeat(64));
        var bound = bindings.keySet().iterator().next();
        assertThat(bound.approvedInput(bindings.get(bound)))
                .containsEntry("atomicAttemptId", f.attempt().toString())
                .containsEntry("businessDate", "2035-01-02");
        jdbc.update(
                "DELETE FROM output_ready_rows WHERE dataset='eod' AND partition_values=?::jsonb",
                mapper.writeValueAsString(partition));
        assertThat(readiness.bind((UUID) child.get("execution_id"), Map.of(ref, "anything")))
                .isNull();
    }

    @Test
    void enrolledDownstreamCannotLoseAFrozenUpstreamThatHasNoOutputDeclaration() {
        prepare(target(JobType.SYNC_INDICATORS), "missing-upstream-declaration");
        var child = jdbc.queryForMap(
                "SELECT a.id,a.execution_id FROM atomic_work_attempts a JOIN job_execution_histories e ON e.id=a.execution_id "
                        + "WHERE e.meta_json->'workExpansion'->>'jobType'='SYNC_INDICATORS' LIMIT 1");
        context.getBean(com.omni.platform.modules.scheduler.metadata.OutputCompletionGate.class).enroll(
                (UUID) child.get("execution_id"), (UUID) child.get("id"),
                List.of(new com.omni.platform.modules.scheduler.metadata.OutputRegistrationEvent.LogicalOutput(
                        "indicators", Map.of("exchange", "hose", "code", "aaa", "source", "ad_close", "timeframe", "1d"))),
                List.of());
        var readiness = context.getBean(com.omni.platform.modules.scheduler.metadata.OutputWorkReadiness.class);
        assertThat(readiness.bind((UUID) child.get("execution_id"), Map.of())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "CANCELLED", "SUCCESS"})
    void closedOwnerAttemptsAreReplacedThenSafelySettledWithoutReopeningHistory(String state) {
        UUID old = request(prepare(target(JobType.SYNC_INDICATORS), "orphan-owner"));
        jdbc.update("UPDATE atomic_work_requests SET state=? WHERE id=?", state, old);
        jdbc.update("UPDATE job_execution_histories SET status='FAILED',finished_at=? WHERE id="
                + "(SELECT execution_id FROM atomic_work_requests WHERE id=?)", java.sql.Timestamp.from(NOW), old);
        UUID fresh = request(prepare(target(JobType.SYNC_INDICATORS), "replacement-owner"));
        assertThat(count("atomic_work_attempts")).isEqualTo(6);
        for (int tick = 0; tick < 3; tick++) graph.reconcile();
        assertThat(jdbc.queryForObject("SELECT state FROM atomic_work_requests WHERE id=?", String.class, old)).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atomic_work_request_members m "
                + "JOIN atomic_work_attempts a ON a.id=m.attempt_id JOIN job_execution_histories e ON e.id=a.execution_id "
                + "JOIN scheduler_outbox_messages o ON o.execution_id=e.id WHERE m.request_id=? "
                + "AND e.status='FAILED' AND e.error='REQUEST_CLOSED' AND e.finished_at IS NOT NULL "
                + "AND o.status='BLOCKED' AND o.dependency_reason='REQUEST_CLOSED' AND o.attempts=0",
                Integer.class, old)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM atomic_work_request_members m "
                + "JOIN atomic_work_attempts a ON a.id=m.attempt_id JOIN job_execution_histories e ON e.id=a.execution_id "
                + "WHERE m.request_id=? AND e.status='PENDING'", Integer.class, fresh)).isEqualTo(3);
        assertThat(graph.inspect(old)).containsEntry("isSettled", true).containsEntry("unfinishedMembers", 0L);
        assertThat((List<?>) graph.inspect(old).get("members")).allSatisfy(member -> {
            var detail = (Map<?, ?>) member;
            assertThat(detail.get("reasonCode")).isEqualTo("REQUEST_CLOSED");
            assertThat(detail.get("executionStatus")).isEqualTo("FAILED");
            assertThat(detail.get("requiresExecutionReview")).isEqualTo(false);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "rerun", "force"})
    void backfillSeparatesAttemptReuseFromProviderRefreshWithoutChangingWorkIdentity(String policy) {
        var actual = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(graph);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "backfillEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "registrationConsumerEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(actual, "enabled", false);
        try {
            jdbc.update("UPDATE job_definitions SET schedule_enabled=false");
            var target = target(JobType.SYNC_INDICATORS);
            boolean force = policy.equals("force"), rerun = !policy.equals("default");
            var metadata = Map.<String, Object>of("force", force, "rerun", rerun);
            var expansion = new java.util.LinkedHashMap<String, Object>(Map.of(
                    "dateFrom", "2035-01-02", "dateTo", "2035-01-02",
                    "sectorCodes", List.of(JobDefinitionConfig.ENABLED_SECTOR_CODES.getFirst()),
                    "sectorLevel", 2, "force", force, "rerun", rerun));
            var request = new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                    com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode.BACKFILL,
                    "operator", "data-policy-first", "Policy verification", null,
                    context.getBean("jsonMapper", JsonMapper.class).writeValueAsString(expansion));
            UUID first = graph.prepareBackfill(request, target.getId(), NOW, metadata);
            assertThat(graph.prepareBackfill(request, target.getId(), NOW, metadata)).isEqualTo(first);
            var second = new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                    request.mode(), request.actor(), "data-policy-second", request.reason(), null, request.parametersJson());
            graph.prepareBackfill(second, target.getId(), NOW, metadata);
            int expected = rerun ? 6 : 3;
            assertThat(count("atomic_work_attempts")).isEqualTo(expected);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT work_id) FROM atomic_work_attempts", Integer.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM job_execution_histories WHERE meta_json->'backfillPolicy'->>'dataPolicy'=? "
                            + "AND (meta_json->'backfillPolicy'->>'rerun')::boolean=?",
                    Integer.class, force ? "REFRESH" : "PREFER_EXISTING", rerun)).isEqualTo(expected);
            expansion.put("force", !force);
            expansion.put("rerun", true);
            var changed = new com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request(
                    request.mode(), request.actor(), request.idempotencyKey(), request.reason(), null,
                    context.getBean("jsonMapper", JsonMapper.class).writeValueAsString(expansion));
            assertThatThrownBy(() -> graph.prepareBackfill(changed, target.getId(), NOW,
                    Map.of("force", !force, "rerun", true)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IDEMPOTENCY_CONFLICT");
            assertThat(count("atomic_work_attempts")).isEqualTo(expected);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(actual, "backfillEnabled", false);
            org.springframework.test.util.ReflectionTestUtils.setField(actual, "registrationConsumerEnabled", false);
            org.springframework.test.util.ReflectionTestUtils.setField(actual, "enabled", true);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"claim", "expired-claim", "ack-lost", "published", "receipt", "timeout", "registration", "completion"})
    void orphanWithAnyDispatchEvidenceIsPreservedForReviewEvenWhenItsUpstreamFails(String evidence) {
        var f = completionFixture("uncertain-orphan", false);
        UUID owner = jdbc.queryForObject("SELECT request_id FROM atomic_work_request_members WHERE attempt_id=?", UUID.class, f.attempt());
        jdbc.update("UPDATE atomic_work_requests SET state='FAILED' WHERE id=?", owner);
        switch (evidence) {
            case "claim", "expired-claim" -> jdbc.update(
                    "UPDATE scheduler_outbox_messages SET attempts=1,claim_token=?,claimed_by='fixture',claim_until=? WHERE execution_id=?",
                    UUID.randomUUID(), java.sql.Timestamp.from(evidence.equals("claim") ? Instant.now().plusSeconds(60) : Instant.now().minusSeconds(60)), f.execution());
            case "ack-lost" -> jdbc.update("UPDATE scheduler_outbox_messages SET attempts=1 WHERE execution_id=?", f.execution());
            case "published" -> jdbc.update("UPDATE scheduler_outbox_messages SET attempts=1,status='PUBLISHED',published_at=? WHERE execution_id=?",
                    java.sql.Timestamp.from(NOW), f.execution());
            case "receipt" -> jdbc.update("UPDATE job_execution_histories SET status='RUNNING',meta_json=meta_json || '{\"workerStarted\":true}'::jsonb WHERE id=?", f.execution());
            case "timeout" -> jdbc.update("INSERT INTO output_attempt_timeouts(execution_id,published_at,timed_out_at) VALUES (?,?,?)",
                    f.execution(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW.plusSeconds(1800)));
            case "registration" -> context.getBean(com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class).accept(f.event());
            case "completion" -> jdbc.update("INSERT INTO output_completion_statuses(execution_id,payload) VALUES (?,jsonb_build_object('status','SUCCESS','executionId',?::text))",
                    f.execution(), f.execution().toString());
        }
        for (int tick = 0; tick < 3; tick++) graph.reconcile();
        assertThat(completionState(f.execution())).isIn("PENDING", "RUNNING");
        assertThat(jdbc.queryForObject("SELECT status FROM scheduler_outbox_messages WHERE execution_id=?", String.class, f.execution()))
                .isEqualTo(evidence.equals("published") ? "PUBLISHED" : "PENDING");
        var member = inspectedMember(owner, f.attempt());
        assertThat(member).containsEntry("orphaned", true).containsEntry("requiresExecutionReview", true);
        assertThat(graph.inspect(owner)).containsEntry("isSettled", false);
        if (evidence.equals("timeout")) {
            jdbc.update("UPDATE job_execution_histories SET status='FAILED' WHERE id=?", f.execution());
            assertThat(inspectedMember(owner, f.attempt())).containsEntry("requiresExecutionReview", true);
        }
    }

    @Test
    void reconciliationSkipsSlotsLockedByAcceptanceAndClosesThemOnALaterTick() {
        UUID owner = request(prepare(target(JobType.SYNC_STOCK_PRICE), "locked-orphan"));
        jdbc.update("UPDATE atomic_work_requests SET state='FAILED' WHERE id=?", owner);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT work_id FROM atomic_work_slots ORDER BY work_id FOR UPDATE");
            assertThatCode(() -> CompletableFuture.runAsync(graph::reconcile).get(10, TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM job_execution_histories WHERE error='REQUEST_CLOSED'", Integer.class)).isZero();
        });
        graph.reconcile();
        assertThat(graph.inspect(owner)).containsEntry("isSettled", true);
    }

    @Test
    void orphanOutboxAndExecutionUpdatesRollBackTogether() {
        UUID owner = request(prepare(target(JobType.SYNC_STOCK_PRICE), "rollback-orphan"));
        jdbc.update("UPDATE atomic_work_requests SET state='FAILED' WHERE id=?", owner);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
            graph.reconcile();
            assertThat(graph.inspect(owner)).containsEntry("isSettled", true);
            status.setRollbackOnly();
        });
        assertThat(graph.inspect(owner)).containsEntry("isSettled", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduler_outbox_messages WHERE status='PENDING'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_execution_histories WHERE error='REQUEST_CLOSED'", Integer.class)).isZero();
    }

    @Test
    void failedAttemptStaysFailedWhileSameWorkHasReadyOutputFromALaterAttempt() {
        var old = completionFixture("old-output-attempt", false);
        UUID owner = jdbc.queryForObject("SELECT request_id FROM atomic_work_request_members WHERE attempt_id=?", UUID.class, old.attempt());
        jdbc.update("UPDATE atomic_work_requests SET state='FAILED' WHERE id=?", owner);
        jdbc.update("UPDATE job_execution_histories SET status='FAILED',error='provider failure' WHERE id=?", old.execution());
        assertThat(inspectedMember(owner, old.attempt())).containsEntry("executionStatus", "FAILED").containsEntry("outputReady", false);
        var fresh = completionFixture("new-output-attempt", false);
        assertThat(fresh.attempt()).isNotEqualTo(old.attempt());
        context.getBean(com.omni.platform.modules.scheduler.metadata.OutputRegistrationStore.class).accept(fresh.event());
        context.getBean(JobService.class).applyStatus(fresh.status());
        var promotion = new com.omni.platform.modules.scheduler.metadata.OutputReadinessStore(jdbc, true, true);
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(tx -> promotion.promote(fresh.execution()));
        var member = inspectedMember(owner, old.attempt());
        assertThat(member).containsEntry("executionStatus", "FAILED").containsEntry("status", "FAILED").containsEntry("outputReady", true);
        assertThat((List<?>) member.get("readyOutputs")).singleElement().satisfies(item -> {
            var output = (Map<?, ?>) item;
            assertThat(output.get("executionId")).isEqualTo(fresh.execution().toString());
            assertThat(output.get("attemptId")).isEqualTo(fresh.attempt().toString());
        });
        jdbc.update("DELETE FROM output_ready_rows WHERE dataset='eod'");
        assertThat(inspectedMember(owner, old.attempt())).containsEntry("outputReady", false);
        assertThat(jdbc.queryForObject("SELECT state FROM atomic_work_requests WHERE id=?", String.class, owner)).isEqualTo("FAILED");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inspectedMember(UUID requestId, UUID attemptId) {
        return ((List<Map<String, Object>>) graph.inspect(requestId).get("members")).stream()
                .filter(member -> attemptId.equals(member.get("attempt_id"))).findFirst().orElseThrow();
    }

    private void releaseLease(SchedulerClaim lease) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(tx -> context.getBean(JobService.class).releaseClaim(lease));
    }
}
