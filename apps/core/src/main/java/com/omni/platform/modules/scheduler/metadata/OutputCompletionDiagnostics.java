package com.omni.platform.modules.scheduler.metadata;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read-only, under the existing authenticated operator jobs boundary. */
@RestController
@RequestMapping("/api/v1/jobs/output-registrations")
public class OutputCompletionDiagnostics {
    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Value(
            "${app.output-registration.reader-enabled:false}")
    private boolean postgres;

    public OutputCompletionDiagnostics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/executions/{execution}")
    @Transactional(
            readOnly = true,
            timeout = 10,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> inspect(@PathVariable UUID execution) {
        var rows =
                jdbc.queryForList(
                        "SELECT e.id,e.status,a.id AS atomic_attempt_id,a.work_id AS atomic_work_id,"
                                + "c.execution_id IS NOT NULL AS enrolled,s.received_at AS success_received_at,"
                                + "r.registration_id,r.received_at AS registration_received_at,t.published_at AS timeout_published_at,"
                                + "t.timed_out_at,t.ownership_state FROM job_execution_histories e "
                                + "LEFT JOIN atomic_work_attempts a ON a.execution_id=e.id "
                                + "LEFT JOIN output_completion_contracts c ON c.execution_id=e.id "
                                + "LEFT JOIN output_completion_statuses s ON s.execution_id=e.id "
                                + "LEFT JOIN output_registrations r ON r.execution_id=e.id "
                                + "LEFT JOIN output_attempt_timeouts t ON t.execution_id=e.id WHERE e.id=?",
                        execution);
        if (rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Execution not found");
        var row = new LinkedHashMap<String, Object>(rows.getFirst());
        boolean terminal = !java.util.List.of("PENDING", "RUNNING").contains(row.get("status"));
        String state =
                terminal
                        ? "TERMINAL"
                        : !Boolean.TRUE.equals(row.get("enrolled"))
                                ? "UNENROLLED"
                                : row.get("success_received_at") != null
                                                && row.get("registration_id") != null
                                        ? "COMPLETION_RECONCILIATION_REQUIRED"
                                        : row.get("success_received_at") != null
                                                ? "PENDING_REGISTRATION"
                                                : "PENDING_STATUS";
        row.put("completionState", state);
        row.put("readinessAuthority", postgres ? "POSTGRES" : "MANIFEST");
        // Never imply that registration acceptance validates immutable bytes or grants READY.
        row.put(
                "datasetReadyEstablished",
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM output_ready_pointers p JOIN output_registrations r USING(registration_id) "
                                        + "WHERE r.execution_id=?)",
                                Boolean.class,
                                execution)));
        row.put(
                "outbox",
                jdbc.queryForList(
                        "SELECT status,published_at,"
                                + "EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-published_at)) AS publish_age_seconds "
                                + "FROM scheduler_outbox_messages WHERE execution_id=? ORDER BY message_index LIMIT 32",
                        execution));
        return row;
    }
}
