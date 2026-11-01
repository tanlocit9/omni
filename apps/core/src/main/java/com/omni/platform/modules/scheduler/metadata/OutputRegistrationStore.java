package com.omni.platform.modules.scheduler.metadata;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** Transactional shadow inbox. No readiness pointer, terminal transition or storage mutation. */
@Service
public class OutputRegistrationStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutputRegistrationStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void accept(String payload) {
        OutputRegistrationEvent.require(payload != null
                && payload.getBytes(StandardCharsets.UTF_8).length <= OutputRegistrationEvent.MAX_BYTES,
                "Registration event exceeds 256 KiB");
        // Constructors validate the entire record before the first mutation.
        OutputRegistrationEvent event = json.readerFor(OutputRegistrationEvent.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(payload);
        jdbc.update("INSERT INTO output_registration_events(event_id,schema_version,payload) VALUES (?,1,?::jsonb) "
                + "ON CONFLICT DO NOTHING", event.eventId(), payload);
        Boolean same = jdbc.queryForObject("SELECT payload=?::jsonb FROM output_registration_events WHERE event_id=?",
                Boolean.class, payload, event.eventId());
        OutputRegistrationEvent.require(Boolean.TRUE.equals(same), "Conflicting immutable eventId");
        // Stable lock order avoids deadlocks between differently ordered multi-work batches.
        var registrations = event.registrations().stream()
                .sorted(java.util.Comparator.comparing(item -> item.executionId().toString())).toList();
        for (var item : registrations) {
            var rows = jdbc.queryForList("SELECT a.work_id,e.meta_json::text AS metadata FROM atomic_work_attempts a "
                    + "JOIN job_execution_histories e ON e.id=a.execution_id "
                    + "WHERE a.id=? AND a.execution_id=? AND a.sealed FOR SHARE OF a,e",
                    item.atomicAttemptId(), item.executionId());
            OutputRegistrationEvent.require(rows.size() == 1, "Unknown execution/atomic attempt");
            var row = rows.getFirst();
            OutputRegistrationEvent.require(item.atomicWorkId().equals(row.get("work_id")), "Attempt/work mismatch");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = json.readValue(String.valueOf(row.get("metadata")), Map.class);
            OutputRegistrationEvent.require(item.atomicWorkId().equals(metadata.get("workIdentity")), "Persisted work alias mismatch");
            Object alias = metadata.get("atomicWorkId");
            OutputRegistrationEvent.require(alias == null || alias.equals(item.atomicWorkId()), "atomicWorkId alias mismatch");
            Object expansion = metadata.get("workExpansion");
            if (expansion instanceof Map<?, ?> map) {
                OutputRegistrationEvent.require(item.atomicWorkId().equals(map.get("atomicWorkId")), "Expansion work alias mismatch");
            }
            String member = json.writeValueAsString(item);
            jdbc.update("INSERT INTO output_registrations(registration_id,first_event_id,execution_id,atomic_attempt_id,atomic_work_id,payload) "
                    + "VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING", item.registrationId(), event.eventId(),
                    item.executionId(), item.atomicAttemptId(), item.atomicWorkId(), member);
            Boolean identical = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM output_registrations "
                    + "WHERE registration_id=? AND execution_id=? AND payload=?::jsonb)", Boolean.class,
                    item.registrationId(), item.executionId(), member);
            OutputRegistrationEvent.require(Boolean.TRUE.equals(identical), "Conflicting immutable registration/execution");
        }
    }
}
