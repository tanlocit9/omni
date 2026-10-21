package com.omni.platform.modules.scheduler.producers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.modules.scheduler.runs.JobRunWorkContext;
import com.omni.platform.shared.executions.WorkIdentity;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    @Transactional(propagation = Propagation.MANDATORY)
    public int prepareRunStep(
            JobDefinition job, JobExecutionHistory parent, Instant now, JobRunWorkContext context) {
        List<KafkaMessage> messages = buildRunMessages(job, parent, now, context);
        jobService.enqueueRunDispatch(parent, getTopic(), messages, now, context.children());
        if (messages.isEmpty()) jobService.markParentWithNoChildren(parent, now);
        return messages.size();
    }

    protected JobExecutionHistory createChild(
            JobRunWorkContext context,
            JobExecutionHistory parent,
            WorkIdentity work,
            Map<String, Object> metadata,
            Instant now) {
        return context == null
                ? jobService.createChildExecution(parent.getId(), work, metadata, now)
                : context.child(work, metadata, now);
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

        List<KafkaMessage> messages = buildMessages(job, executionLog, now);
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
