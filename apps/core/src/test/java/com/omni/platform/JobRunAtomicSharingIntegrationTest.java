package com.omni.platform;

import static com.omni.platform.JobRunCrossServiceIntegrationTest.*;
import static com.omni.platform.JobRunSharedAncestorIntegrationTest.approved;
import static com.omni.platform.JobRunSharedAncestorIntegrationTest.eod;
import static com.omni.platform.JobRunSharedAncestorIntegrationTest.marker;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.omni.platform.modules.scheduler.SchedulerOutboxDispatcher;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.runs.JobRunPreparationService;
import com.omni.platform.modules.scheduler.services.SchedulerClaimService;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.modules.scheduler.work.GraphWorkRequestService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Actual I9 request membership and shared attempts, broker, storage and workers. */
@EnabledIfEnvironmentVariable(named = "OMNI_P14_E2E", matches = "1")
@Timeout(600)
class JobRunAtomicSharingIntegrationTest {
    @BeforeAll
    static void boot() throws Exception {
        JobRunCrossServiceIntegrationTest.start(true, true);
    }

    @AfterAll
    static void shutdown() throws Exception {
        JobRunCrossServiceIntegrationTest.stop();
    }

    @Test
    void sharedAtomicAttemptsCompleteBothOwnersAndSurviveCancellationAndReplay() throws Exception {
        assertThat(context.getBean(GraphWorkRequestService.class).isEnabled()).isTrue();
        jdbc.execute("TRUNCATE job_runs,job_execution_histories,symbols,atomic_work_slots CASCADE");
        // Bound this fixture to one preset per required type, one exchange and one symbol.
        var retained =
                jdbc.queryForList(
                        "SELECT DISTINCT ON (job_type) id FROM job_definitions "
                                + "WHERE job_type IN ('SYNC_SYMBOLS','SYNC_STOCK_PRICE','SYNC_INDICATORS','SYNC_METADATA') "
                                + "ORDER BY job_type,id",
                        UUID.class);
        jdbc.update("UPDATE job_definitions SET is_active=false");
        for (UUID id : retained) {
            jdbc.update(
                    "UPDATE job_definitions SET is_active=true,config_json=config_json || "
                            + "'{\"exchanges\":[\"HOSE\"],\"sectorCodes\":[],\"includeSectorClassification\":false}'::jsonb WHERE id=?",
                    id);
        }
        jdbc.update("INSERT INTO symbols(code,exchange,is_active) VALUES ('HPG','HOSE',true)");
        var graph = context.getBean(GraphWorkRequestService.class);
        var dispatch = context.getBean(SchedulerOutboxDispatcher.class);
        var control = directory.resolve("control");
        UUID first = request(JobType.SYNC_INDICATORS, "first-owner");
        UUID second = request(JobType.SYNC_INDICATORS, "second-owner");
        UUID cancelled = request(JobType.SYNC_INDICATORS, "cancelled-owner");
        assertThat(first).isNotEqualTo(second).isNotEqualTo(cancelled);
        assertThat(count("atomic_work_requests")).isEqualTo(3);
        assertThat(count("atomic_work_attempts")).isEqualTo(3);
        assertThat(count("scheduler_outbox_messages")).isEqualTo(3);
        assertThat(count("job_runs")).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_request_members", Integer.class))
                .isEqualTo(9);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM (SELECT attempt_id FROM atomic_work_request_members "
                                        + "GROUP BY attempt_id HAVING count(DISTINCT request_id)=3) shared",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_runs r WHERE "
                                        + "(SELECT count(*) FROM job_run_steps s WHERE s.job_run_id=r.id)<>1",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_attempts a "
                                        + "JOIN job_run_step_preparations p ON p.job_run_id=a.job_run_id "
                                        + "WHERE p.child_count=1 AND p.message_count=1",
                                Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM atomic_work_requests r "
                                        + "JOIN job_execution_histories e ON e.id=r.execution_id WHERE e.job_run_id IS NULL",
                                Integer.class))
                .isEqualTo(3);

