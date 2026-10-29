package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.expansion.WorkExpansion;
import com.omni.platform.modules.scheduler.messaging.ExpandedJobMessage;
import com.omni.platform.modules.scheduler.messaging.JobMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.runs.JobRunWorkContext;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.executions.WorkIdentity;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@RequiredArgsConstructor
public abstract class JobProducer {

    protected final JobService jobService;
    protected final KafkaPublisher kafkaPublisher;

    /**
     * Job type handled by this producer.
     */
    public abstract JobType getJobType();

    /**
     * Kafka topic used by this producer.
     */
    protected abstract String getTopic();

    /**
     * Build Kafka messages for the given job.
     */
    protected abstract List<KafkaMessage> buildMessages(
            JobDefinition job, JobExecutionHistory log, Instant timestamps);

    /** Explicit bounded preparation capability; an unadapted producer fails closed. */
    protected List<KafkaMessage> buildRunMessages(
            JobDefinition job, JobExecutionHistory parent, Instant now, JobRunWorkContext context) {
        throw new IllegalArgumentException("JOB_RUN_UNSUPPORTED_PRODUCER: " + getJobType());
    }

    /** Resolve through I8 without claim, persistence, outbox or Kafka side effects. */
    public List<ExpandedJobMessage> previewAtomicWork(
            JobDefinition job, Map<String, Object> request, Instant acceptedAt,
            JdbcTemplate jdbc, JsonMapper json) {
        var parent = new JobExecutionHistory();
        parent.setId(UUID.randomUUID());
        parent.setJob(job);
        parent.setUsedSource(job.getSource());
        parent.setMetaJson(Map.of("expansionRequest", WorkExpansion.freeze(request),
                "businessDate", acceptedAt.atZone(WorkExpansion.ZONE).toLocalDate().toString()));
        var context = JobRunWorkContext.preview(parent, jdbc, json);
        return expandMessages(job, parent, acceptedAt, context).stream()
                .map(message -> (ExpandedJobMessage) message.payload()).toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int prepareRunStep(
            JobDefinition job, JobExecutionHistory parent, Instant now, JobRunWorkContext context) {
        List<KafkaMessage> messages = expandMessages(job, parent, now, context);
        jobService.enqueueRunDispatch(parent, getTopic(), messages, now, context.children());
        if (messages.isEmpty()) jobService.markParentWithNoChildren(parent, now);
        return messages.size();
    }

    public record AcceptedRunWork(List<KafkaMessage> messages, List<JobExecutionHistory> children) {
        public AcceptedRunWork {
            messages = List.copyOf(messages);
            children = List.copyOf(children);
        }
    }

    /** Resolve membership once, before the run's aggregate budget admits any child writes. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AcceptedRunWork acceptRunStep(JobDefinition job, JobExecutionHistory parent,
                                        Instant now, JobRunWorkContext preview) {
        var messages = expandMessages(job, parent, now, preview);
        return new AcceptedRunWork(messages, preview.children());
    }

    /** Materialize accepted work; execution UUID allocation is not a second expansion. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int persistAcceptedRunStep(JobExecutionHistory parent, AcceptedRunWork accepted,
                                      Instant now, JobRunWorkContext context) {
        Map<UUID, JobExecutionHistory> previews = new LinkedHashMap<>();
        accepted.children().forEach(child -> previews.put(child.getId(), child));
        List<KafkaMessage> messages = new ArrayList<>();
        for (KafkaMessage message : accepted.messages()) {
            var work = (ExpandedJobMessage) message.payload();
            var preview = previews.get(work.executionId());
            if (preview == null || !parent.getId().equals(work.parentExecutionId())
                    || !work.atomicWorkId().equals(preview.getMetaJson().get("workIdentity")))
                throw new IllegalArgumentException("Accepted run work identity mismatch");
            var child = context.child(WorkIdentity.of(work.workType(), work.workKey()),
                    preview.getMetaJson(), now);
            child.setUsedSource(preview.getUsedSource());
            var command = copyIdentity(work.command(), child.getId(), parent.getId(),
                    work.businessDate(), work.metadata());
            messages.add(new KafkaMessage(message.key(), new ExpandedJobMessage(command,
                    work.jobType(), work.businessDate(), work.parameters(), work.atomicWorkId())));
        }
        jobService.enqueueRunDispatch(parent, getTopic(), messages, now, context.children());
        if (messages.isEmpty()) jobService.markParentWithNoChildren(parent, now);
        return messages.size();
    }

    /** Persist a single graph descriptor without resolving membership a second time. */
    @Transactional(propagation = Propagation.MANDATORY)
    public JobExecutionHistory persistAtomicRunStep(JobExecutionHistory parent,
            ExpandedJobMessage accepted, Map<String,Object> configuration,
            Instant now, JobRunWorkContext context) {
        if (getJobType() != JobType.valueOf(accepted.jobType()))
            throw new IllegalArgumentException("Atomic producer mismatch");
        Map<String,Object> metadata = new LinkedHashMap<>(accepted.metadata());
        metadata.put("workBusinessDate", accepted.businessDate().toString());
        var child = context.child(WorkIdentity.of(accepted.workType(), accepted.workKey()), metadata, now);
        Map<String,Object> snapshot = WorkExpansion.freeze(Map.of(
                "jobType", accepted.jobType(), "workType", accepted.workType().name(),
                "workKey", accepted.workKey(), "businessDate", accepted.businessDate().toString(),
                "parameters", accepted.parameters(), "atomicWorkId", accepted.atomicWorkId(),
                "configuration", configuration));
        context.snapshot(child.getId(), snapshot, accepted.source());
        var command = copyIdentity(accepted.command(), child.getId(), parent.getId(),
                accepted.businessDate(), accepted.metadata());
        var message = new ExpandedJobMessage(command, accepted.jobType(), accepted.businessDate(),
                accepted.parameters(), accepted.atomicWorkId());
        jobService.enqueueRunDispatch(parent, getTopic(),
                List.of(new KafkaMessage(accepted.workKey(), message)), now, context.children());
        return child;
    }

    protected JobExecutionHistory createChild(
            JobRunWorkContext context,
            JobExecutionHistory parent,
            WorkIdentity work,
            Map<String, Object> metadata,
            Instant now) {
        if (parent.getMetaJson() != null && parent.getMetaJson().get("workBusinessDate") != null)
            metadata.put("workBusinessDate", parent.getMetaJson().get("workBusinessDate"));
        return context == null
                ? jobService.createChildExecution(parent.getId(), work, metadata, now)
                : context.child(work, metadata, now);
    }

    /** The one preparation boundary used by legacy, internal manual and JobRun routes. */
    private List<KafkaMessage> expandMessages(
            JobDefinition job, JobExecutionHistory parent, Instant now, JobRunWorkContext context) {
        WorkExpansion.Plan plan = WorkExpansion.resolve(job, parent, now);
        Map<String, Object> parentMetadata =
                new LinkedHashMap<>(parent.getMetaJson() == null ? Map.of() : parent.getMetaJson());
        parentMetadata.put("acceptedExpansion", plan.accepted());
        parent.setMetaJson(parentMetadata);
        List<KafkaMessage> result = new ArrayList<>();
        List<String> membership = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        List<KafkaMessage> templates = List.of();
        if (!plan.days().isEmpty()) {
            var firstJob = WorkExpansion.definition(job, plan, plan.days().getFirst());
            var firstParent = WorkExpansion.parent(parent, firstJob, plan.days().getFirst());
            // Resolve the symbol/sector universe once. Every day uses this closed selection.
            templates = buildRunMessages(firstJob, firstParent, now, context);
        }
        for (LocalDate day : plan.days()) {
            JobDefinition effective = WorkExpansion.definition(job, plan, day);
            JobExecutionHistory view = WorkExpansion.parent(parent, effective, day);
            for (KafkaMessage built : templates) {
                if (!(built.payload() instanceof JobMessage command))
                    throw new IllegalArgumentException("Expected atomic job command");
                if (command.executionId().equals(parent.getId())
                        || !day.equals(plan.days().getFirst())) {
                    Map<String, Object> metadata = new LinkedHashMap<>(command.metadata());
                    metadata.put("workBusinessDate", day.toString());
                    if (metadata.containsKey("tradingDate"))
                        metadata.put("tradingDate", day.toString());
                    if (metadata.containsKey("evaluationDate"))
                        metadata.put("evaluationDate", day.toString());
                    var child =
                            createChild(
                                    context,
                                    view,
                                    WorkIdentity.of(command.workType(), command.workKey()),
                                    metadata,
                                    now);
                    command = copyIdentity(command, child.getId(), parent.getId(), day, metadata);
                }
                if (command.workType() != WorkExpansion.unit(job.getJobType()))
                    throw new IllegalArgumentException(
                            "Unexpected atomic unit for " + getJobType());
                var parameters = WorkExpansion.parameters(command, plan.config());
                String identity =
                        WorkExpansion.identity(
                                getJobType(),
                                command.workType(),
                                command.workKey(),
                                day,
                                parameters);
                Map<String, Object> snapshot =
                        WorkExpansion.freeze(
                                Map.of(
                                        "jobType",
                                        getJobType().name(),
                                        "workType",
                                        command.workType().name(),
                                        "workKey",
                                        command.workKey(),
                                        "businessDate",
                                        day.toString(),
                                        "parameters",
                                        parameters,
                                        "atomicWorkId",
                                        identity,
                                        "configuration",
                                        WorkExpansion.freeze(effective.getConfigJson())));
                if (!identities.add(identity))
                    throw new IllegalArgumentException("Duplicate expanded atomic work");
                membership.add(identity);
                if (result.size() >= WorkExpansion.MAX_WORK)
                    throw new IllegalArgumentException("WORK_EXPANSION_BOUND_EXCEEDED");
                if (context == null)
                    jobService.snapshotExpandedWork(
                            command.executionId(), snapshot, command.source());
                else context.snapshot(command.executionId(), snapshot, command.source());
                result.add(
                        new KafkaMessage(
                                built.key(),
                                new ExpandedJobMessage(
                                        command, getJobType().name(), day, parameters, identity)));
            }
        }
        parentMetadata.put("expandedWorkIds", List.copyOf(membership));
        parent.setMetaJson(parentMetadata);
        return List.copyOf(result);
    }

    private static JobMessage copyIdentity(
            JobMessage command,
            UUID execution,
            UUID parent,
            LocalDate day,
            Map<String, Object> metadata) {
        var components = command.getClass().getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        try {
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] =
                        switch (components[i].getName()) {
                            case "executionId" -> execution;
                            case "parentExecutionId" -> parent;
                            case "metadata" -> metadata;
                            case "tradingDate", "evaluationDate" -> day;
                            case "fromOffset" -> day.atStartOfDay(WorkExpansion.ZONE).toInstant();
                            case "toOffset" ->
                                    day.plusDays(1)
                                            .atStartOfDay(WorkExpansion.ZONE)
                                            .toInstant()
                                            .minusSeconds(1);
                            default -> components[i].getAccessor().invoke(command);
                        };
            }
            return (JobMessage)
                    command.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Optional hook executed after publishing.
     */
    protected void postPublish(JobDefinition job, Instant timestamps) {}

    /**
     * Template method.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID prepareDispatch(JobDefinition job, SchedulerClaim claim, Instant now) {
        return prepareDispatch(job, claim, now, Map.of());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID prepareDispatch(
            JobDefinition job,
            SchedulerClaim claim,
            Instant now,
            Map<DatasetRef, String> approvedInputVersions) {

        return prepareDispatchInternal(job, claim, now, approvedInputVersions, Map.of(), false);
    }

    /**
     * Uses the normal outbox path for an operator trigger while preserving the
     * definition's scheduled next-run time.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID prepareManualDispatch(
            JobDefinition job,
            SchedulerClaim claim,
            Instant now,
            Map<DatasetRef, String> approvedInputVersions,
            Map<String, Object> manualTriggerMetadata) {
        return prepareDispatchInternal(
                job, claim, now, approvedInputVersions, manualTriggerMetadata, true);
    }

    private UUID prepareDispatchInternal(
            JobDefinition job,
            SchedulerClaim claim,
            Instant now,
            Map<DatasetRef, String> approvedInputVersions,
            Map<String, Object> manualTriggerMetadata,
            boolean manual) {
        log.debug(
                "Preparing {} job [{}] type [{}] source [{}] at [{}]",
                manual ? "manual" : "scheduled",
                job.getId(),
                job.getJobType(),
                job.getSource(),
                now);
        JobExecutionHistory executionLog =
                manual
                        ? jobService.prepareManualClaimedExecution(
                                job, claim, now, approvedInputVersions, manualTriggerMetadata)
                        : jobService.prepareClaimedExecution(
                                job, claim, now, approvedInputVersions);

        List<KafkaMessage> messages = expandMessages(job, executionLog, now, null);
        log.info(
                "Built {} Kafka message(s) for job [{}] execution [{}] topic [{}]",
                messages.size(),
                job.getId(),
                executionLog.getId(),
                getTopic());

        if (messages.isEmpty()) {
            log.warn(
                    "No Kafka messages built for job [{}] execution [{}]; marking parent with no children",
                    job.getId(),
                    executionLog.getId());
            jobService.markParentWithNoChildren(executionLog, now);
            jobService.releaseClaim(claim);
            postPublish(job, now);
            return executionLog.getId();
        }

        jobService.enqueueDispatch(executionLog, getTopic(), messages, now);
        jobService.releaseClaim(claim);

        postPublish(job, now);
        return executionLog.getId();
    }
}
