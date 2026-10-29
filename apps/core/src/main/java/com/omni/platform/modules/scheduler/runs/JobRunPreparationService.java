package com.omni.platform.modules.scheduler.runs;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.expansion.GraphWorkExpansion;
import com.omni.platform.modules.scheduler.producers.JobProducerRegistry;
import com.omni.platform.modules.scheduler.producers.JobProducer;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobRunRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Handoff;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeEdge;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.StepKey;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver;
import com.omni.platform.modules.scheduler.topology.StaticJobTopologyProvider;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import tools.jackson.databind.json.JsonMapper;

/** Bounded atomic preparation only. FIFO is not a predecessor-completion barrier: leave disabled until I4. */
@Service
public class JobRunPreparationService {
    public static final int MAX_STEPS = 32;
    public static final int MAX_STEP_WORK = 5000;
    public static final int MAX_RUN_WORK = 20000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JobRunRepository runs;
    private final JobDefinitionRepository definitions;
    private final JobProducerRegistry producers;
    private final StaticJobTopologyProvider topology;
    private final JobService jobs;
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    private final JsonMapper json;
    private final boolean enabled;

    public JobRunPreparationService(
            JobRunRepository runs,
            JobDefinitionRepository definitions,
            JobProducerRegistry producers,
            StaticJobTopologyProvider topology,
            JobService jobs,
            JdbcTemplate jdbc,
            EntityManager entities,
            @Qualifier("jsonMapper") JsonMapper json,
            @Value("${app.scheduler.job-run.preparation-enabled:false}") boolean enabled) {
        this.runs = runs;
        this.definitions = definitions;
        this.producers = producers;
        this.topology = topology;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.entities = entities;
        this.json = json;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * I9.a read-only planning API, using the same I8 producer expansion as every creation route.
     * It does not acquire work, persist attempts or activate the scheduler's preparation path.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ,
            propagation = Propagation.REQUIRES_NEW)
    public GraphWorkExpansion.Plan planGraphExpansion(
            UUID target, Map<String, Object> request, Instant acceptedAt) {
        Map<String, Boolean> membership = new HashMap<>();
        return GraphWorkExpansion.resolve(target, request, acceptedAt, definitions.findAll(),
                (definition, expansionRequest, instant) -> producers.getProducer(definition.getJobType())
                        .previewAtomicWork(definition, expansionRequest, instant, jdbc, json),
                (symbol, sector, level) -> membership.computeIfAbsent(
                        symbol + ":" + level + ":" + sector,
                        ignored -> Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT EXISTS (SELECT 1 FROM symbols WHERE is_active=true "
                                        + "AND exchange || '-' || code=? "
                                        + "AND meta_json->>CAST(? AS text)=?)",
                                Boolean.class, symbol, "sectorLv" + level + "Code", sector))));
    }

    public boolean supports(JobDefinition definition) {
        return enabled
                && definition.getCronExpr() != null
                && !definition.getCronExpr().isBlank()
                && topology.definitionDependencies()
                        .predecessors()
                        .containsKey(
                                new JobDefinitionIdentity(
                                        definition.getSource(),
                                        definition.getJobType(),
                                        definition.getCronExpr()));
    }

    public boolean supportsManual(JobDefinition definition, Map<String, Object> parameters) {
        // Historical/range intraday overrides retain the existing manual route until its I3.d
        // cutover.
        return supports(definition)
                && (definition.getJobType() != JobType.SYNC_INTRADAY_EOD || parameters.isEmpty());
    }

    @Transactional(timeout = 60)
    public UUID prepareScheduled(JobDefinition target, SchedulerClaim claim, Instant now) {
        Instant due = Objects.requireNonNull(claim.scheduledFor(), "scheduledFor");
        Request request =
                new Request(
                        Mode.SCHEDULED,
                        "scheduler",
                        target.getId() + ":" + due.toEpochMilli(),
                        "Scheduled target",
                        null,
                        "{}");
        return prepare(request, target.getId(), claim, now, Map.of());
    }

    @Transactional(timeout = 60)
    public UUID prepareManual(
            JobDefinition target,
            SchedulerClaim claim,
            Instant now,
            String actor,
            String key,
            String reason,
            Map<String, Object> parameters,
            Map<String, Object> metadata) {
        metadata = new LinkedHashMap<>(metadata);
        metadata.put("expansionRequest", parameters);
        return prepare(
                new Request(
                        Mode.MANUAL, actor, key, reason, null, json.writeValueAsString(parameters)),
                target.getId(),
                claim,
                now,
                metadata);
    }

    private UUID prepare(
            Request request,
            UUID targetId,
            SchedulerClaim claim,
            Instant now,
            Map<String, Object> metadata) {
        if (!enabled) throw new IllegalStateException("JOB_RUN_PREPARATION_DISABLED");
        if (!targetId.equals(claim.jobDefinitionId()))
            throw new IllegalArgumentException("JobRun claim target mismatch");
        var existing = runs.findByRequest(request);
        if (existing.isPresent()) {
            var run = existing.get();
            if (!run.definitionIds().getLast().equals(targetId))
                throw new IllegalArgumentException("JOB_RUN_IDEMPOTENCY_CONFLICT: target changed");
            // Match fresh preparation's definition-before-run order. The later lease
            // release must not wait for a definition while holding the run lock.
            jdbc.queryForObject(
                    "SELECT id FROM job_definitions WHERE id=? FOR UPDATE", UUID.class, targetId);
            lockRun(run.id());
            UUID result = closedTarget(run);
            // A fresh lease acquired for a retry is released; no cadence or membership is
            // rewritten.
            releaseReplayClaim(claim);
            return result;
        }
        JobDefinition target = definitions.findById(targetId).orElseThrow();
        Scope scope = new Scope(ScopeType.GLOBAL, "VN", now.atZone(ZONE).toLocalDate());
        var declaration = topology.definitionDependencies();
        JobDefinitionIdentity identity =
                new JobDefinitionIdentity(
                        target.getSource(), target.getJobType(), target.getCronExpr());
        Map<JobDefinitionIdentity, Set<Scope>> capabilities = new HashMap<>();
        Map<ScopeEdge, Scope> mappings = new HashMap<>();
        Map<TopologyNodeKey, List<JobDefinitionIdentity>> bindings = new HashMap<>();
        declaration
                .predecessors()
                .forEach(
                        (definition, upstream) -> {
                            capabilities.put(definition, Set.of(scope));
                            upstream.forEach(
                                    predecessor ->
                                            mappings.put(
                                                    new ScopeEdge(
                                                            new StepKey(definition, scope),
                                                            predecessor),
                                                    scope));
                        });
        // Explicitly select every declared signal strategy; never choose an arbitrary same-type
        // definition.
        declaration.predecessors().keySet().stream()
                .collect(Collectors.groupingBy(declaration.topology()::nodeFor))
                .forEach(
                        (node, values) -> {
                            if (values.size() > 1)
                                bindings.put(node, values.stream().sorted().toList());
                        });
        var path =
                new RequiredPathResolver(declaration)
                        .resolveRequiredPath(
                                identity,
                                scope,
                                new Handoff(bindings, capabilities, mappings),
                                32,
                                MAX_STEPS);
        // Lock before freezing FK-backed steps: taking KEY SHARE references first and then
        // upgrading to FOR UPDATE can deadlock independent runs with common ancestors.
        List<UUID> ids = runs.resolveDefinitions(path).stream().distinct().sorted().toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbc.queryForList(
                "SELECT id FROM job_definitions WHERE id IN ("
                        + placeholders
                        + ") ORDER BY id FOR UPDATE",
                ids.toArray());
        var run = runs.prepare(request, path);
        lockRun(run.id());
        Integer alreadyClosed =
                jdbc.queryForObject(
                        "SELECT count(*) FROM job_run_step_preparations WHERE job_run_id=?",
                        Integer.class,
                        run.id());
        if (alreadyClosed != 0) {
            UUID result = closedTarget(run);
            releaseReplayClaim(claim);
            return result;
        }
        Map<UUID, JobDefinition> catalog =
                definitions.findAllById(ids).stream()
                        .collect(Collectors.toMap(JobDefinition::getId, job -> job));
        JobDefinition claimed = catalog.get(targetId);
        // The target may already be managed from the pre-lock lookup. Reload its fence under
        // the row lock so a concurrent lease change cannot be overwritten by stale JPA state.
        entities.refresh(claimed);
        if (!targetId.equals(claim.jobDefinitionId())
                || !claim.claimToken().equals(claimed.getClaimToken())
                || !claim.claimedBy().equals(claimed.getClaimedBy())
                || claimed.getClaimUntil() == null
                || !claimed.getClaimUntil().isAfter(now)) {
            throw new IllegalStateException("JobRun target claim is stale");
        }
        int work = 0;
        UUID targetExecution = null;
        record AcceptedStep(JobDefinition definition, JobExecutionHistory parent,
                            JobProducer.AcceptedRunWork work) {}
        List<AcceptedStep> accepted = new ArrayList<>();
        for (int index = 0; index < run.definitionIds().size(); index++) {
            JobDefinition definition = catalog.get(run.definitionIds().get(index));
            if (definition == null || !Boolean.TRUE.equals(definition.getIsActive()))
                throw new IllegalArgumentException(
                        "JobRun requires an active executable definition");
            if (definition.getJobType() == JobType.SYNC_INTRADAY_EOD
                    && scope.businessDate().getDayOfWeek().getValue() > 5)
                throw new IllegalArgumentException(
                        "JobRun intraday date must be a trading weekday");
            JobExecutionHistory parent = new JobExecutionHistory();
            parent.setJob(definition);
            parent.setUsedSource(definition.getSource());
            parent.setStatus(JobStatus.PENDING);
            parent.setTriggeredAt(now);
            parent.setJobRunId(run.id());
            parent.setJobRunStepIndex(index);
            Map<String, Object> values = new LinkedHashMap<>();
            if (index == run.definitionIds().size() - 1) values.putAll(metadata);
            values.put("runPreparationVersion", "1");
            values.put("businessDate", scope.businessDate().toString());
            values.put("workType", "GLOBAL");
            values.put("workKey", definition.getJobType().name());
            if (definition.getJobType() == JobType.SYNC_INTRADAY_EOD)
                values.put(
                        "metadataTarget", Map.of("tradingDate", scope.businessDate().toString()));
            parent.setMetaJson(values);
            entities.persist(parent);
            JobRunWorkContext context =
                    JobRunWorkContext.preview(
                            parent,
                            jdbc,
                            json,
                            Math.min(MAX_STEP_WORK, MAX_RUN_WORK - work));
            var resolved =
                    producers
                            .getProducer(definition.getJobType())
                            .acceptRunStep(definition, parent, now, context);
            work += resolved.messages().size();
            if (work > MAX_RUN_WORK)
                throw new IllegalArgumentException("JOB_RUN_WORK_BOUND_EXCEEDED");
            accepted.add(new AcceptedStep(definition, parent, resolved));
        }
        // Only closed, budget-checked descriptors may become execution/outbox rows.
        entities.flush();
        for (int index = 0; index < accepted.size(); index++) {
            var step = accepted.get(index);
            var parent = step.parent();
            var context = new JobRunWorkContext(parent, entities, jdbc, json, MAX_STEP_WORK);
            int messages = producers.getProducer(step.definition().getJobType())
                    .persistAcceptedRunStep(parent, step.work(), now, context);
            entities.flush();
            jdbc.update(
                    """
                    INSERT INTO job_run_step_preparations(job_run_id, step_index, parent_execution_id,
                        child_count, message_count, closed_at) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    run.id(),
                    index,
                    parent.getId(),
                    context.children().size(),
                    messages,
                    Timestamp.from(now));
            // Keep dirty checking bounded to one closed step, not the whole fan-out.
            entities.clear();
            targetExecution = parent.getId();
        }
        jobs.completeRunPreparation(claimed, claim, now, request.mode() == Mode.MANUAL);
        return Objects.requireNonNull(targetExecution);
    }

    private void lockRun(UUID id) {
        jdbc.queryForObject("SELECT id FROM job_runs WHERE id=? FOR UPDATE", UUID.class, id);
    }

    private void releaseReplayClaim(SchedulerClaim claim) {
        UUID current =
                jdbc.queryForObject(
                        "SELECT claim_token FROM job_definitions WHERE id=? FOR UPDATE",
                        UUID.class,
                        claim.jobDefinitionId());
        if (current == null) return;
        if (!current.equals(claim.claimToken()))
            throw new IllegalStateException("JobRun retry claim is stale");
        jobs.releaseClaim(claim);
    }

    private UUID closedTarget(JobRunSnapshot run) {
        List<UUID> parents =
                jdbc.query(
                        "SELECT parent_execution_id FROM job_run_step_preparations WHERE job_run_id=? ORDER BY step_index",
                        (rs, row) -> rs.getObject(1, UUID.class),
                        run.id());
        if (parents.size() != run.definitionIds().size())
            throw new IllegalStateException("JOB_RUN_INCOMPLETE_PREPARATION");
        return parents.getLast();
    }
}