        UUID stock = execution(JobType.SYNC_STOCK_PRICE);
        UUID indicator = execution(JobType.SYNC_INDICATORS);
        assertThat(jdbc.queryForObject("SELECT atomic_work_admissible(?)", Boolean.class, stock))
                .isFalse();
        assertThat(
                        context.getBean(SchedulerOutboxService.class)
                                .claimEligible(
                                        outbox(stock),
                                        BUSINESS_CLOCK,
                                        "i9-e2e",
                                        Duration.ofMinutes(2),
                                        Map.of()))
                .isNull();
        assertThat(graph.cancel(requestId(cancelled), "wrong-owner", BUSINESS_CLOCK)).isFalse();
        assertThat(graph.cancel(requestId(cancelled), "i9-e2e", BUSINESS_CLOCK)).isTrue();
        assertThat(status(cancelled)).isEqualTo("BLOCKED");
        assertThat(requestState(cancelled)).isEqualTo("CANCELLED");

        await(
                () -> {
                    dispatch.dispatch();
                    graph.reconcile();
                    return "SUCCESS".equals(status(stock));
                },
                180,
                "real catalog and stock ancestor attempts complete once for both owners");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scheduler_outbox_messages WHERE execution_id=?",
                                String.class,
                                indicator))
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT atomic_work_admissible(?)", Boolean.class, indicator))
                .isTrue();
        assertThat(Files.exists(control.resolve("entered-" + indicator + ".json"))).isFalse();
        // Execution completion alone must not bypass missing global dataset discovery.
        finishRequest(request(JobType.SYNC_METADATA, "discover-eod"), dispatch, graph);
        await(
                () -> {
                    dispatch.dispatch();
                    return Files.exists(control.resolve("entered-" + indicator + ".json"));
                },
                150,
                "one shared indicator command waits inside the actual Analyzer handler");
        assertThat(marker(control, "entered", indicator).path("attempts").asInt()).isEqualTo(1);
        String approval = approved(indicator);
        assertThat(approval).isEqualTo(eod().path("dataVersion").asText());
        int attempts = count("atomic_work_attempts");
        int messages = count("scheduler_outbox_messages");
        int executions = count("job_execution_histories");
        assertThat(request(JobType.SYNC_INDICATORS, "first-owner")).isEqualTo(first);
        assertThat(count("atomic_work_attempts")).isEqualTo(attempts);
        assertThat(count("scheduler_outbox_messages")).isEqualTo(messages);
        Files.writeString(control.resolve("release-indicators"), "release");
        finishRequest(first, dispatch, graph);
        finishRequest(second, dispatch, graph);
        await(
                () -> Files.exists(control.resolve("ack-" + indicator + ".json")),
                30,
                "actual shared worker ACK");
        assertThat(marker(control, "ack", indicator).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(status(indicator)).isEqualTo("SUCCESS");
        assertThat(status(cancelled)).isEqualTo("BLOCKED");
        assertThat(requestState(cancelled)).isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_runs WHERE state<>'SUCCESS'",
                                Integer.class))
                .isZero();

        var message =
                jdbc.queryForMap(
                        "SELECT topic,message_key FROM scheduler_outbox_messages WHERE id=?",
                        outbox(indicator));
        context.getBean(KafkaPublisher.class)
                .publishSerializedAndWait(
                        message.get("topic").toString(), message.get("message_key").toString(),
                        context.getBean(SchedulerOutboxService.class)
                                        .boundPayload(outbox(indicator)),
                                Duration.ofSeconds(15));
        await(
                () -> {
                    try {
                        return marker(control, "ack", indicator).path("attempts").asInt() == 2;
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                },
                45,
                "completed shared command replay is actually acknowledged");
        assertThat(marker(control, "ack", indicator).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(marker(control, "output", indicator).path("rows").asInt()).isEqualTo(1);
        assertThat(marker(control, "output", indicator).path("inputVersions").get(0).asText())
                .isEqualTo(approval);
        assertThat(approved(indicator)).isEqualTo(approval);
        assertThat(count("atomic_work_attempts")).isEqualTo(attempts);
        assertThat(count("scheduler_outbox_messages")).isEqualTo(messages);
        assertThat(count("job_execution_histories")).isEqualTo(executions);
        assertThat(status(first)).isEqualTo("SUCCESS");
        assertThat(status(second)).isEqualTo("SUCCESS");
        try (var admin =
                AdminClient.create(
                        Map.of(
                                "bootstrap.servers",
                                JSON.readTree(Files.readString(config)).path("kafka").asText()))) {
            assertThat(endOffset(admin, "topic-sync-symbols")).isEqualTo(1);
            assertThat(endOffset(admin, "topic-sync-stock-prices")).isEqualTo(1);
            assertThat(endOffset(admin, "topic-sync-indicators")).isEqualTo(2);
            var tp = new TopicPartition("topic-sync-indicators", 0);
            await(
                    () -> {
                        try {
                            var offset =
                                    admin.listConsumerGroupOffsets(
                                                    "omni-analyzer-topic-sync-indicators")
                                            .partitionsToOffsetAndMetadata()
                                            .get(10, TimeUnit.SECONDS)
                                            .get(tp);
                            return offset != null && offset.offset() == 2;
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    },
                    30,
                    "shared command and duplicate have committed offsets after status ACK");
        }
        finishRequest(request(JobType.SYNC_METADATA, "discover-output"), dispatch, graph);
        launch("query-service", "p14_query.py", true);
        var query = JSON.readTree(Files.readString(directory.resolve("query-result.json")));
        assertThat(query.path("rows").asInt()).isEqualTo(1);
        var result = new LinkedHashMap<String, Object>();
        result.put("graphExpansionEnabled", true);
        result.put("initialRequests", 3);
        result.put("initialSharedAttempts", 3);
        result.put("ownersPerInitialAttempt", 3);
        result.put("stepsPerAtomicRun", 1);
        result.put("firstState", requestState(first));
        result.put("secondState", requestState(second));
        result.put("cancelledState", requestState(cancelled));
        result.put("approvedInput", approval);
        result.put("workerAck", marker(control, "ack", indicator).path("status").asText());
        result.put("replayAckCount", marker(control, "ack", indicator).path("attempts").asInt());
        result.put("queryRows", query.path("rows").asInt());
        var evidence =
                ROOT.resolve(
                        ".agent/check-results/P14-I9-atomic-sharing-20261031/workflow-result.json");
        Files.createDirectories(evidence.getParent());
        Files.writeString(evidence, JSON.writeValueAsString(result));
    }

    static UUID request(JobType type, String key) {
        var definition =
                context.getBean(JobDefinitionRepository.class).findAll().stream()
                        .filter(
                                job ->
                                        job.getJobType() == type
                                                && Boolean.TRUE.equals(job.getIsActive()))
                        .findFirst()
                        .orElseThrow();
        var claim =
                context.getBean(SchedulerClaimService.class)
                        .claimJobDefinition(definition.getId(), BUSINESS_CLOCK, "i9-e2e")
                        .orElseThrow();
        Map<String, Object> expansion =
                type == JobType.SYNC_METADATA
                        ? Map.of()
                        : Map.of("scope", Map.of("type", "SYMBOL", "key", "HOSE-HPG"));
        return context.getBean(JobRunPreparationService.class)
                .prepareManual(
                        definition,
                        claim,
                        BUSINESS_CLOCK,
                        "i9-e2e",
                        key,
                        "Atomic sharing integration",
                        expansion,
                        Map.of());
    }

    static void finishRequest(
            UUID root, SchedulerOutboxDispatcher dispatch, GraphWorkRequestService graph)
            throws Exception {
        await(
                () -> {
                    dispatch.dispatch();
                    graph.reconcile();
                    assertThat(status(root)).isNotIn("FAILED", "BLOCKED");
                    return "SUCCESS".equals(status(root)) && "SUCCESS".equals(requestState(root));
                },
                150,
                "actual I9 request completes through shared attempt statuses");
    }

    static UUID requestId(UUID root) {
        return jdbc.queryForObject(
                "SELECT id FROM atomic_work_requests WHERE execution_id=?", UUID.class, root);
    }

    static String requestState(UUID root) {
        return jdbc.queryForObject(
                "SELECT state FROM atomic_work_requests WHERE execution_id=?", String.class, root);
    }

    static UUID execution(JobType type) {
        return jdbc.queryForObject(
                "SELECT a.execution_id FROM atomic_work_attempts a "
                        + "JOIN job_execution_histories e ON e.id=a.execution_id JOIN job_definitions d ON d.id=e.job_id "
                        + "WHERE d.job_type=?",
                UUID.class,
                type.name());
    }

    static UUID outbox(UUID execution) {
        return jdbc.queryForObject(
                "SELECT id FROM scheduler_outbox_messages WHERE execution_id=?",
                UUID.class,
                execution);
    }

    static int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    static long endOffset(AdminClient admin, String topic) throws Exception {
        var tp = new TopicPartition(topic, 0);
        return admin.listOffsets(Map.of(tp, OffsetSpec.latest()))
                .all()
                .get(15, TimeUnit.SECONDS)
                .get(tp)
                .offset();
    }
}
