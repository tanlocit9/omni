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
    private static final String LIVE_OWNER =
            "EXISTS (SELECT 1 FROM atomic_work_request_members owner "
                    + "JOIN atomic_work_requests request ON request.id=owner.request_id "
                    + "WHERE owner.attempt_id=a.id AND request.state IN ('PREPARING','PREPARED'))";
    private static final String CLOSED_OWNER =
            "EXISTS (SELECT 1 FROM atomic_work_request_members owner "
                    + "JOIN atomic_work_requests request ON request.id=owner.request_id "
                    + "WHERE owner.attempt_id=a.id AND request.state IN ('SUCCESS','FAILED','CANCELLED'))";
    // PENDING alone cannot exclude an acknowledged or ambiguous publication.
    private static final String NEVER_DISPATCHED =
            "o.status='PENDING' AND o.attempts=0 AND o.published_at IS NULL "
                    + "AND o.claim_token IS NULL AND o.claimed_by IS NULL AND o.claim_until IS NULL "
                    + "AND e.meta_json->>'workerStarted' IS DISTINCT FROM 'true' "
                    + "AND NOT EXISTS (SELECT 1 FROM scheduler_outbox_messages other "
                    + "WHERE other.execution_id=e.id AND other.id<>o.id) "
                    + "AND NOT EXISTS (SELECT 1 FROM output_attempt_timeouts timeout "
                    + "WHERE timeout.execution_id=e.id) "
                    + "AND NOT EXISTS (SELECT 1 FROM output_registrations registration WHERE registration.execution_id=e.id) "
                    + "AND NOT EXISTS (SELECT 1 FROM output_completion_statuses completion WHERE completion.execution_id=e.id)";
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final JsonMapper json;
    private final JobDefinitionRepository definitions;
    private final JobProducerRegistry producers;
    private final JobRunRepository runs;
    private final JobService jobs;
    private final boolean enabled;
    private final boolean planningEnabled;
    private UUID reconcileCursor;
    @Value("${app.scheduler.backfill.enabled:false}")
    private boolean backfillEnabled;
    @Value("${app.output-registration.shadow-enabled:false}")
    private boolean registrationConsumerEnabled;

    @Transactional(timeout = 120)
    public UUID prepareBackfill(Request request, UUID target, Instant now, Map<String, Object> metadata) {
        if (request.mode() != Mode.BACKFILL) throw new IllegalArgumentException("BACKFILL mode required");
        return prepareAccepted(request, target, null, now, metadata);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.omni.platform.modules.scheduler.metadata.OutputDeclarations outputDeclarations;

    @org.springframework.beans.factory.annotation.Autowired
    private com.omni.platform.modules.scheduler.metadata.OutputCompletionGate completionGate;

    public GraphWorkRequestService(
            JdbcTemplate jdbc,
            EntityManager entities,
            JsonMapper json,
            JobDefinitionRepository definitions,
            JobProducerRegistry producers,
            JobRunRepository runs,
            JobService jobs,
            @Value("${app.scheduler.job-run.graph-expansion-enabled:false}") boolean enabled,
            @Value("${app.scheduler.job-run.preparation-enabled:false}") boolean preparation,
            @Value("${app.scheduler.job-run.planning-enabled:true}") boolean planning) {
        if (enabled && (!preparation || !planning))
            throw new IllegalArgumentException(
                    "Graph expansion requires preparation and guarded planning");
        this.jdbc = jdbc;
        this.entities = entities;
        this.json = json;
        this.definitions = definitions;
        this.producers = producers;
        this.runs = runs;
        this.jobs = jobs;
        this.enabled = enabled;
        this.planningEnabled = planning;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public UUID requestId(UUID execution) {
        return jdbc.queryForObject("SELECT id FROM atomic_work_requests WHERE execution_id=?", UUID.class, execution);
    }

    private record Template(
            ExpandedJobMessage message,
            JobDefinition definition,
            Map<String, Object> configuration) {}

    private record Attempt(UUID id, UUID execution) {}

    /** Retry is a new request key. Same key is exact replay, including failure/cancellation. */
    @Transactional(timeout = 120)
    public UUID prepare(
            Request request,
            UUID targetId,
            SchedulerClaim claim,
            Instant now,
            Map<String, Object> metadata) {
        if (!enabled) throw new IllegalStateException("GRAPH_EXPANSION_DISABLED");
        if (request.mode() == Mode.BACKFILL)
            throw new IllegalArgumentException("Invalid graph request claim/mode");
        return prepareAccepted(request, targetId, claim, now, metadata);
    }

    private UUID prepareAccepted(Request request, UUID targetId, SchedulerClaim claim,
            Instant now, Map<String, Object> metadata) {
        boolean backfill = request.mode() == Mode.BACKFILL;
        if (!backfill && !enabled) throw new IllegalStateException("GRAPH_EXPANSION_DISABLED");
        if (backfill && (!backfillEnabled || !registrationConsumerEnabled || !planningEnabled))
            throw new IllegalStateException("BACKFILL_DISABLED_OR_REGISTRATION_UNAVAILABLE");
        if (backfill ? claim != null : (claim == null || !targetId.equals(claim.jobDefinitionId())))
            throw new IllegalArgumentException("Invalid graph request claim/mode");
        Map<String, Object> expansion = readObject(request.parametersJson());
        if (backfill && (Boolean.TRUE.equals(expansion.get("force")) != Boolean.TRUE.equals(metadata.get("force"))
                || newAttempts(expansion) != newAttempts(metadata)))
            throw new IllegalArgumentException("Backfill rerun/force policy must match accepted request");
        // Serialize one idempotency key, then acquire definitions/slots in stable order.
        jdbc.queryForList(
                "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                request.mode() + ":" + request.actor() + ":" + request.idempotencyKey());
        var previous =
                jdbc.queryForList(
                        "SELECT id,execution_id,target_definition_id,request_json::text AS request_json "
                                + "FROM atomic_work_requests WHERE mode=? AND actor=? AND idempotency_key=?",
                        request.mode().name(),
                        request.actor(),
                        request.idempotencyKey());
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!targetId.equals(row.get("target_definition_id"))
                    || !WorkExpansion.canonical(expansion)
                            .equals(
                                    WorkExpansion.canonical(
                                            readObject(row.get("request_json").toString()))))
                throw new IllegalArgumentException("GRAPH_IDEMPOTENCY_CONFLICT");
            assertClaim(targetId, claim, now);
            if (claim != null) jobs.releaseClaim(claim);
            return (UUID) row.get("execution_id");
        }
        // Preset edits cannot race the accepted graph. Locks are intentionally coarse/bounded.
        jdbc.queryForList(
                "SELECT id FROM job_definitions WHERE is_active=true ORDER BY id FOR UPDATE");
        // Freeze symbol membership/classification across all projected producer previews.
        jdbc.execute("LOCK TABLE symbols IN SHARE MODE");
        entities.clear();
        JobDefinition target = assertClaim(targetId, claim, now);
        if (backfill && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM job_definitions WHERE is_active AND schedule_enabled "
                        + "AND job_type IN ('SYNC_STOCK_PRICE','SYNC_INDICATORS')) "
                        + "OR EXISTS(SELECT 1 FROM job_execution_histories e JOIN job_definitions j ON j.id=e.job_id "
                        + "WHERE e.status IN ('PENDING','RUNNING') AND e.parent_log_id IS NOT NULL "
                        + "AND j.job_type IN ('SYNC_STOCK_PRICE','SYNC_INDICATORS') "
                        + "AND NOT (e.meta_json ? 'outputCompletion'))", Boolean.class)))
            throw new IllegalStateException("BACKFILL_REQUIRES_PAUSED_AND_DRAINED_LEGACY_WRITERS");
        if (backfill && expansion.get("sectorCodes") instanceof List<?> requestedSectors) {
            for (Object sector : requestedSectors) {
                if (!Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM symbols WHERE is_active=true AND meta_json->>CAST(? AS text)=?)",
                        Boolean.class, "sectorLv" + expansion.get("sectorLevel") + "Code", sector)))
                    throw new IllegalArgumentException("No active symbols for requested sector: " + sector);
            }
        }
        List<JobDefinition> catalog =
                definitions.findAll().stream()
                        .filter(
                                d ->
                                        d.getCanonicalDefinitionId() == null
                                                || d.getId().equals(targetId))
                        .toList();
        Map<String, Template> templates = new HashMap<>();
        Map<String, Boolean> sectors = new HashMap<>();
        var plan =
                GraphWorkExpansion.resolve(
                        targetId,
                        expansion,
                        now,
                        catalog,
                        (definition, projected, instant) -> {
                            var parent = new JobExecutionHistory();
                            parent.setMetaJson(Map.of("expansionRequest", projected));
                            var configuration =
                                    WorkExpansion.resolve(definition, parent, instant).config();
                            var messages =
                                    producers
                                            .getProducer(definition.getJobType())
                                            .previewAtomicWork(
                                                    definition, projected, instant, jdbc, json);
                            for (var message : messages)
                                templates.putIfAbsent(
                                        message.atomicWorkId(),
                                        new Template(message, definition, configuration));
                            return messages;
                        },
                        (symbol, sector, level) ->
                                sectors.computeIfAbsent(
                                        symbol + ":" + sector + ":" + level,
                                        ignored ->
                                                Boolean.TRUE.equals(
                                                        jdbc.queryForObject(
                                                                "SELECT EXISTS(SELECT 1 FROM symbols WHERE is_active=true AND exchange || '-' || code=? "
                                                                        + "AND meta_json->>CAST(? AS text)=?)",
                                                                Boolean.class,
                                                                symbol,
                                                                "sectorLv" + level + "Code",
                                                                sector))));
        if (backfill && plan.targets().isEmpty())
            throw new IllegalArgumentException("Backfill scope resolves no target work");
        if (backfill) {
            var acceptedParent = new JobExecutionHistory();
            acceptedParent.setMetaJson(Map.of("expansionRequest", expansion));
            var days = WorkExpansion.resolve(target, acceptedParent, now).days();
            var symbols = jdbc.queryForList(
                    "SELECT exchange || '-' || code FROM symbols WHERE is_active=true "
                            + "AND meta_json->>CAST(? AS text) IN (SELECT jsonb_array_elements_text(?::jsonb))",
                    String.class, "sectorLv" + expansion.get("sectorLevel") + "Code",
                    json.writeValueAsString(expansion.get("sectorCodes")));
            Set<String> expected = symbols.stream().flatMap(symbol -> days.stream()
                    .map(day -> symbol + "@" + day)).collect(Collectors.toSet());
            Set<String> targetWorkIds = Set.copyOf(plan.targets());
            Set<String> actual = plan.works().stream().filter(work -> targetWorkIds.contains(work.id()))
                    .map(work -> work.workKey() + "@" + work.businessDate()).collect(Collectors.toSet());
            if (!actual.equals(expected))
                throw new IllegalArgumentException("BACKFILL_INCOMPLETE_TARGET_MEMBERSHIP");
        }
        UUID requestId = UUID.randomUUID();
        var root =
                parent(
                        target,
                        now,
                        null,
                        Map.of(
                                "graphRequestId",
                                requestId.toString(),
                                "workType",
                                "GLOBAL",
                                "workKey",
                                target.getJobType().name(),
                                "requestMetadata",
                                WorkExpansion.freeze(metadata)));
        entities.persist(root);
        entities.flush();
        jdbc.update(
                "INSERT INTO atomic_work_requests(id,mode,actor,idempotency_key,target_definition_id,"
                        + "execution_id,request_json,graph_json,reason,created_at) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?)",
                requestId,
                request.mode().name(),
                request.actor(),
                request.idempotencyKey(),
                targetId,
                root.getId(),
                request.parametersJson(),
                json.writeValueAsString(plan),
                request.reason(),
                Timestamp.from(now));
        // Sorted slot locks prevent overlapping graphs acquiring opposite work orders.
        for (var work : plan.works()) {
            jdbc.update(
                    "INSERT INTO atomic_work_slots(work_id) VALUES (?) ON CONFLICT DO NOTHING",
                    work.id());
            jdbc.queryForObject(
                    "SELECT work_id FROM atomic_work_slots WHERE work_id=? FOR UPDATE",
                    String.class,
                    work.id());
        }
        Map<String, List<String>> dependencies = new HashMap<>();
        plan.dependencies()
                .forEach(
                        edge ->
                                dependencies
                                        .computeIfAbsent(edge.dependent(), k -> new ArrayList<>())
                                        .add(edge.upstream()));
        Map<String, GraphWorkExpansion.Work> remaining =
                plan.works().stream()
                        .collect(
                                Collectors.toMap(
                                        GraphWorkExpansion.Work::id,
                                        Function.identity(),
                                        (a, b) -> a,
                                        LinkedHashMap::new));
        Map<String, Attempt> attempts = new HashMap<>();
        Set<String> targets = Set.copyOf(plan.targets());
        while (!remaining.isEmpty()) {
            var ready =
                    remaining.values().stream()
                            .filter(
                                    w ->
                                            attempts.keySet()
                                                    .containsAll(
                                                            dependencies.getOrDefault(
                                                                    w.id(), List.of())))
                            .toList();
            if (ready.isEmpty()) throw new IllegalStateException("GRAPH_DEPENDENCY_CYCLE");
            for (var work : ready) {
                var upstream =
                        dependencies.getOrDefault(work.id(), List.of()).stream()
                                .map(id -> attempts.get(id).id())
                                .sorted()
                                .toList();
                Attempt attempt = acquire(work, templates.get(work.id()), upstream, now, backfill, metadata);
                attempts.put(work.id(), attempt);
                remaining.remove(work.id());
                jdbc.update(
                        "INSERT INTO atomic_work_request_members(request_id,work_id,attempt_id,is_target) VALUES (?,?,?,?)",
                        requestId,
                        work.id(),
                        attempt.id(),
                        targets.contains(work.id()));
                // Each one-work run is closed; do not dirty-check the entire graph on every flush.
                entities.clear();
            }
        }
        jdbc.update("UPDATE atomic_work_requests SET state='PREPARED' WHERE id=?", requestId);
        if (claim != null) jobs.completeRunPreparation(
                assertClaim(targetId, claim, now), claim, now, request.mode() == Mode.MANUAL);
        return root.getId();
    }

    private JobDefinition assertClaim(UUID targetId, SchedulerClaim claim, Instant now) {
        jdbc.queryForObject(
                "SELECT id FROM job_definitions WHERE id=? FOR UPDATE", UUID.class, targetId);
        var target = definitions.findById(targetId).orElseThrow();
        entities.refresh(target);
        if (claim == null) {
            if (!Boolean.TRUE.equals(target.getIsActive())) throw new IllegalStateException("Inactive backfill target");
            return target;
        }
        if (!Boolean.TRUE.equals(target.getIsActive())
                || !claim.claimToken().equals(target.getClaimToken())
                || !claim.claimedBy().equals(target.getClaimedBy())
                || target.getClaimUntil() == null
                || !target.getClaimUntil().isAfter(now))
            throw new IllegalStateException("GRAPH_CLAIM_STALE");
        return target;
    }

    private Attempt acquire(
            GraphWorkExpansion.Work work, Template template, List<UUID> upstream, Instant now, boolean backfill, Map<String, Object> requestMetadata) {
        var requiredOutputs =
                outputDeclarations == null
                        ? List
                                .<com.omni.platform.modules.scheduler.metadata
                                                .OutputRegistrationEvent.LogicalOutput>
                                        of()
                        : backfill ? outputDeclarations.requiredForEnrolledWork(template.message())
                        : outputDeclarations.required(template.message());
        String binding = json.writeValueAsString(upstream.stream().map(UUID::toString).toList());
        var reusable = backfill && newAttempts(requestMetadata)
                ? List.<Map<String, Object>>of() : jdbc.queryForList(
                        "SELECT a.id,a.execution_id FROM atomic_work_attempts a "
                                + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE a.work_id=? AND a.sealed "
                                + "AND a.upstream_attempt_ids=?::jsonb AND e.status IN ('PENDING','RUNNING') "
                                + "AND " + LIVE_OWNER + " "
                                + "AND NOT EXISTS(SELECT 1 FROM atomic_work_dependencies d JOIN atomic_work_attempts u ON u.id=d.upstream_attempt_id "
                                + "JOIN job_execution_histories h ON h.id=u.execution_id WHERE d.attempt_id=a.id AND h.status IN ('FAILED','ERROR','BLOCKED')) "
                                + "AND (?=false OR EXISTS(SELECT 1 FROM output_completion_contracts c WHERE c.execution_id=e.id)) "
                                + "ORDER BY a.created_at DESC,a.id LIMIT 1",
                        work.id(),
                        binding,
                        !requiredOutputs.isEmpty());
        if (!reusable.isEmpty())
            return new Attempt(
                    (UUID) reusable.getFirst().get("id"),
                    (UUID) reusable.getFirst().get("execution_id"));
        UUID attemptId = UUID.randomUUID();
        var definition = template.definition();
        var key =
                new StepKey(
                        new JobDefinitionIdentity(
                                definition.getSource(),
                                definition.getJobType(),
                                definition.getCronExpr()),
                        new Scope(
                                ScopeType.valueOf(work.workType().name()),
                                work.workKey(),
                                work.businessDate()));
        var path =
                new RequiredPath(
                        1,
                        key,
                        List.of(
                                new Step(
                                        key,
                                        TopologyNodeKey.forJobType(work.jobType()),
                                        List.of())));
        var run =
                runs.prepare(
                        new Request(
                                Mode.MANUAL,
                                "atomic-work",
                                attemptId.toString(),
                                "Atomic graph attempt",
                                null,
                                "{}"),
                        path);
        var parent =
                parent(
                        definition,
                        now,
                        run.id(),
                        Map.of(
                                "runPreparationVersion",
                                "1",
                                "businessDate",
                                work.businessDate().toString(),
                                "workType",
                                "GLOBAL",
                                "workKey",
                                work.jobType().name(),
                                "atomicAttemptId",
                                attemptId.toString(),
                                "expandedWorkIds",
                                List.of(work.id())));
        entities.persist(parent);
        entities.flush();
        var context = new JobRunWorkContext(parent, entities, jdbc, json, 1);
        Map<String, Object> childMetadata = new LinkedHashMap<>();
        if (!requiredOutputs.isEmpty()) childMetadata.putAll(outputDeclarations.metadata(attemptId, requiredOutputs));
        if (backfill) childMetadata.put("backfillPolicy", Map.of(
                "force", Boolean.TRUE.equals(requestMetadata.get("force")),
                "rerun", newAttempts(requestMetadata),
                "dataPolicy", Boolean.TRUE.equals(requestMetadata.get("force")) ? "REFRESH" : "PREFER_EXISTING",
                "verifyEodParquet", true));
        var child =
                producers
                        .getProducer(work.jobType())
                        .persistAtomicRunStep(
                                parent,
                                template.message(),
                                template.configuration(),
                                now,
                                context,
                                childMetadata);
        entities.flush();
        jdbc.update(
                "INSERT INTO job_run_step_preparations(job_run_id,step_index,parent_execution_id,child_count,message_count,closed_at) "
                        + "VALUES (?,0,?,1,1,?)",
                run.id(),
                parent.getId(),
                Timestamp.from(now));
        jdbc.update(
                "INSERT INTO atomic_work_attempts(id,work_id,job_run_id,parent_execution_id,execution_id,upstream_attempt_ids,created_at) "
                        + "VALUES (?,?,?,?,?,?::jsonb,?)",
                attemptId,
                work.id(),
                run.id(),
                parent.getId(),
                child.getId(),
                binding,
                Timestamp.from(now));
        for (UUID id : upstream)
            jdbc.update(
                    "INSERT INTO atomic_work_dependencies(attempt_id,upstream_attempt_id) VALUES (?,?)",
                    attemptId,
                    id);
        jdbc.update("UPDATE atomic_work_attempts SET sealed=true WHERE id=?", attemptId);
        if (!requiredOutputs.isEmpty())
            completionGate.enroll(
                    child.getId(),
                    attemptId,
                    requiredOutputs,
                    work.jobType().name().equals("EVALUATE_SIGNALS")
                            ? List.of("OUTPUT_UNCHANGED")
                            : List.of());
        return new Attempt(attemptId, child.getId());
    }

    private static JobExecutionHistory parent(
            JobDefinition definition, Instant now, UUID run, Map<String, Object> metadata) {
        var parent = new JobExecutionHistory();
        parent.setJob(definition);
        parent.setUsedSource(definition.getSource());
        parent.setStatus(JobStatus.PENDING);
        parent.setTriggeredAt(now);
        parent.setStartedAt(now);
        parent.setJobRunId(run);
        if (run != null) parent.setJobRunStepIndex(0);
        parent.setMetaJson(new LinkedHashMap<>(metadata));
        return parent;
    }

    private static boolean newAttempts(Map<String, Object> policy) {
        return Boolean.TRUE.equals(policy.get("force")) || Boolean.TRUE.equals(policy.get("rerun"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readObject(String value) {
        return json.readValue(value, Map.class);
    }

    /** Remove one owner's interest; reconciliation may later settle never-dispatched orphans. */
    @Transactional
    public boolean cancel(UUID requestId, String actor, Instant now) {
        var rows =
                jdbc.queryForList(
                        "SELECT execution_id FROM atomic_work_requests WHERE id=? AND actor=? AND state='PREPARED' FOR UPDATE",
                        requestId,
                        actor);
        if (rows.isEmpty()) return false;
        jdbc.update("UPDATE atomic_work_requests SET state='CANCELLED' WHERE id=?", requestId);
        jdbc.update(
                "UPDATE job_execution_histories SET status='BLOCKED',finished_at=?,error='REQUEST_CANCELLED' WHERE id=? AND status IN ('PENDING','RUNNING')",
                Timestamp.from(now),
                rows.getFirst().get("execution_id"));
        return true;
    }

    /** Explicit refresh/retry creates new membership; it never rewrites the old graph. */
    @Transactional(timeout = 120)
    public UUID retry(
            UUID previousRequest, String actor, String newKey, SchedulerClaim claim, Instant now) {
        var previous =
                jdbc.queryForMap(
                        "SELECT actor,state,target_definition_id,request_json::text AS parameters "
                                + "FROM atomic_work_requests WHERE id=?",
                        previousRequest);
        if (!actor.equals(previous.get("actor"))
                || "PREPARED".equals(previous.get("state"))
                || "PREPARING".equals(previous.get("state")))
            throw new IllegalArgumentException("Retry requires owned terminal request");
        if (jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM atomic_work_requests WHERE mode='MANUAL' AND actor=? AND idempotency_key=? AND id=?)",
                Boolean.class,
                actor,
                newKey,
                previousRequest))
            throw new IllegalArgumentException("Retry requires a new request key");
        return prepare(
                new Request(
                        Mode.MANUAL,
                        actor,
                        newKey,
                        "Retry of " + previousRequest,
                        null,
                        previous.get("parameters").toString()),
                (UUID) previous.get("target_definition_id"),
                claim,
                now,
                Map.of("retryOf", previousRequest.toString()));
    }

    /** Bounded operational snapshot; stored graph and attempts remain authority after restart. */
    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<String, Object> inspect(UUID requestId) {
        var request =
                jdbc.queryForMap(
                        "SELECT id,state,execution_id,target_definition_id,graph_json::text AS graph_json,created_at FROM atomic_work_requests WHERE id=?",
                        requestId);
        request.put("graph_json", readObject(request.get("graph_json").toString()));
        var members =
                jdbc.queryForList(
                        "SELECT m.work_id,m.is_target,a.id AS attempt_id,a.job_run_id,a.execution_id,e.status,e.started_at,e.finished_at,"
                                + "o.status AS outbox_status,c.required_outputs::text AS required_outputs,"
                                + LIVE_OWNER + " AS has_live_owner,"
                                + "coalesce((" + NEVER_DISPATCHED + "),false) AS never_dispatched,"
                                + "EXISTS(SELECT 1 FROM output_attempt_timeouts timeout WHERE timeout.execution_id=e.id) AS timed_out,"
                                + "CASE WHEN e.error IN ('REQUEST_CLOSED','ATOMIC_UPSTREAM_FAILED') THEN e.error END AS reason_code,"
                                + "coalesce((SELECT jsonb_agg(jsonb_build_object('dataset',w.dataset,'partition',w.partition_values,"
                                + "'dataVersion',w.data_version,'executionId',w.execution_id,'attemptId',w.atomic_attempt_id,"
                                + "'businessDate',w.business_date) ORDER BY w.dataset,w.partition_values::text) "
                                + "FROM output_work_ready_catalog w WHERE w.atomic_work_id=a.work_id),'[]'::jsonb)::text AS ready_outputs "
                                + "FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                                + "JOIN job_execution_histories e ON e.id=a.execution_id "
                                + "LEFT JOIN output_completion_contracts c ON c.execution_id=e.id "
                                + "LEFT JOIN LATERAL (SELECT * FROM scheduler_outbox_messages message WHERE message.execution_id=e.id "
                                + "ORDER BY message.message_index LIMIT 1) o ON true "
                                + "WHERE m.request_id=? ORDER BY m.work_id",
                        requestId);
        for (var member : members) {
            List<Map<String, Object>> outputs = json.readValue(member.remove("ready_outputs").toString(), List.class);
            Object requiredJson = member.remove("required_outputs");
            Boolean outputReady = outputs.isEmpty() ? null : true;
            if (requiredJson != null) {
                List<Map<String, Object>> required = json.readValue(requiredJson.toString(), List.class);
                outputReady = required.stream().allMatch(declaration -> outputs.stream().anyMatch(item ->
                        declaration.get("dataset").equals(item.get("dataset"))
                                && declaration.get("partition").equals(item.get("partition"))));
            }
            boolean orphaned = !Boolean.TRUE.equals(member.remove("has_live_owner"));
            boolean untouched = Boolean.TRUE.equals(member.remove("never_dispatched"));
            boolean timedOut = Boolean.TRUE.equals(member.remove("timed_out"));
            member.put("executionStatus", member.get("status"));
            member.put("outputReady", outputReady);
            member.put("readyOutputs", outputs);
            member.put("reasonCode", member.remove("reason_code"));
            member.put("orphaned", orphaned && !terminalMember(member.get("status").toString()));
            member.put("requiresExecutionReview", timedOut || (orphaned && !untouched
                    && !terminalMember(member.get("status").toString())));
        }
        Map<String, Long> counts = members.stream().collect(Collectors.groupingBy(
                member -> member.get("status").toString(), Collectors.counting()));
        long unfinished = members.stream().filter(member -> !terminalMember(member.get("status").toString())).count();
        boolean hasFailure = members.stream().anyMatch(member ->
                List.of("FAILED", "ERROR", "BLOCKED").contains(member.get("status").toString()));
        return Map.of("request", request, "members", members, "memberCounts", counts,
                "unfinishedMembers", unfinished, "isSettled", unfinished == 0, "hasFailure", hasFailure);
    }

    private static boolean terminalMember(String status) {
        return List.of("SUCCESS", "FAILED", "ERROR", "BLOCKED").contains(status);
    }

    private void reconcileOrphanedAttempts() {
        var candidates = jdbc.queryForList(
                "SELECT a.id,a.work_id FROM atomic_work_attempts a "
                        + "JOIN job_execution_histories e ON e.id=a.execution_id "
                        + "JOIN scheduler_outbox_messages o ON o.execution_id=e.id "
                        + "WHERE a.sealed AND e.status IN ('PENDING','RUNNING') AND " + NEVER_DISPATCHED
                        + " AND " + CLOSED_OWNER + " AND NOT " + LIVE_OWNER
                        + " ORDER BY a.work_id,a.id LIMIT 100");
        for (var candidate : candidates) {
            // Acceptance holds the same sorted work-slot locks before reusing/binding attempts.
            if (jdbc.queryForList(
                    "SELECT work_id FROM atomic_work_slots WHERE work_id=? FOR UPDATE SKIP LOCKED",
                    candidate.get("work_id")).isEmpty()) continue;
            var closed = jdbc.queryForList(
                    "UPDATE scheduler_outbox_messages o SET status='BLOCKED',dependency_reason='REQUEST_CLOSED',"
                            + "updated_at=CURRENT_TIMESTAMP FROM atomic_work_attempts a "
                            + "JOIN job_execution_histories e ON e.id=a.execution_id "
                            + "WHERE a.id=? AND a.sealed AND o.execution_id=e.id "
                            + "AND e.status IN ('PENDING','RUNNING') AND " + NEVER_DISPATCHED
                            + " AND " + CLOSED_OWNER + " AND NOT " + LIVE_OWNER
                            + " RETURNING o.execution_id",
                    candidate.get("id"));
            for (var row : closed) {
                jdbc.update(
                        "UPDATE job_execution_histories SET status='FAILED',finished_at=CURRENT_TIMESTAMP,"
                                + "error='REQUEST_CLOSED' WHERE id=? AND status IN ('PENDING','RUNNING')",
                        row.get("execution_id"));
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.scheduler.job-run.graph-reconcile-delay:5000}")
    @Transactional(timeout = 15)
    public void reconcile() {
        if (!enabled && !backfillEnabled) return;
        reconcileOrphanedAttempts();
        // Fail dependents only before publication; never rewrite a published/running worker
        // attempt.
        var failed =
                jdbc.queryForList(
                        "SELECT a.execution_id FROM atomic_work_attempts a JOIN scheduler_outbox_messages o ON o.execution_id=a.execution_id "
                                + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE a.sealed AND e.status IN ('PENDING','RUNNING') "
                                + "AND " + NEVER_DISPATCHED + " "
                                + "AND EXISTS(SELECT 1 FROM atomic_work_dependencies d JOIN atomic_work_attempts u ON u.id=d.upstream_attempt_id "
                                + "JOIN job_execution_histories h ON h.id=u.execution_id WHERE d.attempt_id=a.id AND h.status IN ('FAILED','ERROR','BLOCKED')) "
                                + "ORDER BY a.created_at,a.id LIMIT 100");
        for (var row : failed) {
            UUID execution = (UUID) row.get("execution_id");
            jdbc.update(
                    "UPDATE scheduler_outbox_messages o SET status='BLOCKED',dependency_reason='ATOMIC_UPSTREAM_FAILED',"
                            + "updated_at=CURRENT_TIMESTAMP FROM job_execution_histories e "
                            + "WHERE o.execution_id=e.id AND e.id=? AND " + NEVER_DISPATCHED,
                    execution);
            jdbc.update(
                    "UPDATE job_execution_histories e SET status='BLOCKED',finished_at=CURRENT_TIMESTAMP,error='ATOMIC_UPSTREAM_FAILED' "
                            + "WHERE e.id=? AND e.status IN ('PENDING','RUNNING') AND EXISTS(SELECT 1 FROM scheduler_outbox_messages o "
                            + "WHERE o.execution_id=e.id AND o.status='BLOCKED')",
                    execution);
        }
        jdbc.update(
                "UPDATE job_execution_histories p SET status=done.status,finished_at=coalesce(done.finished_at,CURRENT_TIMESTAMP),error=done.error "
                        + "FROM (SELECT a.parent_execution_id,e.status,e.finished_at,e.error FROM atomic_work_attempts a "
                        + "JOIN job_execution_histories e ON e.id=a.execution_id JOIN job_execution_histories root ON root.id=a.parent_execution_id "
                        + "WHERE root.status IN ('PENDING','RUNNING') AND e.status IN ('SUCCESS','FAILED','ERROR','BLOCKED') "
                        + "ORDER BY a.created_at,a.id LIMIT 100) done WHERE p.id=done.parent_execution_id AND p.status IN ('PENDING','RUNNING')");
        jdbc.update(
                "UPDATE job_runs r SET state=CASE WHEN done.status='SUCCESS' THEN 'SUCCESS' ELSE 'FAILED' END "
                        + "FROM (SELECT a.job_run_id,e.status FROM atomic_work_attempts a JOIN job_execution_histories e ON e.id=a.execution_id "
                        + "JOIN job_runs existing ON existing.id=a.job_run_id WHERE existing.state IN ('PREPARED','RUNNING') "
                        + "AND e.status IN ('SUCCESS','FAILED','ERROR','BLOCKED') ORDER BY a.created_at,a.id LIMIT 100) done "
                        + "WHERE r.id=done.job_run_id AND r.state IN ('PREPARED','RUNNING')");
        var requests =
                jdbc.queryForList(
                        "SELECT id,execution_id FROM atomic_work_requests WHERE state='PREPARED' "
                                + "AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED",
                        reconcileCursor,
                        reconcileCursor);
        if (requests.isEmpty()) {
            reconcileCursor = null;
            return;
        }
        reconcileCursor = (UUID) requests.getLast().get("id");
        for (var request : requests) {
            var statuses =
                    jdbc.queryForList(
                            "SELECT e.status FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                                    + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE m.request_id=?",
                            String.class,
                            request.get("id"));
            // Keep the owner PREPARED/admissible until every member has settled.
            // A failed branch cannot revoke dispatch rights from healthy siblings.
            if (statuses.stream().anyMatch(status -> !terminalMember(status))) continue;
            boolean failure =
                    statuses.stream()
                            .anyMatch(
                                    s ->
                                            s.equals("FAILED")
                                                    || s.equals("ERROR")
                                                    || s.equals("BLOCKED"));
            jdbc.update(
                    "UPDATE atomic_work_requests SET state=? WHERE id=?",
                    failure ? "FAILED" : "SUCCESS",
                    request.get("id"));
            jdbc.update(
                    "UPDATE job_execution_histories root SET status=?,finished_at=CURRENT_TIMESTAMP,error=?,"
                            + "records_synced=totals.synced,records_skipped=totals.skipped FROM ("
                            + "SELECT CASE WHEN count(*)=count(e.records_synced) THEN sum(e.records_synced) END AS synced,"
                            + "CASE WHEN count(*)=count(e.records_skipped) THEN sum(e.records_skipped) END AS skipped "
                            + "FROM atomic_work_request_members m JOIN atomic_work_attempts a ON a.id=m.attempt_id "
                            + "JOIN job_execution_histories e ON e.id=a.execution_id WHERE m.request_id=? AND m.is_target"
                            + ") totals WHERE root.id=? AND root.status IN ('PENDING','RUNNING')",
                    failure ? "FAILED" : "SUCCESS",
                    failure ? "ATOMIC_TARGET_FAILED" : null,
                    request.get("id"),
                    request.get("execution_id"));
        }
    }
}
