package com.omni.platform.modules.scheduler.work;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.modules.scheduler.expansion.GraphWorkExpansion;
import com.omni.platform.modules.scheduler.expansion.WorkExpansion;
import com.omni.platform.modules.scheduler.messaging.ExpandedJobMessage;
import com.omni.platform.modules.scheduler.producers.JobProducerRegistry;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobRunRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request;
import com.omni.platform.modules.scheduler.runs.JobRunWorkContext;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.RequiredPath;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Step;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.StepKey;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.json.JsonMapper;

/** Durable request membership over independent one-work JobRuns. No writer or READY authority. */
@Service
public class GraphWorkRequestService {
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final JsonMapper json;
    private final JobDefinitionRepository definitions;
    private final JobProducerRegistry producers;
    private final JobRunRepository runs;
    private final JobService jobs;
    private final boolean enabled;
    private UUID reconcileCursor;

    public GraphWorkRequestService(JdbcTemplate jdbc, EntityManager entities, JsonMapper json,
            JobDefinitionRepository definitions, JobProducerRegistry producers,
            JobRunRepository runs, JobService jobs,
            @Value("${app.scheduler.job-run.graph-expansion-enabled:false}") boolean enabled,
            @Value("${app.scheduler.job-run.preparation-enabled:false}") boolean preparation,
            @Value("${app.scheduler.job-run.planning-enabled:true}") boolean planning) {
        if (enabled && (!preparation || !planning))
            throw new IllegalArgumentException("Graph expansion requires preparation and guarded planning");
        this.jdbc = jdbc; this.entities = entities; this.json = json;
        this.definitions = definitions; this.producers = producers;
        this.runs = runs; this.jobs = jobs; this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    private record Template(ExpandedJobMessage message, JobDefinition definition,
                            Map<String, Object> configuration) {}
    private record Attempt(UUID id, UUID execution) {}

    /** Retry is a new request key. Same key is exact replay, including failure/cancellation. */
    @Transactional(timeout = 120)
    public UUID prepare(Request request, UUID targetId, SchedulerClaim claim,
                        Instant now, Map<String, Object> metadata) {
        if (!enabled) throw new IllegalStateException("GRAPH_EXPANSION_DISABLED");
        if (!targetId.equals(claim.jobDefinitionId()) || request.mode() == Mode.BACKFILL)
            throw new IllegalArgumentException("Invalid graph request claim/mode");
        Map<String, Object> expansion = readObject(request.parametersJson());
        // Serialize one idempotency key, then acquire definitions/slots in stable order.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                request.mode() + ":" + request.actor() + ":" + request.idempotencyKey());
        var previous = jdbc.queryForList("SELECT id,execution_id,target_definition_id,request_json::text AS request_json "
                + "FROM atomic_work_requests WHERE mode=? AND actor=? AND idempotency_key=?",
                request.mode().name(), request.actor(), request.idempotencyKey());
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!targetId.equals(row.get("target_definition_id"))
                    || !WorkExpansion.canonical(expansion).equals(WorkExpansion.canonical(
                            readObject(row.get("request_json").toString()))))
                throw new IllegalArgumentException("GRAPH_IDEMPOTENCY_CONFLICT");
            assertClaim(targetId, claim, now);
            jobs.releaseClaim(claim);
            return (UUID) row.get("execution_id");
        }
        // Preset edits cannot race the accepted graph. Locks are intentionally coarse/bounded.
        jdbc.queryForList("SELECT id FROM job_definitions WHERE is_active=true ORDER BY id FOR UPDATE");
        // Freeze symbol membership/classification across all projected producer previews.
        jdbc.execute("LOCK TABLE symbols IN SHARE MODE");
        entities.clear();
        JobDefinition target = assertClaim(targetId, claim, now);
        List<JobDefinition> catalog = definitions.findAll().stream()
                .filter(d -> d.getCanonicalDefinitionId() == null || d.getId().equals(targetId)).toList();
        Map<String, Template> templates = new HashMap<>();
        Map<String, Boolean> sectors = new HashMap<>();
        var plan = GraphWorkExpansion.resolve(targetId, expansion, now, catalog,
                (definition, projected, instant) -> {
                    var parent = new JobExecutionHistory();
                    parent.setMetaJson(Map.of("expansionRequest", projected));
                    var configuration = WorkExpansion.resolve(definition, parent, instant).config();
                    var messages = producers.getProducer(definition.getJobType())
                            .previewAtomicWork(definition, projected, instant, jdbc, json);
                    for (var message : messages) templates.putIfAbsent(message.atomicWorkId(),
                            new Template(message, definition, configuration));
                    return messages;
                }, (symbol, sector, level) -> sectors.computeIfAbsent(symbol + ":" + sector + ":" + level,
                    ignored -> Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM symbols WHERE is_active=true AND exchange || '-' || code=? "
                            + "AND meta_json->>CAST(? AS text)=?)", Boolean.class,
                        symbol, "sectorLv" + level + "Code", sector))));
        UUID requestId = UUID.randomUUID();
        var root = parent(target, now, null, Map.of("graphRequestId", requestId.toString(),
                "workType", "GLOBAL", "workKey", target.getJobType().name(),
                "requestMetadata", WorkExpansion.freeze(metadata)));
        entities.persist(root); entities.flush();
        jdbc.update("INSERT INTO atomic_work_requests(id,mode,actor,idempotency_key,target_definition_id,"
                + "execution_id,request_json,graph_json,reason,created_at) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?)",
                requestId, request.mode().name(), request.actor(), request.idempotencyKey(), targetId,
                root.getId(), request.parametersJson(), json.writeValueAsString(plan), request.reason(), Timestamp.from(now));
        // Sorted slot locks prevent overlapping graphs acquiring opposite work orders.
        for (var work : plan.works()) {
            jdbc.update("INSERT INTO atomic_work_slots(work_id) VALUES (?) ON CONFLICT DO NOTHING", work.id());
            jdbc.queryForObject("SELECT work_id FROM atomic_work_slots WHERE work_id=? FOR UPDATE", String.class, work.id());
        }
        Map<String, List<String>> dependencies = new HashMap<>();
        plan.dependencies().forEach(edge -> dependencies.computeIfAbsent(edge.dependent(), k -> new ArrayList<>()).add(edge.upstream()));
        Map<String, GraphWorkExpansion.Work> remaining = plan.works().stream()
                .collect(Collectors.toMap(GraphWorkExpansion.Work::id, Function.identity(), (a,b) -> a, LinkedHashMap::new));
        Map<String, Attempt> attempts = new HashMap<>();
        Set<String> targets = Set.copyOf(plan.targets());
        while (!remaining.isEmpty()) {
            var ready = remaining.values().stream().filter(w -> attempts.keySet()
                    .containsAll(dependencies.getOrDefault(w.id(), List.of()))).toList();
            if (ready.isEmpty()) throw new IllegalStateException("GRAPH_DEPENDENCY_CYCLE");
            for (var work : ready) {
                var upstream = dependencies.getOrDefault(work.id(), List.of()).stream()
                        .map(id -> attempts.get(id).id()).sorted().toList();
                Attempt attempt = acquire(work, templates.get(work.id()), upstream, now);
                attempts.put(work.id(), attempt); remaining.remove(work.id());
                jdbc.update("INSERT INTO atomic_work_request_members(request_id,work_id,attempt_id,is_target) VALUES (?,?,?,?)",
                        requestId, work.id(), attempt.id(), targets.contains(work.id()));
                // Each one-work run is closed; do not dirty-check the entire graph on every flush.
                entities.clear();
            }
        }
        jdbc.update("UPDATE atomic_work_requests SET state='PREPARED' WHERE id=?", requestId);
        jobs.completeRunPreparation(assertClaim(targetId, claim, now), claim, now, request.mode() == Mode.MANUAL);
        return root.getId();
    }

    private JobDefinition assertClaim(UUID targetId, SchedulerClaim claim, Instant now) {
        jdbc.queryForObject("SELECT id FROM job_definitions WHERE id=? FOR UPDATE", UUID.class, targetId);
        var target = definitions.findById(targetId).orElseThrow();
        entities.refresh(target);
        if (!Boolean.TRUE.equals(target.getIsActive()) || !claim.claimToken().equals(target.getClaimToken())
                || !claim.claimedBy().equals(target.getClaimedBy()) || target.getClaimUntil() == null
                || !target.getClaimUntil().isAfter(now)) throw new IllegalStateException("GRAPH_CLAIM_STALE");
        return target;
    }

    private Attempt acquire(GraphWorkExpansion.Work work, Template template, List<UUID> upstream, Instant now) {
        String binding = json.writeValueAsString(upstream.stream().map(UUID::toString).toList());
        var reusable = jdbc.queryForList("SELECT a.id,a.execution_id FROM atomic_work_attempts a "
                + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE a.work_id=? AND a.sealed "
                + "AND a.upstream_attempt_ids=?::jsonb AND e.status IN ('PENDING','RUNNING') "
                + "AND NOT EXISTS(SELECT 1 FROM atomic_work_dependencies d JOIN atomic_work_attempts u ON u.id=d.upstream_attempt_id "
                + "JOIN job_execution_histories h ON h.id=u.execution_id WHERE d.attempt_id=a.id AND h.status IN ('FAILED','ERROR','BLOCKED')) "
                + "ORDER BY a.created_at DESC,a.id LIMIT 1", work.id(), binding);
        if (!reusable.isEmpty()) return new Attempt((UUID)reusable.getFirst().get("id"),
                (UUID)reusable.getFirst().get("execution_id"));
        UUID attemptId = UUID.randomUUID();
        var definition = template.definition();
        var key = new StepKey(new JobDefinitionIdentity(definition.getSource(), definition.getJobType(), definition.getCronExpr()),
                new Scope(ScopeType.valueOf(work.workType().name()), work.workKey(), work.businessDate()));
        var path = new RequiredPath(1, key, List.of(new Step(key, TopologyNodeKey.forJobType(work.jobType()), List.of())));
        var run = runs.prepare(new Request(Mode.MANUAL, "atomic-work", attemptId.toString(),
                "Atomic graph attempt", null, "{}"), path);
        var parent = parent(definition, now, run.id(), Map.of("runPreparationVersion", "1",
                "businessDate", work.businessDate().toString(), "workType", "GLOBAL",
                "workKey", work.jobType().name(), "atomicAttemptId", attemptId.toString(),
                "expandedWorkIds", List.of(work.id())));
        entities.persist(parent); entities.flush();
        var context = new JobRunWorkContext(parent, entities, jdbc, json, 1);
        var child = producers.getProducer(work.jobType()).persistAtomicRunStep(
                parent, template.message(), template.configuration(), now, context);
        entities.flush();
        jdbc.update("INSERT INTO job_run_step_preparations(job_run_id,step_index,parent_execution_id,child_count,message_count,closed_at) "
                + "VALUES (?,0,?,1,1,?)", run.id(), parent.getId(), Timestamp.from(now));
        jdbc.update("INSERT INTO atomic_work_attempts(id,work_id,job_run_id,parent_execution_id,execution_id,upstream_attempt_ids,created_at) "
                + "VALUES (?,?,?,?,?,?::jsonb,?)", attemptId, work.id(), run.id(), parent.getId(), child.getId(), binding, Timestamp.from(now));
        for (UUID id : upstream) jdbc.update("INSERT INTO atomic_work_dependencies(attempt_id,upstream_attempt_id) VALUES (?,?)", attemptId, id);
        jdbc.update("UPDATE atomic_work_attempts SET sealed=true WHERE id=?", attemptId);
        return new Attempt(attemptId, child.getId());
    }

    private static JobExecutionHistory parent(JobDefinition definition, Instant now, UUID run, Map<String,Object> metadata) {
        var parent = new JobExecutionHistory(); parent.setJob(definition); parent.setUsedSource(definition.getSource());
        parent.setStatus(JobStatus.PENDING); parent.setTriggeredAt(now); parent.setStartedAt(now);
        parent.setJobRunId(run); if (run != null) parent.setJobRunStepIndex(0);
        parent.setMetaJson(new LinkedHashMap<>(metadata)); return parent;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> readObject(String value) { return json.readValue(value, Map.class); }

    /** No execution is killed: cancellation removes one request's interest only. */
    @Transactional
    public boolean cancel(UUID requestId, String actor, Instant now) {
        var rows = jdbc.queryForList("SELECT execution_id FROM atomic_work_requests WHERE id=? AND actor=? AND state='PREPARED' FOR UPDATE", requestId, actor);
        if (rows.isEmpty()) return false;
        jdbc.update("UPDATE atomic_work_requests SET state='CANCELLED' WHERE id=?", requestId);
        jdbc.update("UPDATE job_execution_histories SET status='BLOCKED',finished_at=?,error='REQUEST_CANCELLED' WHERE id=? AND status IN ('PENDING','RUNNING')",
                Timestamp.from(now), rows.getFirst().get("execution_id")); return true;
    }

    /** Explicit refresh/retry creates new membership; it never rewrites the old graph. */
    @Transactional(timeout=120)
    public UUID retry(UUID previousRequest, String actor, String newKey,
                      SchedulerClaim claim, Instant now) {
        var previous = jdbc.queryForMap("SELECT actor,state,target_definition_id,request_json::text AS parameters "
                + "FROM atomic_work_requests WHERE id=?", previousRequest);
        if (!actor.equals(previous.get("actor")) || "PREPARED".equals(previous.get("state"))
                || "PREPARING".equals(previous.get("state")))
            throw new IllegalArgumentException("Retry requires owned terminal request");
        if (jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM atomic_work_requests WHERE mode='MANUAL' AND actor=? AND idempotency_key=? AND id=?)",
                Boolean.class, actor, newKey, previousRequest))
            throw new IllegalArgumentException("Retry requires a new request key");
        return prepare(new Request(Mode.MANUAL, actor, newKey, "Retry of " + previousRequest,
                null, previous.get("parameters").toString()), (UUID)previous.get("target_definition_id"),
                claim, now, Map.of("retryOf",previousRequest.toString()));
    }

    /** Bounded operational snapshot; stored graph and attempts remain authority after restart. */
    @Transactional(readOnly=true)
    public Map<String,Object> inspect(UUID requestId) {
        var request = jdbc.queryForMap("SELECT id,state,execution_id,target_definition_id,graph_json::text AS graph_json,created_at FROM atomic_work_requests WHERE id=?", requestId);
        request.put("graph_json", readObject(request.get("graph_json").toString()));
        var members = jdbc.queryForList("SELECT m.work_id,m.is_target,a.id AS attempt_id,a.job_run_id,a.execution_id,e.status "
                + "FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE m.request_id=? ORDER BY m.work_id", requestId);
        return Map.of("request", request, "members", members);
    }

    @Scheduled(fixedDelayString="${app.scheduler.job-run.graph-reconcile-delay:5000}")
    @Transactional(timeout=15)
    public void reconcile() {
        if (!enabled) return;
        // Fail dependents only before publication; never rewrite a published/running worker attempt.
        var failed = jdbc.queryForList("SELECT a.execution_id FROM atomic_work_attempts a JOIN scheduler_outbox_messages o ON o.execution_id=a.execution_id "
                + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE a.sealed AND e.status IN ('PENDING','RUNNING') "
                + "AND o.status='PENDING' AND (o.claim_until IS NULL OR o.claim_until<=CURRENT_TIMESTAMP) "
                + "AND EXISTS(SELECT 1 FROM atomic_work_dependencies d JOIN atomic_work_attempts u ON u.id=d.upstream_attempt_id "
                + "JOIN job_execution_histories h ON h.id=u.execution_id WHERE d.attempt_id=a.id AND h.status IN ('FAILED','ERROR','BLOCKED')) "
                + "ORDER BY a.created_at,a.id LIMIT 100");
        for (var row : failed) {
            UUID execution = (UUID)row.get("execution_id");
            jdbc.update("UPDATE scheduler_outbox_messages SET status='BLOCKED',dependency_reason='ATOMIC_UPSTREAM_FAILED',"
                    + "claim_token=NULL,claimed_by=NULL,claim_until=NULL WHERE execution_id=? AND status='PENDING' "
                    + "AND (claim_until IS NULL OR claim_until<=CURRENT_TIMESTAMP)", execution);
            jdbc.update("UPDATE job_execution_histories e SET status='BLOCKED',finished_at=CURRENT_TIMESTAMP,error='ATOMIC_UPSTREAM_FAILED' "
                    + "WHERE e.id=? AND e.status IN ('PENDING','RUNNING') AND EXISTS(SELECT 1 FROM scheduler_outbox_messages o "
                    + "WHERE o.execution_id=e.id AND o.status='BLOCKED')", execution);
        }
        jdbc.update("UPDATE job_execution_histories p SET status=done.status,finished_at=coalesce(done.finished_at,CURRENT_TIMESTAMP),error=done.error "
                + "FROM (SELECT a.parent_execution_id,e.status,e.finished_at,e.error FROM atomic_work_attempts a "
                + "JOIN job_execution_histories e ON e.id=a.execution_id JOIN job_execution_histories root ON root.id=a.parent_execution_id "
                + "WHERE root.status IN ('PENDING','RUNNING') AND e.status IN ('SUCCESS','FAILED','ERROR','BLOCKED') "
                + "ORDER BY a.created_at,a.id LIMIT 100) done WHERE p.id=done.parent_execution_id AND p.status IN ('PENDING','RUNNING')");
        jdbc.update("UPDATE job_runs r SET state=CASE WHEN done.status='SUCCESS' THEN 'SUCCESS' ELSE 'FAILED' END "
                + "FROM (SELECT a.job_run_id,e.status FROM atomic_work_attempts a JOIN job_execution_histories e ON e.id=a.execution_id "
                + "JOIN job_runs existing ON existing.id=a.job_run_id WHERE existing.state IN ('PREPARED','RUNNING') "
                + "AND e.status IN ('SUCCESS','FAILED','ERROR','BLOCKED') ORDER BY a.created_at,a.id LIMIT 100) done "
                + "WHERE r.id=done.job_run_id AND r.state IN ('PREPARED','RUNNING')");
        var requests = jdbc.queryForList("SELECT id,execution_id FROM atomic_work_requests WHERE state='PREPARED' "
                + "AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED", reconcileCursor, reconcileCursor);
        if (requests.isEmpty()) { reconcileCursor=null; return; }
        reconcileCursor=(UUID)requests.getLast().get("id");
        for (var request : requests) {
            var statuses = jdbc.queryForList("SELECT e.status FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                    + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE m.request_id=? AND m.is_target", String.class, request.get("id"));
            boolean failure = statuses.stream().anyMatch(s -> s.equals("FAILED") || s.equals("ERROR") || s.equals("BLOCKED"));
            if (!failure && statuses.stream().anyMatch(s -> !s.equals("SUCCESS"))) continue;
            jdbc.update("UPDATE atomic_work_requests SET state=? WHERE id=?", failure ? "FAILED" : "SUCCESS", request.get("id"));
            jdbc.update("UPDATE job_execution_histories SET status=?,finished_at=CURRENT_TIMESTAMP,error=? WHERE id=? AND status IN ('PENDING','RUNNING')",
                    failure ? "FAILED" : "SUCCESS", failure ? "ATOMIC_TARGET_FAILED" : null, request.get("execution_id"));
        }
    }
}
