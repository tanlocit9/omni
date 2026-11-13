package com.omni.platform.modules.scheduler.metadata;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.services.JobService;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Registration, terminal execution and READY commit or roll back together. */
@Service
public class WorkerCompletionStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final OutputRegistrationStore registrations;
    private final JobService jobs;
    private final OutputCompletionGate completion;

    public WorkerCompletionStore(
            JdbcTemplate jdbc,
            JsonMapper json,
            OutputRegistrationStore registrations,
            JobService jobs,
            OutputCompletionGate completion) {
        this.jdbc = jdbc;
        this.json = json;
        this.registrations = registrations;
        this.jobs = jobs;
        this.completion = completion;
    }

    @Transactional
    public void accept(String payload) {
        OutputRegistrationEvent.require(
                payload != null
                        && payload.getBytes(StandardCharsets.UTF_8).length
                                <= OutputRegistrationEvent.MAX_BYTES,
                "Completion event exceeds 256 KiB");
        WorkerCompletionEvent.validateWire(json.readTree(payload));
        var event =
                json.readerFor(WorkerCompletionEvent.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(payload);
        acceptEvent((WorkerCompletionEvent) event, payload);
    }

    private void acceptEvent(WorkerCompletionEvent event, String payload) {
        var status = event.status();
        UUID execution = UUID.fromString(status.executionId());
        var rows =
                jdbc.queryForList(
                        "SELECT e.job_id,e.parent_log_id,e.status,e.meta_json::text,c.storage_policy,a.work_id FROM job_execution_histories e "
                                + "JOIN output_completion_contracts c ON c.execution_id=e.id "
                                + "JOIN atomic_work_attempts a ON a.id=c.atomic_attempt_id AND a.execution_id=e.id AND a.sealed "
                                + "WHERE e.id=? AND c.atomic_attempt_id=? FOR UPDATE OF e",
                        execution,
                        event.atomicAttemptId());
        OutputRegistrationEvent.require(rows.size() == 1, "Unknown enrolled completion attempt");
        var row = rows.getFirst();
        var metadata = json.readTree(row.get("meta_json").toString());
        var declaration = metadata.path("outputCompletion");
        var work = metadata.has("workExpansion") ? metadata.path("workExpansion") : metadata;
        OutputRegistrationEvent.require(
                WorkerCompletionEvent.PROTOCOL.equals(declaration.path("protocol").asText())
                        && event.eventId().toString().equals(declaration.path("eventId").asText())
                        && event.atomicAttemptId()
                                .toString()
                                .equals(declaration.path("atomicAttemptId").asText()),
                "Completion identities differ from server declaration");
        OutputRegistrationEvent.require(
                status.jobDefinitionId().equals(row.get("job_id").toString())
                        && Objects.equals(
                                status.parentExecutionId(),
                                row.get("parent_log_id") == null
                                        ? null
                                        : row.get("parent_log_id").toString())
                        && status.workType().name().equals(work.path("workType").asText())
                        && status.workKey().equals(work.path("workKey").asText()),
                "Completion execution identity mismatch");
        if (event.registration() != null) {
            var item = event.registration().registrations().getFirst();
            OutputRegistrationEvent.require(
                    item.registrationId()
                                    .toString()
                                    .equals(declaration.path("registrationId").asText())
                            && item.atomicWorkId().equals(row.get("work_id")),
                    "Completion registration identity mismatch");
        }
        String terminal = row.get("status").toString();
        if ("VERSIONED".equals(row.get("storage_policy"))
                && Set.of("SUCCESS", "FAILED").contains(terminal)) {
            if (event.registration() != null)
                completion.validate(event.registration().registrations().getFirst());
            if (terminal.equals("SUCCESS")
                    && event.registration() != null
                    && matchesAccepted(event)) return;
            audit(
                    event,
                    payload,
                    terminal,
                    terminal.equals("SUCCESS")
                            ? "TERMINAL_SUCCESS_CONFLICT"
                            : "TERMINAL_FAILED_LATE");
            return;
        }
        if (event.registration() != null)
            registrations.accept(json.writeValueAsString(event.registration()));
        jobs.applyStatus(status);
    }

    private boolean matchesAccepted(WorkerCompletionEvent event) {
        var item = event.registration().registrations().getFirst();
        var rows =
                jdbc.queryForList(
                        "SELECT payload::text FROM output_registrations WHERE execution_id=? AND registration_id=?",
                        item.executionId(),
                        item.registrationId());
        OutputRegistrationEvent.require(
                rows.size() == 1, "Terminal success lacks accepted registration");
        var first = json.readTree(rows.getFirst().get("payload").toString());
        var incoming = json.readTree(json.writeValueAsString(item));
        // Identical logical replay may upload a different immutable physical version.
        for (var registration : java.util.List.of(first, incoming))
            for (var output : registration.get("outputs"))
                ((tools.jackson.databind.node.ObjectNode) output).remove("storageVersionId");
        return first.equals(incoming);
    }

    private void audit(
            WorkerCompletionEvent event, String payload, String terminal, String reason) {
        String hash;
        try {
            hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        jdbc.update(
                "INSERT INTO output_completion_rejections(event_id,payload_sha256,execution_id,atomic_attempt_id,terminal_status,reason,payload) "
                        + "VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                event.eventId(),
                hash,
                UUID.fromString(event.status().executionId()),
                event.atomicAttemptId(),
                terminal,
                reason,
                payload);
        OutputRegistrationEvent.require(
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT payload=? FROM output_completion_rejections WHERE event_id=? AND payload_sha256=?",
                                Boolean.class,
                                payload,
                                event.eventId(),
                                hash)),
                "Conflicting completion audit hash");
    }
}
