package com.omni.platform;

import static com.omni.platform.JobRunCrossServiceIntegrationTest.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import com.omni.platform.modules.scheduler.SchedulerOutboxDispatcher;
import com.omni.platform.modules.scheduler.metadata.OutputDeclarations;
import com.omni.platform.modules.scheduler.metadata.OutputReadinessStore;
import com.omni.platform.modules.scheduler.work.GraphWorkRequestService;
import tools.jackson.databind.JsonNode;

/** Actual HTTP -> Kafka -> Python workers -> MinIO Parquet -> registration/READY. */
@EnabledIfEnvironmentVariable(named = "OMNI_P14_E2E", matches = "1")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(600)
class BackfillDataPolicyIntegrationTest {
    static final String ACTOR = UUID.randomUUID().toString();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static GraphWorkRequestService graph;
    static SchedulerOutboxDispatcher dispatcher;
    static Path control;
    static LocalDate day;
    static URI endpoint;

    record Submitted(UUID requestId, UUID executionId) {}

    @BeforeAll
    static void initialize() throws Exception {
        start(false, true, true, false, true);
        jdbc.execute("TRUNCATE job_runs,job_execution_histories,symbols,atomic_work_slots CASCADE");
        var retained =
                jdbc.queryForList(
                        "SELECT DISTINCT ON (job_type) id FROM job_definitions WHERE is_active=true "
                                + "AND (job_type<>'SYNC_SIGNALS' OR config_json->>'strategy'='TREND_MOMENTUM_V1') ORDER BY job_type,id",
                        UUID.class);
        jdbc.update("UPDATE job_definitions SET is_active=false,schedule_enabled=false");
        for (UUID id : retained) {
            jdbc.update(
                    "UPDATE job_definitions SET is_active=true,config_json=config_json || "
                            + "'{\"exchanges\":[\"HOSE\"],\"sectorCodes\":[],\"includeSectorClassification\":false}'::jsonb WHERE id=?",
                    id);
        }
        jdbc.update(
                "INSERT INTO symbols(code,exchange,is_active,meta_json) VALUES ('HPG','HOSE',true,'{\"sectorLv1Code\":\"BANK\"}'::jsonb)");
        graph = context.getBean(GraphWorkRequestService.class);
        dispatcher = context.getBean(SchedulerOutboxDispatcher.class);
        Object actualGraph = AopTestUtils.getUltimateTargetObject(graph);
        Object actualReadiness =
                AopTestUtils.getUltimateTargetObject(context.getBean(OutputReadinessStore.class));
        ReflectionTestUtils.setField(actualGraph, "backfillEnabled", true);
        ReflectionTestUtils.setField(actualGraph, "registrationConsumerEnabled", true);
        ReflectionTestUtils.setField(actualGraph, "enabled", false);
        ReflectionTestUtils.setField(context.getBean(OutputDeclarations.class), "enabled", false);
        ReflectionTestUtils.setField(actualReadiness, "enabled", false);
        control = directory.resolve("provider-control");
        day = BUSINESS_CLOCK.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();
        endpoint =
                URI.create(
                        "http://127.0.0.1:"
                                + context.getEnvironment().getRequiredProperty("local.server.port")
                                + "/api/v1/jobs/backfills");
        Files.writeString(control.resolve("use-current-snapshot-clock"), "true");
        Files.writeString(
                control.resolve("symbol-classification.json"), "{\"sectorLv1Code\":\"BANK\"}");
        // Seed real canonical history through the worker, leaving one historical day absent.
        Files.writeString(control.resolve("omit-stock-date"), previousDay(4).toString());
        finish(submit(day, Map.of("force", true)), true);
        Files.delete(control.resolve("omit-stock-date"));
        assertThat(fetches()).isEqualTo(1);
    }

    @AfterAll
    static void cleanup() throws Exception {
        HTTP.close();
        stop();
    }

