package com.omni.platform.modules.scheduler.metadata;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** Transactional inbox and enrolled completion signal. Never mutates dataset READY or storage. */
@Service
public class OutputRegistrationStore {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private OutputCompletionGate completion;
    private ApplicationEventPublisher events;

    public OutputRegistrationStore(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Autowired
    public void configureCompletion(
            OutputCompletionGate completion, ApplicationEventPublisher events) {
        this.completion = completion;
        this.events = events;
    }

    @Transactional
    public void accept(String payload) {
        OutputRegistrationEvent.require(
                payload != null
                        && payload.getBytes(StandardCharsets.UTF_8).length
                                <= OutputRegistrationEvent.MAX_BYTES,
                "Registration event exceeds 256 KiB");
        // Constructors validate the entire record before the first mutation.
        OutputRegistrationEvent.validateWire(json.readTree(payload));
        OutputRegistrationEvent event =
                json.readerFor(OutputRegistrationEvent.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .readValue(payload);
        var registrations =
                event.registrations().stream()
                        .sorted(
                                java.util.Comparator.comparing(
                                        item -> item.executionId().toString()))
                        .toList();
        // Lock all children in deterministic order before reading status or accepting members.
        // JobService and the watchdog own the same row lock, serializing both arrival orders.
        for (var item : registrations) {
            jdbc.queryForList(
                    "SELECT id FROM job_execution_histories WHERE id=? FOR UPDATE",
                    item.executionId());
            if (completion != null) completion.validate(item);
        }
        // A replay may PUT identical rows into a new immutable backend version. Keep
        // first physical evidence; immutable equality below still rejects logical changes.
        var wire = json.readTree(payload);
        boolean normalized = false;
        for (var registration : wire.get("registrations")) {
            var stored =
                    jdbc.queryForList(
                            "SELECT payload::text FROM output_registrations WHERE registration_id=? AND execution_id=?",
                            java.util.UUID.fromString(registration.get("registrationId").asText()),
                            java.util.UUID.fromString(registration.get("executionId").asText()));
            if (stored.isEmpty()) continue;
            var first = json.readTree(stored.getFirst().get("payload").toString()).get("outputs");
            var incoming = registration.get("outputs");
            for (int i = 0; i < Math.min(first.size(), incoming.size()); i++) {
                var originalVersion = first.get(i).get("storageVersionId");
                var nextVersion = incoming.get(i).get("storageVersionId");
                if (originalVersion != null
                        && !originalVersion.isNull()
                        && nextVersion != null
                        && !nextVersion.isNull()
                        && first.get(i).get("dataset").equals(incoming.get(i).get("dataset"))
                        && first.get(i).get("partition").equals(incoming.get(i).get("partition"))) {
                    ((tools.jackson.databind.node.ObjectNode) incoming.get(i))
                            .set("storageVersionId", originalVersion);
                    normalized = true;
                }
            }
        }
        if (normalized) {
            payload = json.writeValueAsString(wire);
            event = json.readValue(payload, OutputRegistrationEvent.class);
            registrations =
                    event.registrations().stream()
                            .sorted(
                                    java.util.Comparator.comparing(
                                            item -> item.executionId().toString()))
                            .toList();
        }
        jdbc.update(
                "INSERT INTO output_registration_events(event_id,schema_version,payload) VALUES (?,1,?::jsonb) "
                        + "ON CONFLICT DO NOTHING",
                event.eventId(),
                payload);
        Boolean same =
                jdbc.queryForObject(
                        "SELECT payload=?::jsonb FROM output_registration_events WHERE event_id=?",
                        Boolean.class,
                        payload,
                        event.eventId());
        OutputRegistrationEvent.require(Boolean.TRUE.equals(same), "Conflicting immutable eventId");
        for (var item : registrations) {
            var rows =
                    jdbc.queryForList(
                            "SELECT a.work_id,e.meta_json::text AS metadata FROM atomic_work_attempts a "
                                    + "JOIN job_execution_histories e ON e.id=a.execution_id "
                                    + "WHERE a.id=? AND a.execution_id=? AND a.sealed",
                            item.atomicAttemptId(),
                            item.executionId());
            OutputRegistrationEvent.require(rows.size() == 1, "Unknown execution/atomic attempt");
            var row = rows.getFirst();
            OutputRegistrationEvent.require(
                    item.atomicWorkId().equals(row.get("work_id")), "Attempt/work mismatch");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata =
                    json.readValue(String.valueOf(row.get("metadata")), Map.class);
            OutputRegistrationEvent.require(
                    item.atomicWorkId().equals(metadata.get("workIdentity")),
                    "Persisted work alias mismatch");
            Object alias = metadata.get("atomicWorkId");
            OutputRegistrationEvent.require(
                    alias == null || alias.equals(item.atomicWorkId()),
                    "atomicWorkId alias mismatch");
            Object expansion = metadata.get("workExpansion");
            if (metadata.get("outputCompletion") instanceof Map<?, ?> declaration) {
                OutputRegistrationEvent.require(
                        item.registrationId().toString().equals(declaration.get("registrationId"))
                                && event.eventId().toString().equals(declaration.get("eventId"))
                                && item.atomicAttemptId()
                                        .toString()
                                        .equals(declaration.get("atomicAttemptId")),
                        "Registration identities differ from server declaration");
            }
            if (expansion instanceof Map<?, ?> map) {
                OutputRegistrationEvent.require(
                        item.atomicWorkId().equals(map.get("atomicWorkId")),
                        "Expansion work alias mismatch");
            }
            String member = json.writeValueAsString(item);
            jdbc.update(
                    "INSERT INTO output_registrations(registration_id,first_event_id,execution_id,atomic_attempt_id,atomic_work_id,payload) "
                            + "VALUES (?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                    item.registrationId(),
                    event.eventId(),
                    item.executionId(),
                    item.atomicAttemptId(),
                    item.atomicWorkId(),
                    member);
            Boolean identical =
                    jdbc.queryForObject(
                            "SELECT EXISTS(SELECT 1 FROM output_registrations "
                                    + "WHERE registration_id=? AND execution_id=? AND payload=?::jsonb)",
                            Boolean.class,
                            item.registrationId(),
                            item.executionId(),
                            member);
            OutputRegistrationEvent.require(
                    Boolean.TRUE.equals(identical), "Conflicting immutable registration/execution");
            for (int index = 0; index < item.outputs().size(); index++) {
                var output = item.outputs().get(index);
                jdbc.update(
                        "INSERT INTO output_registration_outputs(registration_id,output_index,dataset,partition_values,data_version,payload) "
                                + "VALUES (?,?,?,?::jsonb,?,?::jsonb) ON CONFLICT DO NOTHING",
                        item.registrationId(),
                        index,
                        output.dataset(),
                        json.writeValueAsString(output.partition()),
                        output.dataVersion(),
                        json.writeValueAsString(output));
                var dates = new java.util.ArrayList<Object[]>();
                for (var date : output.coverageDates())
                    dates.add(
                            new Object[] {
                                item.registrationId(), index, java.sql.Date.valueOf(date)
                            });
                jdbc.batchUpdate(
                        "INSERT INTO output_registration_coverage(registration_id,output_index,coverage_date) "
                                + "VALUES (?,?,?) ON CONFLICT DO NOTHING",
                        dates);
            }
        }
        // Run only after every member validates. A listener failure rolls back the entire record.
        if (events != null)
            for (var item : registrations) {
                events.publishEvent(new OutputRegistrationAccepted(item.executionId()));
            }
    }
}
