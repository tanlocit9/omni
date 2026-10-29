package com.omni.platform;

import static com.omni.platform.JobRunCrossServiceIntegrationTest.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.omni.platform.modules.scheduler.SchedulerOutboxDispatcher;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;

/** Deterministic adverse interleaving of real workers on their shared canonical dataset. */
@EnabledIfEnvironmentVariable(named = "OMNI_P14_E2E", matches = "1")
@Timeout(600)
class JobRunSharedAncestorIntegrationTest {
    @BeforeAll
    static void boot() throws Exception {
        JobRunCrossServiceIntegrationTest.start(true);
    }

    @AfterAll
    static void shutdown() throws Exception {
        JobRunCrossServiceIntegrationTest.stop();
    }

    @Test
    void overlappingRunsAndReplayNeverReportSuccessWithUnapprovedInputVersion() throws Exception {
        jdbc.execute("TRUNCATE job_runs, job_execution_histories, symbols CASCADE");
        jdbc.update(
                "UPDATE job_definitions SET config_json=jsonb_set(config_json,'{sectorCodes}','[]') WHERE job_type IN ('SYNC_STOCK_PRICE','SYNC_INDICATORS')");
        jdbc.update(
                "UPDATE job_definitions SET config_json=config_json || '{\"exchanges\":[\"HOSE\"],\"includeSectorClassification\":false}'::jsonb WHERE job_type='SYNC_SYMBOLS'");
        jdbc.update("INSERT INTO symbols(code,exchange,is_active) VALUES ('HPG','HOSE',true)");
        var dispatcher = context.getBean(SchedulerOutboxDispatcher.class);
        Path control = directory.resolve("control");
        UUID first = prepare(JobType.SYNC_INDICATORS),
                firstRun = run(first),
                firstWork = child(first);
        completeAncestors(firstRun, dispatcher);
        finish(prepare(JobType.SYNC_METADATA), dispatcher);
        var initial = eod();
        assertThat(initial.path("rowCount").asInt()).isEqualTo(180);
        await(
                () -> {
                    dispatcher.dispatch();
                    return Files.exists(control.resolve("entered-" + firstWork + ".json"));
                },
                150,
                "first indicator admitted and paused before reading input");
        String approvedFirst = approved(firstWork);
        assertThat(approvedFirst).isEqualTo(initial.path("dataVersion").asText());

        // Both runs are alive: B's real stock ancestors overwrite the same EOD
        // path while A's indicator command has already been approved/published.
        Files.writeString(control.resolve("second-generation"), "same-day-correction");
        UUID second = prepare(JobType.SYNC_INDICATORS),
                secondRun = run(second),
                secondWork = child(second);
        assertThat(secondRun).isNotEqualTo(firstRun);
        assertThat(status(first)).isNotIn("SUCCESS", "FAILED", "BLOCKED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM (SELECT job_id FROM job_execution_histories WHERE job_run_id IN (?,?) AND parent_log_id IS NULL GROUP BY job_id HAVING count(DISTINCT job_run_id)=2) shared",
                                Integer.class,
                                firstRun,
                                secondRun))
                .isGreaterThan(1);
        completeAncestors(secondRun, dispatcher);
        finish(prepare(JobType.SYNC_METADATA), dispatcher);
        var replacement = eod();
        assertThat(replacement.path("rowCount").asInt()).isEqualTo(180);
        assertThat(replacement.path("dataVersion").asText()).isNotEqualTo(approvedFirst);
        Files.writeString(control.resolve("release-indicators"), "release");
        await(
                () -> {
                    dispatcher.dispatch();
                    return terminal(first)
                            && terminal(second)
                            && Files.exists(control.resolve("ack-" + firstWork + ".json"))
                            && Files.exists(control.resolve("ack-" + secondWork + ".json"));
                },
                150,
                "both real indicator commands report terminal status");
        String approvedSecond = approved(secondWork);
        var firstAck = marker(control, "ack", firstWork);
        var secondAck = marker(control, "ack", secondWork);
        var firstOutput = output(control, firstWork, firstAck);
        var secondOutput = output(control, secondWork, secondAck);
        String persistedBeforeReplay = status(firstWork);

