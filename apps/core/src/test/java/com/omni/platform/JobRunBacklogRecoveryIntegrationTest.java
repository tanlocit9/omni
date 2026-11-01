package com.omni.platform;

import static com.omni.platform.JobRunCrossServiceIntegrationTest.*;
import static com.omni.platform.JobRunSharedAncestorIntegrationTest.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.omni.platform.modules.scheduler.SchedulerOutboxDispatcher;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Real persisted PUBLISHED/RUNNING work recovers through the same broker group after a crash. */
@EnabledIfEnvironmentVariable(named = "OMNI_P14_E2E", matches = "1")
@Timeout(600)
class JobRunBacklogRecoveryIntegrationTest {
    @BeforeAll
    static void boot() throws Exception {
        JobRunCrossServiceIntegrationTest.start(true);
    }

    @AfterAll
    static void shutdown() throws Exception {
        JobRunCrossServiceIntegrationTest.stop();
    }

    @Test
    void publishedRunningWorkResumesAfterWorkerCrashWithoutNewExecutionOrApproval()
            throws Exception {
        jdbc.execute("TRUNCATE job_runs, job_execution_histories, symbols CASCADE");
        jdbc.update(
                "UPDATE job_definitions SET config_json=jsonb_set(config_json,'{sectorCodes}','[]') "
                        + "WHERE job_type IN ('SYNC_STOCK_PRICE','SYNC_INDICATORS')");
        jdbc.update(
                "UPDATE job_definitions SET config_json=config_json || "
                        + "'{\"exchanges\":[\"HOSE\"],\"includeSectorClassification\":false}'::jsonb "
                        + "WHERE job_type='SYNC_SYMBOLS'");
        jdbc.update("INSERT INTO symbols(code,exchange,is_active) VALUES ('HPG','HOSE',true)");
        var dispatcher = context.getBean(SchedulerOutboxDispatcher.class);
        var root = prepare(JobType.SYNC_INDICATORS);
        var work = child(root);
        var control = directory.resolve("control");
        completeAncestors(run(root), dispatcher);
        finish(prepare(JobType.SYNC_METADATA), dispatcher);
        await(
                () -> {
                    dispatcher.dispatch();
                    return Files.exists(control.resolve("entered-" + work + ".json"));
                },
                150,
                "real indicator consumer has uncommitted in-flight work");
        assertThat(status(work)).isEqualTo("RUNNING");
        assertThat(terminal(root)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM scheduler_outbox_messages WHERE execution_id=?",
                                String.class,
                                work))
                .isEqualTo("PUBLISHED");
        var approval = approved(work);
        var acceptedDay = JSON.readTree(Files.readString(config)).path("businessDate").asText();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT meta_json->>'workBusinessDate' FROM job_execution_histories WHERE id=?",
                                String.class,
                                work))
                .isEqualTo(acceptedDay);
        int histories =
                jdbc.queryForObject("SELECT count(*) FROM job_execution_histories", Integer.class);
        int messages =
                jdbc.queryForObject(
                        "SELECT count(*) FROM scheduler_outbox_messages", Integer.class);
        var tp = new TopicPartition("topic-sync-indicators", 0);
        var group = "omni-analyzer-topic-sync-indicators";
        try (var admin =
                AdminClient.create(
                        Map.of(
                                "bootstrap.servers",
                                JSON.readTree(Files.readString(config)).path("kafka").asText()))) {
            long end =
                    admin.listOffsets(Map.of(tp, OffsetSpec.latest()))
                            .all()
                            .get(15, TimeUnit.SECONDS)
                            .get(tp)
                            .offset();
            var before =
                    admin.listConsumerGroupOffsets(group)
                            .partitionsToOffsetAndMetadata()
                            .get(15, TimeUnit.SECONDS)
                            .get(tp);
            assertThat(before == null || before.offset() < end).isTrue();

            // Only kill the Analyzer process launched by this disposable fixture.
            assertThat(WORKERS).hasSize(2);
            var analyzer = WORKERS.remove(1);
            analyzer.descendants().forEach(ProcessHandle::destroyForcibly);
            analyzer.destroyForcibly();
            assertThat(analyzer.waitFor(10, TimeUnit.SECONDS)).isTrue();
            Files.copy(
                    directory.resolve("analyzer.log"),
                    directory.resolve("analyzer-before-crash.log"));
            assertThat(status(work)).isEqualTo("RUNNING");
            assertThat(terminal(root)).isFalse();
            assertThat(Files.exists(control.resolve("ack-" + work + ".json"))).isFalse();
            launch("analyzer", "p14_worker.py", false);
            await(
                    () -> {
                        try {
                            return marker(control, "entered", work).path("attempts").asInt() >= 2;
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    },
                    90,
                    "same command is redelivered after abrupt worker exit");
            assertThat(approved(work)).isEqualTo(approval);
            Files.writeString(control.resolve("release-indicators"), "release");
            finish(root, dispatcher);
            await(
                    () -> {
                        try {
                            var offset =
                                    admin.listConsumerGroupOffsets(group)
                                            .partitionsToOffsetAndMetadata()
                                            .get(10, TimeUnit.SECONDS)
                                            .get(tp);
                            return offset != null
                                    && offset.offset() == end
                                    && Files.exists(control.resolve("ack-" + work + ".json"));
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    },
                    45,
                    "terminal status acknowledged before final offset advances");
            assertThat(marker(control, "ack", work).path("status").asText()).isEqualTo("SUCCESS");
            assertThat(status(work)).isEqualTo("SUCCESS");
            assertThat(approved(work)).isEqualTo(approval);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT meta_json->>'workBusinessDate' FROM job_execution_histories WHERE id=?",
                                    String.class,
                                    work))
                    .isEqualTo(acceptedDay);
            assertThat(marker(control, "output", work).path("inputVersions").get(0).asText())
                    .isEqualTo(approval);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM job_execution_histories", Integer.class))
                    .isEqualTo(histories);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM scheduler_outbox_messages",
                                    Integer.class))
                    .isEqualTo(messages);
            // A status-ack/commit crash window may replay completed work: preserve identity and one
            // dated row.
            var original =
                    jdbc.queryForMap(
                            "SELECT id,topic,message_key FROM scheduler_outbox_messages WHERE execution_id=?",
                            work);
            context.getBean(KafkaPublisher.class)
                    .publishSerializedAndWait(
                            original.get("topic").toString(),
                            original.get("message_key").toString(),
                            context.getBean(SchedulerOutboxService.class)
                                    .boundPayload((UUID) original.get("id")),
                            Duration.ofSeconds(15));
            await(
                    () -> {
                        try {
                            var offset =
                                    admin.listConsumerGroupOffsets(group)
                                            .partitionsToOffsetAndMetadata()
                                            .get(10, TimeUnit.SECONDS)
                                            .get(tp);
                            return marker(control, "ack", work).path("attempts").asInt() >= 2
                                    && offset != null
                                    && offset.offset() == end + 1;
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    },
                    45,
                    "duplicate terminal work is processed without new attempts or duplicate rows");
            assertThat(marker(control, "ack", work).path("status").asText()).isEqualTo("SUCCESS");
            assertThat(status(work)).isEqualTo("SUCCESS");
            assertThat(status(root)).isEqualTo("SUCCESS");
            assertThat(marker(control, "output", work).path("rows").asInt()).isEqualTo(1);
            assertThat(marker(control, "output", work).path("inputVersions").get(0).asText())
                    .isEqualTo(approval);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM job_execution_histories", Integer.class))
                    .isEqualTo(histories);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM scheduler_outbox_messages",
                                    Integer.class))
                    .isEqualTo(messages);
            Path evidence =
                    ROOT.resolve(".agent/check-results/P14-backlog-recovery/workflow-result.json");
            Files.createDirectories(evidence.getParent());
            Files.writeString(
                    evidence,
                    JSON.writeValueAsString(
                            Map.of(
                                    "businessDate",
                                    JSON.readTree(Files.readString(config))
                                            .path("businessDate")
                                            .asText(),
                                    "beforeCrash",
                                    "RUNNING",
                                    "afterRestart",
                                    status(work),
                                    "parentAfterRestart",
                                    status(root),
                                    "handlerEntries",
                                    marker(control, "entered", work).path("attempts").asInt(),
                                    "committedOffset",
                                    end + 1,
                                    "sameExecutionAndOutboxCounts",
                                    true,
                                    "approvedInputPreserved",
                                    true,
                                    "outputRows",
                                    marker(control, "output", work).path("rows").asInt())));
        }
    }
}