    @Order(1)
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void coveredParquetDayIsReusedWithoutExistingPostgresReadyEvenWhenProviderWouldFail(
            boolean rerun) throws Exception {
        LocalDate target = previousDay(rerun ? 2 : 1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM output_work_ready_catalog WHERE business_date=?",
                                Integer.class,
                                target))
                .isZero();
        int before = fetches();
        Files.writeString(control.resolve("fail-stock"), "cache hit must not call provider");
        try {
            var request = submit(target, rerun ? Map.of("rerun", true) : Map.of());
            finish(request, true);
            var proof = proof(request);
            assertThat(proof.path("before").path("dayRows").asInt()).isEqualTo(1);
            assertThat(proof.path("after").path("close").asDouble())
                    .isEqualTo(proof.path("before").path("close").asDouble());
            assertThat(proof.path("after").path("otherRows").asText())
                    .isEqualTo(proof.path("before").path("otherRows").asText());
            assertThat(proof.path("policy").path("dataPolicy").asText())
                    .isEqualTo("PREFER_EXISTING");
            assertThat(proof.path("policy").path("rerun").asBoolean()).isEqualTo(rerun);
            assertThat(fetches()).isEqualTo(before);
            assertReady(request);
        } finally {
            Files.deleteIfExists(control.resolve("fail-stock"));
        }
    }

    @Order(2)
    @Test
    void existingParquetFileWithMissingDayFetchesThenRegistersThatExactDay() throws Exception {
        int before = fetches();
        var request = submit(previousDay(4), Map.of());
        finish(request, true);
        var proof = proof(request);
        assertThat(proof.path("before").path("dayRows").asInt()).isZero();
        assertThat(proof.path("before").path("otherRows").isNull())
                .isFalse(); // file/history existed
        assertThat(proof.path("after").path("dayRows").asInt()).isEqualTo(1);
        assertThat(proof.path("after").path("otherRows").asText())
                .isEqualTo(proof.path("before").path("otherRows").asText());
        assertThat(fetches()).isEqualTo(before + 1);
        assertReady(request);
    }

    @Order(3)
    @Test
    void forceFetchesCorrectedProviderRowsDespiteCoveredParquetAndPreservesOtherDays()
            throws Exception {
        int before = fetches();
        LocalDate target = previousDay(3);
        Files.writeString(
                control.resolve("stock-price-overrides.json"),
                JSON.writeValueAsString(Map.of(target.toString(), 777)));
        var request = submit(target, Map.of("force", true, "rerun", false));
        finish(request, true);
        var proof = proof(request);
        assertThat(proof.path("before").path("dayRows").asInt()).isEqualTo(1);
        assertThat(proof.path("before").path("close").asDouble()).isNotEqualTo(777);
        assertThat(proof.path("after").path("close").asDouble()).isEqualTo(777);
        assertThat(proof.path("after").path("otherRows").asText())
                .isEqualTo(proof.path("before").path("otherRows").asText());
        assertThat(proof.path("policy").path("dataPolicy").asText()).isEqualTo("REFRESH");
        assertThat(proof.path("policy").path("rerun").asBoolean()).isTrue();
        assertThat(fetches()).isEqualTo(before + 1);
        assertReady(request);
    }

    @Order(4)
    @Test
    void forceProviderFailureDoesNotFallBackToCachedRowsOrReleaseIndicators() throws Exception {
        int before = fetches();
        Files.writeString(
                control.resolve("fail-stock"), "force must fetch even though cached day exists");
        try {
            var request = submit(previousDay(3), Map.of("force", true));
            finish(request, false);
            assertUnchangedFailedRefresh(request);
            assertThat(fetches()).isEqualTo(before + 1);
        } finally {
            Files.deleteIfExists(control.resolve("fail-stock"));
        }
    }

    @Order(5)
    @Test
    void forceMissingProviderDayCannotUseAnExistingCachedDay() throws Exception {
        int before = fetches();
        Files.writeString(control.resolve("omit-stock-date"), previousDay(3).toString());
        try {
            var request = submit(previousDay(3), Map.of("force", true));
            finish(request, false);
            assertUnchangedFailedRefresh(request);
            assertThat(fetches()).isEqualTo(before + 1);
        } finally {
            Files.deleteIfExists(control.resolve("omit-stock-date"));
        }
    }

    static LocalDate previousDay(int offset) {
        LocalDate result = day;
        for (int remaining = offset; remaining > 0; ) {
            result = result.minusDays(1);
            if (result.getDayOfWeek().getValue() <= 5) remaining--;
        }
        return result;
    }

    @Order(6)
    @ParameterizedTest
    @ValueSource(strings = {"TREND_MOMENTUM_V1", "ICHIMOKU_V1"})
    void signalsBackfillRunsTheFullEnrolledChainWithFrozenInputs(String strategy) throws Exception {
        jdbc.update(
                "UPDATE job_definitions SET config_json=jsonb_set(config_json,'{strategy}',to_jsonb(?::text)) "
                        + "WHERE job_type='SYNC_SIGNALS' AND is_active",
                strategy);
        UUID definition =
                jdbc.queryForObject(
                        "SELECT id FROM job_definitions WHERE job_type='SYNC_SIGNALS' AND is_active",
                        UUID.class);
        var body =
                Map.of(
                        "goal",
                        "SYNC_SIGNALS",
                        "targetDefinitionId",
                        definition.toString(),
                        "sectorCodes",
                        List.of("BANK"),
                        "sectorLevel",
                        1,
                        "dateFrom",
                        previousDay(1).toString(),
                        "dateTo",
                        previousDay(1).toString());
        var response =
                HTTP.send(
                        HttpRequest.newBuilder(endpoint)
                                .header("Content-Type", "application/json")
                                .header("X-Omni-User", ACTOR)
                                .header("Idempotency-Key", "signals-" + UUID.randomUUID())
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(body)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        var accepted = JSON.readTree(response.body());
        var request =
                new Submitted(
                        UUID.fromString(accepted.path("requestId").asText()),
                        UUID.fromString(accepted.path("executionId").asText()));
        finish(request, true);
        assertThat(((List<?>) graph.inspect(request.requestId()).get("members"))).hasSize(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_request_members m "
                                        + "JOIN atomic_work_attempts a ON a.id=m.attempt_id JOIN output_work_ready_catalog w ON w.execution_id=a.execution_id "
                                        + "WHERE m.request_id=?",
                                Integer.class,
                                request.requestId()))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_request_members m "
                                        + "JOIN atomic_work_attempts a ON a.id=m.attempt_id JOIN job_execution_histories e ON e.id=a.execution_id "
                                        + "WHERE m.request_id=? AND m.is_target AND jsonb_array_length(e.meta_json->'approvedInputs')=2",
                                Integer.class,
                                request.requestId()))
                .isEqualTo(1);
    }

    @Order(7)
    @ParameterizedTest
    @ValueSource(strings = {"range", "rerun", "force-failure"})
    void signalsPreserveMultipleDaysAndCannotRunAfterAnUpstreamFailure(String scenario)
            throws Exception {
        jdbc.update(
                "UPDATE job_definitions SET config_json=jsonb_set(config_json,'{strategy}','\"TREND_MOMENTUM_V1\"'::jsonb) "
                        + "WHERE job_type='SYNC_SIGNALS' AND is_active");
        UUID definition =
                jdbc.queryForObject(
                        "SELECT id FROM job_definitions WHERE job_type='SYNC_SIGNALS' AND is_active",
                        UUID.class);
        var body =
                new LinkedHashMap<String, Object>(
                        Map.of(
                                "goal",
                                "SYNC_SIGNALS",
                                "targetDefinitionId",
                                definition.toString(),
                                "sectorCodes",
                                List.of("BANK"),
                                "sectorLevel",
                                1,
                                "dateFrom",
                                previousDay(scenario.equals("range") ? 2 : 1).toString(),
                                "dateTo",
                                previousDay(1).toString()));
        if (scenario.equals("rerun")) body.put("rerun", true);
        boolean failed = scenario.equals("force-failure");
        if (failed) {
            body.put("force", true);
            Files.writeString(control.resolve("fail-stock"), "Signals must not bypass failed EOD");
        }
        int before = fetches();
        try {
            var request = postBackfill(body);
            finish(request, !failed);
            if (failed) {
                assertUnchangedFailedRefresh(request);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM atomic_work_request_members m "
                                                + "JOIN atomic_work_attempts a ON a.id=m.attempt_id JOIN scheduler_outbox_messages o ON o.execution_id=a.execution_id "
                                                + "WHERE m.request_id=? AND m.is_target AND o.status='PUBLISHED'",
                                        Integer.class,
                                        request.requestId()))
                        .isZero();
            } else {
                int days = scenario.equals("range") ? 2 : 1;
                assertThat(((List<?>) graph.inspect(request.requestId()).get("members")))
                        .hasSize(3 * days + 1);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT count(*) FROM atomic_work_request_members m "
                                                + "JOIN atomic_work_attempts a ON a.id=m.attempt_id JOIN output_work_ready_catalog w ON w.execution_id=a.execution_id "
                                                + "WHERE m.request_id=?",
                                        Integer.class,
                                        request.requestId()))
                        .isEqualTo(3 * days);
                assertThat(fetches()).isEqualTo(before);
            }
        } finally {
            Files.deleteIfExists(control.resolve("fail-stock"));
        }
    }

    static Submitted submit(LocalDate target, Map<String, Boolean> flags) throws Exception {
        Map<String, Object> body =
                new LinkedHashMap<>(
                        Map.of(
                                "goal",
                                "SYNC_INDICATORS",
                                "sectorCodes",
                                List.of("BANK"),
                                "sectorLevel",
                                1,
                                "dateFrom",
                                target.toString(),
                                "dateTo",
                                target.toString()));
        body.putAll(flags);
        return postBackfill(body);
    }

    @Order(8)
    @Test
    void timedOutRevokedWriterCannotCorruptAcceptedSignalsInputsOrTheNextMerge() throws Exception {
        jdbc.update(
                "UPDATE job_definitions SET config_json=jsonb_set(config_json,'{strategy}','\"TREND_MOMENTUM_V1\"') WHERE job_type='SYNC_SIGNALS' AND is_active");
        LocalDate firstDay = previousDay(2), secondDay = previousDay(1);
        Files.writeString(
                control.resolve("stock-price-overrides.json"),
                JSON.writeValueAsString(Map.of(firstDay.toString(), 444)));
        Files.writeString(control.resolve("pause-eod-put"), "pause only the first EOD fixture PUT");
        var old =
                postBackfill(
                        Map.of(
                                "goal",
                                "SYNC_SIGNALS",
                                "sectorCodes",
                                List.of("BANK"),
                                "sectorLevel",
                                1,
                                "dateFrom",
                                firstDay.toString(),
                                "dateTo",
                                firstDay.toString(),
                                "force",
                                true));
        await(
                () -> {
                    dispatcher.dispatch();
                    return Files.exists(control.resolve("eod-put-paused"));
                },
                120,
                "old EOD worker encoded corrected data and paused before versioned PUT");
        UUID oldExecution =
                jdbc.queryForObject(
                        "SELECT a.execution_id FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                                + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE m.request_id=? AND e.meta_json->'workExpansion'->>'jobType'='SYNC_STOCK_PRICE'",
                        UUID.class,
                        old.requestId());
        Files.writeString(control.resolve("revoke-eod-worker"), "leave real consumer group");
        await(
                () -> Files.exists(control.resolve("eod-worker-revoked")),
                30,
                "consumer group revocation while old handler is alive");
        var watchdog =
                new com.omni.platform.modules.scheduler.metadata.ExecutionTimeoutWatchdog(
                        jdbc,
                        JSON,
                        context.getBean(
                                com.omni.platform.modules.scheduler.services.JobService.class),
                        context.getBean(
                                org.springframework.transaction.PlatformTransactionManager.class),
                        java.time.Duration.ofMinutes(30),
                        120000,
                        100);
        assertThat(watchdog.scan(java.time.Instant.now().plusSeconds(1860))).isPositive();
        graph.reconcile();
        assertThat(status(oldExecution)).isEqualTo("FAILED");
        Files.writeString(
                control.resolve("stock-price-overrides.json"),
                JSON.writeValueAsString(
                        Map.of(firstDay.toString(), 777, secondDay.toString(), 888)));
        launch("ingestor", "p14_worker.py", false, "-replacement");
        var next =
                postBackfill(
                        Map.of(
                                "goal",
                                "SYNC_SIGNALS",
                                "sectorCodes",
                                List.of("BANK"),
                                "sectorLevel",
                                1,
                                "dateFrom",
                                firstDay.toString(),
                                "dateTo",
                                secondDay.toString(),
                                "force",
                                true));
        finish(next, true);
        String accepted =
                jdbc.queryForObject(
                        "SELECT storage_version_id FROM output_storage_heads WHERE dataset='eod'",
                        String.class);
        Files.writeString(
                control.resolve("release-eod-put"),
                "release stale encoded 444 after replacement accepted 777/888");
        await(
                () -> Files.exists(control.resolve("late-eod-put-completed")),
                30,
                "late physical PUT from revoked worker");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT storage_version_id FROM output_storage_heads WHERE dataset='eod'",
                                String.class))
                .isEqualTo(accepted);
        assertThat(status(oldExecution)).isEqualTo("FAILED");
        Files.writeString(
                control.resolve("expected-pinned-prices.json"),
                JSON.writeValueAsString(
                        Map.of(firstDay.toString(), 777, secondDay.toString(), 888)));
        Files.writeString(
                control.resolve("expected-canonical-prices.json"),
                JSON.writeValueAsString(Map.of(firstDay.toString(), 444)));
        launch("query-service", "p14_pinned_query.py", true);
        var frozenBindings =
                JSON.readTree(
                        jdbc.queryForObject(
                                "SELECT (meta_json->'approvedInputs')::text FROM job_execution_histories "
                                        + "WHERE status='SUCCESS' AND meta_json->'workExpansion'->>'jobType'='SYNC_SIGNALS' "
                                        + "ORDER BY finished_at DESC LIMIT 1",
                                String.class));
        var frozenExpected = new java.util.ArrayList<Map<String, Object>>();
        for (var binding : frozenBindings) {
            Map<String, Object> expected =
                    new java.util.LinkedHashMap<>(JSON.convertValue(binding, Map.class));
            expected.put(
                    "storageVersionId",
                    jdbc.queryForObject(
                            "SELECT o.payload->>'storageVersionId' FROM output_registrations r "
                                    + "JOIN output_registration_outputs o USING(registration_id) "
                                    + "WHERE r.atomic_attempt_id=? AND o.dataset=? AND o.partition_values=?::jsonb",
                            String.class,
                            UUID.fromString(binding.get("atomicAttemptId").asText()),
                            binding.get("dataset").asText(),
                            binding.get("partition").toString()));
            assertThat(expected.get("storageVersionId")).isNotNull();
            frozenExpected.add(expected);
        }
        assertThat(frozenExpected).isNotEmpty();
        Files.writeString(
                control.resolve("frozen-read-approvals.json"),
                JSON.writeValueAsString(frozenExpected));
        int beforeRerun = fetches();
        var rerun =
                postBackfill(
                        Map.of(
                                "goal",
                                "SYNC_SIGNALS",
                                "sectorCodes",
                                List.of("BANK"),
                                "sectorLevel",
                                1,
                                "dateFrom",
                                firstDay.toString(),
                                "dateTo",
                                secondDay.toString(),
                                "rerun",
                                true));
        finish(rerun, true);
        assertThat(fetches()).isEqualTo(beforeRerun);
        var workReader =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputWorkReadiness.class);
        for (var expected : frozenExpected)
            assertThat(
                            workReader
                                    .read(expected.get("atomicWorkId").toString())
                                    .getFirst()
                                    .get("atomicAttemptId"))
                    .isNotEqualTo(expected.get("atomicAttemptId"));
        Files.writeString(
                control.resolve("expected-canonical-prices.json"),
                JSON.writeValueAsString(
                        Map.of(firstDay.toString(), 777, secondDay.toString(), 888)));
        launch("query-service", "p14_pinned_query.py", true);
        assertThat(
                        JSON.readTree(Files.readString(control.resolve("pinned-query-result.json")))
                                .get("frozenProofs")
                                .asInt())
                .isEqualTo(frozenExpected.size());
        assertThat(status(oldExecution)).isEqualTo("FAILED");
        await(
                () ->
                        jdbc.queryForObject(
                                        "SELECT count(*) FROM output_completion_rejections WHERE execution_id=? AND reason='TERMINAL_FAILED_LATE'",
                                        Integer.class,
                                        oldExecution)
                                > 0,
                30,
                "late revoked completion is durably audited without blocking the rerun");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM output_registrations WHERE execution_id=?",
                                Integer.class,
                                oldExecution))
                .isZero();
    }

    static Submitted postBackfill(Map<String, Object> body) throws Exception {
        var command =
                HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .header("X-Omni-User", ACTOR)
                        .header("Idempotency-Key", "policy-" + UUID.randomUUID())
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                        .build();
        var response = HTTP.send(command, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        var accepted = JSON.readTree(response.body());
        return new Submitted(
                UUID.fromString(accepted.path("requestId").asText()),
                UUID.fromString(accepted.path("executionId").asText()));
    }

    static void finish(Submitted request, boolean success) throws Exception {
        if (success)
            JobRunAtomicSharingIntegrationTest.finishRequest(
                    request.executionId(), dispatcher, graph);
        else
            await(
                    () -> {
                        dispatcher.dispatch();
                        graph.reconcile();
                        return "FAILED".equals(status(request.executionId()));
                    },
                    180,
                    "forced provider failure settles without cached fallback");
        assertThat(status(request.executionId())).isEqualTo(success ? "SUCCESS" : "FAILED");
        assertThat(graph.inspect(request.requestId())).containsEntry("isSettled", true);
    }

    static int fetches() throws Exception {
        Path log = control.resolve("stock-fetches.jsonl");
        return Files.exists(log) ? Files.readAllLines(log).size() : 0;
    }

    static JsonNode proof(Submitted request) throws Exception {
        UUID execution =
                jdbc.queryForObject(
                        "SELECT a.execution_id FROM atomic_work_request_members m "
                                + "JOIN atomic_work_attempts a ON a.id=m.attempt_id JOIN job_execution_histories e ON e.id=a.execution_id "
                                + "WHERE m.request_id=? AND e.meta_json->'workExpansion'->>'jobType'='SYNC_STOCK_PRICE'",
                        UUID.class,
                        request.requestId());
        Path proof = control.resolve("stock-proof-" + execution + ".json");
        await(() -> Files.exists(proof), 30, "worker read-back proof for actual canonical Parquet");
        return JSON.readTree(Files.readString(proof));
    }

    static void assertReady(Submitted request) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                                        + "JOIN output_work_ready_catalog w ON w.execution_id=a.execution_id JOIN atomic_work_requests r ON r.id=m.request_id "
                                        + "WHERE m.request_id=? AND w.business_date=(r.request_json->>'dateFrom')::date",
                                Integer.class,
                                request.requestId()))
                .isEqualTo(2);
    }

    static void assertUnchangedFailedRefresh(Submitted request) throws Exception {
        var proof = proof(request);
        assertThat(proof.path("status").asText()).isEqualTo("ERROR");
        assertThat(proof.path("before").path("dayRows").asInt()).isEqualTo(1);
        assertThat(proof.path("after")).isEqualTo(proof.path("before"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                                        + "JOIN output_work_ready_catalog w ON w.execution_id=a.execution_id WHERE m.request_id=?",
                                Integer.class,
                                request.requestId()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                                        + "JOIN job_execution_histories e ON e.id=a.execution_id JOIN scheduler_outbox_messages o ON o.execution_id=e.id "
                                        + "WHERE m.request_id=? AND e.meta_json->'workExpansion'->>'jobType'='SYNC_INDICATORS' AND o.status='PUBLISHED'",
                                Integer.class,
                                request.requestId()))
                .isZero();
    }
}
