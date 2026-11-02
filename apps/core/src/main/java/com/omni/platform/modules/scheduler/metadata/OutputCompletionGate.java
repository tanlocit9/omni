package com.omni.platform.modules.scheduler.metadata;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import tools.jackson.databind.json.JsonMapper;

/** Durable correlation. Call only while owning the execution row write lock. */
@Service
public class OutputCompletionGate {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutputCompletionGate(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Freeze a server-owned declaration before any command for this execution is published. */
    @Transactional
    public void enroll(UUID execution, UUID attempt,
            List<OutputRegistrationEvent.LogicalOutput> required, List<String> validEmptyReasons) {
        OutputRegistrationEvent.require(required != null && !required.isEmpty() && required.size() <= 32
                && required.stream().noneMatch(java.util.Objects::isNull)
                && required.stream().distinct().count() == required.size(), "Invalid completion declaration");
        OutputRegistrationEvent.require(validEmptyReasons != null && validEmptyReasons.size() <= 32
                && validEmptyReasons.stream().allMatch(s -> s != null && !s.isBlank() && s.length() <= 500),
                "Invalid valid-empty reasons");
        var executions = jdbc.queryForList("SELECT id FROM job_execution_histories WHERE id=? "
                + "AND status IN ('PENDING','RUNNING') FOR UPDATE", execution);
        OutputRegistrationEvent.require(executions.size() == 1, "Execution is not enrollable");
        OutputRegistrationEvent.require(!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM scheduler_outbox_messages WHERE execution_id=? "
                + "AND (status='PUBLISHED' OR claim_token IS NOT NULL OR attempts>0))", Boolean.class, execution)),
                "Cannot enroll after dispatch may have started");
        OutputRegistrationEvent.require(!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM output_registrations WHERE execution_id=?)", Boolean.class, execution)),
                "Cannot enroll an execution with pre-existing shadow evidence");
        String outputs = json.writeValueAsString(required);
        String reasons = json.writeValueAsString(validEmptyReasons);
        jdbc.update("INSERT INTO output_completion_contracts(execution_id,atomic_attempt_id,required_outputs,valid_empty_reasons) "
                + "VALUES (?,?,?::jsonb,?::jsonb) ON CONFLICT DO NOTHING", execution, attempt, outputs, reasons);
        OutputRegistrationEvent.require(Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM output_completion_contracts WHERE execution_id=? AND atomic_attempt_id=? "
                + "AND required_outputs=?::jsonb AND valid_empty_reasons=?::jsonb)",
                Boolean.class, execution, attempt, outputs, reasons)), "Conflicting completion contract");
    }

    /** Non-enrolled executions retain existing behavior, even if global flags later change. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean allowSuccess(JobStatusMessage status) {
        UUID execution = UUID.fromString(status.executionId());
        if (!enrolled(execution)) return true;
        String payload = json.writeValueAsString(status);
        // First valid SUCCESS is durable. Transport retries may change observation timestamps;
        // replay always applies the first payload rather than replacing its attribution.
        jdbc.update("INSERT INTO output_completion_statuses(execution_id,payload) VALUES (?,?::jsonb) "
                + "ON CONFLICT DO NOTHING", execution, payload);
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM output_registrations r "
                + "JOIN output_completion_contracts c ON c.execution_id=r.execution_id "
                + "AND c.atomic_attempt_id=r.atomic_attempt_id WHERE r.execution_id=?)", Boolean.class, execution));
    }

    public boolean enrolled(UUID execution) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM output_completion_contracts WHERE execution_id=?)", Boolean.class, execution));
    }

    public JobStatusMessage pending(UUID execution) {
        var rows = jdbc.queryForList("SELECT payload::text FROM output_completion_statuses WHERE execution_id=?", execution);
        return rows.isEmpty() ? null : json.readValue(rows.getFirst().get("payload").toString(), JobStatusMessage.class);
    }

    /** Validate against the server's declaration, not a worker's self-declared output list. */
    public void validate(OutputRegistrationEvent.Registration item) {
        var rows = jdbc.queryForList("SELECT atomic_attempt_id,required_outputs::text,valid_empty_reasons::text "
                + "FROM output_completion_contracts WHERE execution_id=?", item.executionId());
        if (rows.isEmpty()) return; // Shadow-only evidence; it grants no completion/readiness.
        var contract = rows.getFirst();
        OutputRegistrationEvent.require(item.atomicAttemptId().equals(contract.get("atomic_attempt_id")),
                "Registration outside enrolled attempt");
        if (item.validEmptyReason() != null) {
            OutputRegistrationEvent.require(Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT valid_empty_reasons ?? ? FROM output_completion_contracts WHERE execution_id=?",
                    Boolean.class, item.validEmptyReason(), item.executionId())), "Unapproved valid-empty reason");
        } else {
            for (var output : item.outputs()) {
                OutputRegistrationEvent.require(output.schemaVersion()==1, "Unsupported enrolled output schema");
                OutputRegistrationEvent.require(item.jobType().name().equals("EVALUATE_SIGNALS")
                        || output.coverageDates().contains(item.businessDate()),
                        "Output does not cover enrolled work date");
            }
            // JSONB containment in both directions makes declaration ordering irrelevant.
            String required = json.writeValueAsString(item.requiredOutputs());
            OutputRegistrationEvent.require(Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT required_outputs @> ?::jsonb AND required_outputs <@ ?::jsonb "
                    + "FROM output_completion_contracts WHERE execution_id=?", Boolean.class,
                    required, required, item.executionId())), "Required outputs differ from server declaration");
        }
    }
}