        // Replay only this disposable environment's existing published command
        // through the actual publisher port, preserving its execution identity.
        var original =
                jdbc.queryForMap(
                        "SELECT topic,message_key,payload FROM scheduler_outbox_messages WHERE execution_id=?",
                        firstWork);
        context.getBean(KafkaPublisher.class)
                .publishSerializedAndWait(
                        original.get("topic").toString(), original.get("message_key").toString(),
                        context.getBean(SchedulerOutboxService.class)
                                .boundPayload(jdbc.queryForObject(
                                        "SELECT id FROM scheduler_outbox_messages WHERE execution_id=?",
                                        UUID.class, firstWork)), Duration.ofSeconds(15));
        await(
                () -> {
                    try {
                        return marker(control, "ack", firstWork).path("attempts").asInt() >= 2;
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                },
                45,
                "duplicate command handled and status acknowledged");
        var replayAck = marker(control, "ack", firstWork);
        var replayOutput = output(control, firstWork, replayAck);
        assertThat(status(firstWork)).isEqualTo(persistedBeforeReplay);
        if ("SUCCESS".equals(firstAck.path("status").asText())
                || "SUCCESS".equals(secondAck.path("status").asText())) {
            finish(prepare(JobType.SYNC_METADATA), dispatcher);
            launch("query-service", "p14_query.py", true);
        }
        boolean firstConsistent = consistent(firstAck, firstOutput, approvedFirst);
        boolean secondConsistent = consistent(secondAck, secondOutput, approvedSecond);
        boolean replayConsistent = consistent(replayAck, replayOutput, approvedFirst);
        var evidence = new HashMap<String, Object>();
        evidence.put("firstRun", firstRun.toString());
        evidence.put("secondRun", secondRun.toString());
        evidence.put("firstApprovedVersion", approvedFirst);
        evidence.put("secondApprovedVersion", approvedSecond);
        evidence.put("initialRows", 180);
        evidence.put("replacementRows", 180);
        evidence.put("replacementVersion", replacement.path("dataVersion").asText());
        evidence.put("firstStatus", firstAck.path("status").asText());
        evidence.put("secondStatus", secondAck.path("status").asText());
        evidence.put("firstOutput", firstOutput);
        evidence.put("secondOutput", secondOutput);
        evidence.put("replayOutput", replayOutput);
        evidence.put("replayAttempts", replayAck.path("attempts").asInt());
        evidence.put("firstConsistent", firstConsistent);
        evidence.put("secondConsistent", secondConsistent);
        evidence.put("replayConsistent", replayConsistent);
        Path report = ROOT.resolve(".agent/check-results/P14-shared-ancestor/overlap-result.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, JSON.writeValueAsString(evidence));
        assertThat(firstConsistent && secondConsistent && replayConsistent)
                .as(
                        "Successful worker output must use the execution's approved EOD version; evidence %s",
                        report)
                .isTrue();
    }

    static UUID run(UUID execution) {
        return jdbc.queryForObject(
                "SELECT job_run_id FROM job_execution_histories WHERE id=?", UUID.class, execution);
    }

    static UUID child(UUID parent) {
        return jdbc.queryForObject(
                "SELECT id FROM job_execution_histories WHERE parent_log_id=?", UUID.class, parent);
    }

    static boolean terminal(UUID execution) {
        return List.of("SUCCESS", "FAILED", "BLOCKED").contains(status(execution));
    }

    static void completeAncestors(UUID run, SchedulerOutboxDispatcher dispatcher) throws Exception {
        await(
                () -> {
                    dispatcher.dispatch();
                    return jdbc.queryForObject(
                                    "SELECT count(*) FROM job_execution_histories h JOIN job_definitions d ON d.id=h.job_id WHERE h.job_run_id=? AND d.job_type IN ('SYNC_SYMBOLS','SYNC_STOCK_PRICE') AND h.status<>'SUCCESS'",
                                    Integer.class,
                                    run)
                            == 0;
                },
                180,
                "actual shared symbols/stock ancestor workers complete");
    }

    static String approved(UUID work) throws Exception {
        var values =
                JSON.readTree(
                                jdbc.queryForObject(
                                        "SELECT meta_json::text FROM job_execution_histories WHERE id=?",
                                        String.class,
                                        work))
                        .path("approvedInputs");
        // JobService stringifies metadata when applying terminal statuses.
        if (values.isArray()) return values.get(0).path("dataVersion").asText();
        var match = Pattern.compile("sha256:[0-9a-f]{64}").matcher(values.asText());
        assertThat(match.find()).isTrue();
        return match.group();
    }

    static JsonNode eod() throws Exception {
        try (var input =
                context.getBean("minioClient", MinioClient.class)
                        .getObject(
                                GetObjectArgs.builder()
                                        .bucket(bucket)
                                        .object("_metadata/metadata.json")
                                        .build())) {
            var document = JSON.readTree(input.readAllBytes());
            for (var dataset : document.path("datasets"))
                if ("eod".equals(dataset.path("name").asText()))
                    for (var partition : dataset.path("partitions"))
                        if ("hpg".equals(partition.path("values").path("code").asText()))
                            return partition;
            throw new IllegalStateException("No EOD discovery partition");
        }
    }

    static JsonNode marker(Path control, String prefix, UUID work) throws Exception {
        return JSON.readTree(Files.readString(control.resolve(prefix + "-" + work + ".json")));
    }

    static JsonNode output(Path control, UUID work, JsonNode ack) throws Exception {
        return "SUCCESS".equals(ack.path("status").asText())
                ? marker(control, "output", work)
                : JSON.readTree("{}");
    }

    static boolean consistent(JsonNode ack, JsonNode output, String approved) {
        return !"SUCCESS".equals(ack.path("status").asText())
                || (output.path("inputVersions").size() == 1
                        && approved.equals(output.path("inputVersions").get(0).asText()));
    }
}
