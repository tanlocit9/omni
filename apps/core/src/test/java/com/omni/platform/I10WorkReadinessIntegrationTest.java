package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static com.omni.platform.JobRunCrossServiceIntegrationTest.*;

import java.nio.file.Files;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import com.omni.platform.modules.scheduler.SchedulerOutboxDispatcher;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

/** Actual Kafka/status/registration, PostgreSQL, worker Parquet and authenticated Query path. */
@EnabledIfEnvironmentVariable(named = "OMNI_P14_E2E", matches = "1")
@Timeout(600)
class I10WorkReadinessIntegrationTest {
    @BeforeAll
    static void initialize() throws Exception {
        start(false, true, true, false, false, "shadow");
    }

    @AfterAll
    static void cleanup() throws Exception {
        stop();
    }

    @Test
    void downstreamWaitsForCommittedRegistrationThenQueriesScopedReadyWithoutMetadataSync()
            throws Exception {
        jdbc.execute("TRUNCATE job_runs,job_execution_histories,symbols,atomic_work_slots CASCADE");
        var retained =
                jdbc.queryForList(
                        "SELECT DISTINCT ON (job_type) id FROM job_definitions WHERE is_active=true ORDER BY job_type,id",
                        UUID.class);
        jdbc.update("UPDATE job_definitions SET is_active=false");
        for (UUID id : retained) {
            jdbc.update(
                    "UPDATE job_definitions SET is_active=true,config_json=config_json || "
                            + "'{\"exchanges\":[\"HOSE\"],\"sectorCodes\":[],\"includeSectorClassification\":false}'::jsonb WHERE id=?",
                    id);
        }
        jdbc.update("INSERT INTO symbols(code,exchange,is_active) VALUES ('HPG','HOSE',true)");
        var registry = context.getBean(KafkaListenerEndpointRegistry.class);
        var registration =
                registry.getListenerContainers().stream()
                        .filter(
                                c ->
                                        "platform-output-registration-shadow-v1"
                                                .equals(c.getGroupId()))
                        .findFirst()
                        .orElseThrow();
        registration.stop();
        UUID target =
                JobRunAtomicSharingIntegrationTest.request(
                        JobType.SYNC_INDICATORS, "i10-registered-chain");
        var dispatch = context.getBean(SchedulerOutboxDispatcher.class);
        try (var admin =
                org.apache.kafka.clients.admin.Admin.create(
                        java.util.Map.of(
                                "bootstrap.servers",
                                context.getEnvironment().getProperty("kafka.bootstrap-servers")))) {
            var partition =
                    new org.apache.kafka.common.TopicPartition(
                            "topic-register-output-metadata-v1", 0);
            await(
                    () -> {
                        dispatch.dispatch();
                        try {
                            return admin.listOffsets(
                                                    java.util.Map.of(
                                                            partition,
                                                            org.apache.kafka.clients.admin
                                                                    .OffsetSpec.latest()))
                                            .all()
                                            .get(2, java.util.concurrent.TimeUnit.SECONDS)
                                            .get(partition)
                                            .offset()
                                    > 0;
                        } catch (Exception waitingForCompletion) {
                            return false;
                        }
                    },
                    180,
                    "terminal completion is queued while Platform completion consumer is paused");
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM output_completion_statuses", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_registrations", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_work_ready", Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages o JOIN job_execution_histories e ON e.id=o.execution_id "
                                        + "JOIN job_definitions d ON d.id=e.job_id WHERE d.job_type='SYNC_INDICATORS' AND o.status='PUBLISHED'",
                                Integer.class))
                .isZero();
        assertThat(status(target)).isNotEqualTo("SUCCESS");
        registration.start();
        JobRunAtomicSharingIntegrationTest.finishRequest(
                target,
                dispatch,
                context.getBean(
                        com.omni.platform.modules.scheduler.work.GraphWorkRequestService.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_registrations", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_work_ready", Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories e JOIN job_definitions d ON d.id=e.job_id WHERE d.job_type='SYNC_METADATA'",
                                Integer.class))
                .isZero();
        launch("query-service", "p14_query.py", true);
        var query = JSON.readTree(Files.readString(directory.resolve("query-result.json")));
        assertThat(query.path("rows").asInt()).isEqualTo(1);
        assertThat(query.path("eodDataVersion").asText()).startsWith("sha256:");
        var graph =
                context.getBean(
                        com.omni.platform.modules.scheduler.work.GraphWorkRequestService.class);
        var failedSwitch = directory.resolve("provider-control/fail-stock");
        Files.writeString(failedSwitch, "fail before write");
        UUID failed =
                JobRunAtomicSharingIntegrationTest.request(
                        JobType.SYNC_INDICATORS, "i10-provider-failure");
        await(
                () -> {
                    dispatch.dispatch();
                    graph.reconcile();
                    return "FAILED".equals(status(failed));
                },
                150,
                "provider failure closes request without publishing downstream");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages o JOIN job_execution_histories e ON e.id=o.execution_id "
                                        + "JOIN atomic_work_attempts a ON a.execution_id=e.id "
                                        + "JOIN atomic_work_request_members m ON m.attempt_id=a.id "
                                        + "JOIN atomic_work_requests r ON r.id=m.request_id "
                                        + "WHERE r.execution_id=? AND e.meta_json->'workExpansion'->>'jobType'='SYNC_INDICATORS' AND o.status='PUBLISHED'",
                                Integer.class,
                                failed))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_work_ready", Integer.class))
                .isEqualTo(2);
        // A new request creates a new attempt; it cannot reopen or rebind the failed one.
        Files.delete(failedSwitch);
        UUID retried =
                JobRunAtomicSharingIntegrationTest.request(
                        JobType.SYNC_INDICATORS, "i10-provider-retry");
        JobRunAtomicSharingIntegrationTest.finishRequest(retried, dispatch, graph);
        assertThat(status(failed)).isEqualTo("FAILED");
        assertThat(status(target)).isEqualTo("SUCCESS");
        assertThat(status(retried)).isEqualTo("SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_attempts a JOIN job_execution_histories e ON e.id=a.execution_id "
                                        + "WHERE e.meta_json->'workExpansion'->>'jobType'='SYNC_STOCK_PRICE' AND e.status='FAILED'",
                                Integer.class))
                .isEqualTo(1);
        launch("query-service", "p14_query.py", true);
        // A backfill can enroll and promote its own chain with realtime enrollment disabled.
        var actualGraph = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(graph);
        var declarations =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputDeclarations.class);
        var readiness =
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                        context.getBean(
                                com.omni.platform.modules.scheduler.metadata.OutputReadinessStore
                                        .class));
        org.springframework.test.util.ReflectionTestUtils.setField(
                actualGraph, "backfillEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(
                actualGraph, "registrationConsumerEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(actualGraph, "enabled", false);
        org.springframework.test.util.ReflectionTestUtils.setField(declarations, "enabled", false);
        org.springframework.test.util.ReflectionTestUtils.setField(readiness, "enabled", false);
        jdbc.update("UPDATE job_definitions SET schedule_enabled=false");
        jdbc.update(
                "UPDATE symbols SET meta_json=jsonb_build_object('sectorLv1Code','BANK') WHERE code='HPG'");
        var targetDefinition =
                context
                        .getBean(
                                com.omni.platform.modules.scheduler.repositories
                                        .JobDefinitionRepository.class)
                        .findAll()
                        .stream()
                        .filter(
                                d ->
                                        d.getJobType() == JobType.SYNC_INDICATORS
                                                && Boolean.TRUE.equals(d.getIsActive()))
                        .findFirst()
                        .orElseThrow();
        var day =
                BUSINESS_CLOCK
                        .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh"))
                        .toLocalDate()
                        .toString();
        // HTTP acceptance uses the real clock; keep historical EOD fixtures on BUSINESS_CLOCK.
        Files.writeString(directory.resolve("provider-control/use-current-snapshot-clock"), "true");
        Files.writeString(
                directory.resolve("provider-control/symbol-classification.json"),
                "{\"sectorLv1Code\":\"BANK\"}");
        var endpoint =
                java.net.URI.create(
                        "http://127.0.0.1:"
                                + context.getEnvironment().getRequiredProperty("local.server.port")
                                + "/api/v1/jobs/backfills");
        var body =
                JSON.writeValueAsString(
                        java.util.Map.of(
                                "goal",
                                "SYNC_INDICATORS",
                                "dateFrom",
                                day,
                                "dateTo",
                                day,
                                "sectorCodes",
                                java.util.List.of("BANK"),
                                "sectorLevel",
                                1,
                                "force",
                                true));
        var http = java.net.http.HttpClient.newHttpClient();
        var unauthorized =
                java.net.http.HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", "i10-backfill-chain")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build();
        assertThat(
                        http.send(unauthorized, java.net.http.HttpResponse.BodyHandlers.ofString())
                                .statusCode())
                .isEqualTo(401);
        var acceptedRequest =
                java.net.http.HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .header("X-Omni-User", UUID.randomUUID().toString())
                        .header("Idempotency-Key", "i10-backfill-chain")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build();
        var accepted =
                http.send(acceptedRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        UUID backfill = UUID.fromString(JSON.readTree(accepted.body()).get("executionId").asText());
        var replay = http.send(acceptedRequest, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(JSON.readTree(replay.body()).get("executionId").asText())
                .isEqualTo(backfill.toString());
        JobRunAtomicSharingIntegrationTest.finishRequest(backfill, dispatch, graph);
        assertThat(status(backfill)).isEqualTo("SUCCESS");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                                        + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE m.request_id=? "
                                        + "AND (e.started_at IS NULL OR e.meta_json->>'workerStarted' IS DISTINCT FROM 'true')",
                                Integer.class,
                                graph.requestId(backfill)))
                .isZero();
        assertThat(declarations.enabled()).isFalse();
        assertThat(graph.isEnabled()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_requests WHERE execution_id=? AND mode='BACKFILL'",
                                Integer.class,
                                backfill))
                .isEqualTo(1);
        launch("query-service", "p14_query.py", true);
        pinExistingStorageThenRunVersionedSignals(day, endpoint, http);
    }

    private void pinExistingStorageThenRunVersionedSignals(
            String day, java.net.URI endpoint, java.net.http.HttpClient http) throws Exception {
        int registrations =
                jdbc.queryForObject("SELECT count(*) FROM output_registrations", Integer.class);
        int ready = jdbc.queryForObject("SELECT count(*) FROM output_work_ready", Integer.class);
        var client = context.getBean("minioClient", io.minio.MinioClient.class);
        client.setBucketVersioning(
                io.minio.SetBucketVersioningArgs.builder()
                        .bucket(bucket)
                        .config(
                                new io.minio.messages.VersioningConfiguration(
                                        io.minio.messages.VersioningConfiguration.Status.ENABLED,
                                        null))
                        .build());
        launch("analyzer", "p14_storage_baseline.py", true, "-physical-baseline");
        var result =
                JSON.readTree(
                        Files.readString(
                                directory.resolve(
                                        "provider-control/physical-baseline-result.json")));
        assertThat(result.path("pinnedDatasets").asInt()).isEqualTo(2);
        assertThat(result.path("logicalVersionsUnchanged").asBoolean()).isTrue();
        assertThat(result.path("bytesPreserved").asBoolean()).isTrue();
        assertThat(result.path("shadow").path("matches").asBoolean()).isTrue();
        assertThat(result.path("shadow").path("comparedRows").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_registrations", Integer.class))
                .isEqualTo(registrations);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_work_ready", Integer.class))
                .isEqualTo(ready);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM output_storage_heads WHERE baseline_version_id IS NOT NULL",
                                Integer.class))
                .isEqualTo(2);
        launch("query-service", "p14_query.py", true, "-pinned-baseline");
        for (Object bean :
                java.util.List.of(
                        context.getBean(
                                com.omni.platform.modules.scheduler.metadata.OutputDeclarations
                                        .class),
                        context.getBean(
                                com.omni.platform.modules.scheduler.metadata.OutputCompletionGate
                                        .class),
                        context.getBean(
                                com.omni.platform.modules.scheduler.metadata.OutputWorkReadiness
                                        .class)))
            org.springframework.test.util.ReflectionTestUtils.setField(
                    bean, "versionedStorage", true);
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new org.springframework.core.env.MapPropertySource(
                                "versioned-physical-rehearsal",
                                java.util.Map.of(
                                        "app.output-registration.versioned-storage-enabled",
                                        true)));
        jdbc.update(
                "UPDATE job_definitions SET config_json=jsonb_set(config_json,'{strategy}','\"TREND_MOMENTUM_V1\"') "
                        + "WHERE job_type='SYNC_SIGNALS' AND is_active");
        int fetches =
                Files.readAllLines(directory.resolve("provider-control/stock-fetches.jsonl"))
                        .size();
        String body =
                JSON.writeValueAsString(
                        java.util.Map.of(
                                "goal",
                                "SYNC_SIGNALS",
                                "dateFrom",
                                day,
                                "dateTo",
                                day,
                                "sectorCodes",
                                java.util.List.of("BANK"),
                                "sectorLevel",
                                1,
                                "rerun",
                                true));
        var command =
                java.net.http.HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .header("X-Omni-User", UUID.randomUUID().toString())
                        .header("Idempotency-Key", "i10-pinned-signals")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build();
        var accepted = http.send(command, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        UUID root = UUID.fromString(JSON.readTree(accepted.body()).get("executionId").asText());
        JobRunAtomicSharingIntegrationTest.finishRequest(
                root,
                context.getBean(SchedulerOutboxDispatcher.class),
                context.getBean(
                        com.omni.platform.modules.scheduler.work.GraphWorkRequestService.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM output_storage_heads", Integer.class))
                .isEqualTo(3);
        assertThat(Files.readAllLines(directory.resolve("provider-control/stock-fetches.jsonl")))
                .hasSize(fetches);
        var prices = JSON.writeValueAsString(java.util.Map.of(day, 180));
        Files.writeString(
                directory.resolve("provider-control/expected-pinned-prices.json"), prices);
        Files.writeString(
                directory.resolve("provider-control/expected-canonical-prices.json"), prices);
        launch("query-service", "p14_pinned_query.py", true, "-pinned-signals");
        rehearseDrainedCanonicalRepair(body, endpoint, http);
    }

    private java.util.Map<String, java.util.List<String>> readinessHistorySnapshot() {
        var snapshot = new java.util.LinkedHashMap<String, java.util.List<String>>();
        for (String table :
                java.util.List.of(
                        "output_registrations",
                        "output_registration_outputs",
                        "output_registration_events",
                        "output_work_ready_history",
                        "output_work_ready",
                        "output_ready_rows",
                        "output_storage_heads"))
            snapshot.put(
                    table,
                    jdbc.queryForList(
                            "SELECT to_jsonb(t)::text FROM "
                                    + table
                                    + " t ORDER BY to_jsonb(t)::text",
                            String.class));
        return snapshot;
    }

    private void rehearseDrainedCanonicalRepair(
            String requestBody, java.net.URI endpoint, java.net.http.HttpClient http)
            throws Exception {
        stopWorkers();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE parent_log_id IS NOT NULL "
                                        + "AND status IN ('PENDING','RUNNING')",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages WHERE status='PENDING' "
                                        + "OR claim_token IS NOT NULL",
                                Integer.class))
                .isZero();
        var before = readinessHistorySnapshot();
        var heads = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var row :
                jdbc.queryForList(
                        "SELECT dataset,partition_values::text AS partition,storage_version_id "
                                + "FROM output_storage_heads ORDER BY dataset,partition_values::text"))
            heads.add(
                    java.util.Map.of(
                            "dataset",
                            row.get("dataset"),
                            "partition",
                            JSON.readTree(row.get("partition").toString()),
                            "storageVersionId",
                            row.get("storage_version_id")));
        assertThat(heads).hasSize(3);
        Files.writeString(
                directory.resolve("provider-control/canonical-repair-heads.json"),
                JSON.writeValueAsString(
                        java.util.Map.of("disposableRehearsal", true, "heads", heads)));
        launch("query-service", "p14_canonical_repair.py", true, "-canonical-repair");
        var result =
                JSON.readTree(
                        Files.readString(
                                directory.resolve(
                                        "provider-control/canonical-repair-result.json")));
        assertThat(result.path("restoredPartitions").asInt()).isEqualTo(3);
        assertThat(result.path("validatedProofs").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(result.path("canonicalWasStale").asBoolean()).isTrue();
        assertThat(result.path("discoveryUnchanged").asBoolean()).isTrue();
        assertThat(readinessHistorySnapshot()).isEqualTo(before);
        launch("query-service", "p14_pinned_query.py", true, "-query-after-repair");
        // Rollback stops writers; it never permits a mutable writer on pinned data.
        var declarations =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputDeclarations.class);
        var completion =
                context.getBean(
                        com.omni.platform.modules.scheduler.metadata.OutputCompletionGate.class);
        org.springframework.test.util.ReflectionTestUtils.setField(
                declarations, "versionedStorage", false);
        org.springframework.test.util.ReflectionTestUtils.setField(
                completion, "versionedStorage", false);
        try {
            var command =
                    java.net.http.HttpRequest.newBuilder(endpoint)
                            .header("Content-Type", "application/json")
                            .header("X-Omni-User", UUID.randomUUID().toString())
                            .header("Idempotency-Key", "i10-unsafe-writer-downgrade")
                            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(requestBody))
                            .build();
            var rejected = http.send(command, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(503);
            assertThat(rejected.body()).contains("CANONICAL_WORK_ON_VERSIONED_PARTITION");
            assertThat(readinessHistorySnapshot()).isEqualTo(before);
            launch("query-service", "p14_pinned_query.py", true, "-query-while-writers-disabled");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    declarations, "versionedStorage", true);
            org.springframework.test.util.ReflectionTestUtils.setField(
                    completion, "versionedStorage", true);
        }
    }
}
