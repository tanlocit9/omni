package com.omni.platform.modules.scheduler.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.omni.platform.modules.notifications.events.OperationalNotificationEvent;
import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.modules.scheduler.messaging.JobStatusMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.notifications.JobNotificationContext;
import com.omni.platform.modules.scheduler.notifications.JobNotificationPolicyRegistry;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.shared.utils.MetadataUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Coordinates scheduler job execution state transitions and job-level
 * operational notification decisions.
 * <p>
 * This service is the transactional boundary for applying status messages,
 * aggregating fan-out parent executions, enforcing terminal-state guards, and
 * publishing channel-neutral {@link OperationalNotificationEvent}s. Consumers
 * should delegate status changes here instead of making notification decisions
 * directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobDefinitionRepository jobDefinitionRepository;
    private final JobExecutionHistoryRepository jobExecutionHistoryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final JobNotificationPolicyRegistry jobNotificationPolicyRegistry;
    private final SchedulerOutboxService schedulerOutboxService;

    @Value("${app.scheduler.zone:Asia/Ho_Chi_Minh}")
    private String schedulerZone;

    /**
     * Creates a pending execution log for the supplied job and advances the job's
     * next run time when it is cron-driven.
     *
     * @param job job definition about to be executed
     * @param now scheduler timestamp used for next-run calculation
     * @return newly persisted pending execution log
     */
    public JobExecutionHistory prepareForExecution(
            JobDefinition job,
            Instant now) {

        JobExecutionHistory log = createPendingLog(job);

        updateNextRun(job, now);

        jobExecutionHistoryRepository.save(log);
        jobDefinitionRepository.save(job);

        return log;
    }

    public JobExecutionHistory prepareClaimedExecution(
            JobDefinition job,
            SchedulerClaim claim,
            Instant now) {
        return prepareClaimedExecution(job, claim, now, Map.of());
    }

    public JobExecutionHistory prepareClaimedExecution(
            JobDefinition job,
            SchedulerClaim claim,
            Instant now,
            Map<DatasetRef, String> approvedInputVersions) {
        if (!job.getId().equals(claim.jobDefinitionId())
                || !Objects.equals(job.getClaimToken(), claim.claimToken())
                || !Objects.equals(job.getClaimedBy(), claim.claimedBy())) {
            throw new IllegalStateException("Scheduler claim no longer owns job definition " + job.getId());
        }

        JobExecutionHistory execution = prepareForExecution(job, now);
        if (!approvedInputVersions.isEmpty()) {
            execution.setMetaJson(Map.of(
                    "approvedInputs",
                    approvedInputVersions.entrySet().stream()
                            .map(entry -> Map.<String, Object>of(
                                    "dataset",
                                    entry.getKey().getDataset(),
                                    "partition",
                                    entry.getKey().getPartition(),
                                    "dataVersion",
                                    entry.getValue()))
                            .toList()));
            jobExecutionHistoryRepository.save(execution);
        }
        return execution;
    }

    public void enqueueDispatch(
            JobExecutionHistory execution,
            String topic,
            List<KafkaMessage> messages,
            Instant now) {
        schedulerOutboxService.enqueue(execution, topic, messages, now);
    }

    public void releaseClaim(SchedulerClaim claim) {
        boolean released = jobDefinitionRepository.releaseClaim(claim);
        if (!released) {
            throw new IllegalStateException("Scheduler claim was superseded for job " + claim.jobDefinitionId());
        }
    }

    /**
     * Creates a running child execution for fan-out work.
     * <p>
     * Child executions inherit the parent's job definition and retain the parent id
     * as the durable source of truth used later by aggregation.
     *
     * @param parentLogId parent execution id
     * @param workKey work item identifier represented by the child task
     * @param metadata additional metadata to store on the child execution
     * @param now child start timestamp
     * @return persisted running child execution
     */
    public JobExecutionHistory createChildExecution(
            UUID parentLogId,
            String workKey,
            Map<String, Object> metadata,
            Instant now) {

        JobExecutionHistory parent = jobExecutionHistoryRepository.findById(parentLogId)
                .orElseThrow(() -> new IllegalArgumentException("Parent job execution not found: " + parentLogId));

        JobExecutionHistory child = createPendingLog(parent.getJob());
        child.setParentLogId(parent.getId());
        child.setStartedAt(now);
        child.setStatus(JobStatus.RUNNING);

        Map<String, Object> meta = new LinkedHashMap<>();
        MetadataUtils.putIfPresent(meta, "symbolKey", workKey);
        MetadataUtils.putIfPresent(meta, "workKey", workKey);
        putAllAsStrings(meta, metadata);
        child.setMetaJson(meta);

        return jobExecutionHistoryRepository.save(child);
    }

    /**
     * Marks a fan-out parent as a successful no-op when message production yields no
     * child work.
     * <p>
     * Zero-child executions are intentionally silent operationally: the database
     * state is finalized, but no notification event is published. Terminal parents
     * are never reopened.
     *
     * @param execution parent execution to finalize
     * @param finishedAt timestamp to use for start and finish fields
     */
    @Transactional
    public void markParentWithNoChildren(
            JobExecutionHistory execution,
            Instant finishedAt) {

        if (isTerminal(execution.getStatus())) {
            log.debug("Ignoring zero-child transition for terminal parent execution {} with status {}",
                    execution.getId(), execution.getStatus());
            return;
        }

        execution.setStatus(JobStatus.SUCCESS);
        execution.setStartedAt(finishedAt);
        execution.setFinishedAt(finishedAt);
        execution.setRecordsSynced(0);
        execution.setRecordsSkipped(0);
        execution.setError(null);

        Map<String, Object> meta = new LinkedHashMap<>();
        putAllAsStrings(meta, execution.getMetaJson());
        MetadataUtils.putIfPresent(meta, "childCount", 0);
        MetadataUtils.putIfPresent(meta, "successCount", 0);
        MetadataUtils.putIfPresent(meta, "failedCount", 0);
        MetadataUtils.putIfPresent(meta, "pendingCount", 0);
        MetadataUtils.putIfPresent(meta, "runningCount", 0);
        execution.setMetaJson(meta);

        jobExecutionHistoryRepository.save(execution);
    }

    /**
     * Marks a directly managed execution as successful.
     * <p>
     * Used by synchronous in-process handlers that already own the work result.
     * Terminal executions are treated as closed and ignored to avoid reopening or
     * duplicating terminal side effects.
     *
     * @param execution execution to update
     * @param recordsSynced number of records synced by the work item
     * @param recordsSkipped number of records skipped by the work item
     * @param finishedAt finish timestamp
     * @param metadata metadata to merge into the execution row
     */
    public void markExecutionSuccess(
            JobExecutionHistory execution,
            int recordsSynced,
            int recordsSkipped,
            Instant finishedAt,
            Map<String, Object> metadata) {

        if (isTerminal(execution.getStatus())) {
            log.debug("Ignoring success transition for terminal execution {} with status {}",
                    execution.getId(), execution.getStatus());
            return;
        }

        mergeMetadata(execution, metadata);
        execution.setStatus(JobStatus.SUCCESS);
        execution.setRecordsSynced(recordsSynced);
        execution.setRecordsSkipped(recordsSkipped);
        execution.setFinishedAt(finishedAt);
        execution.setError(null);

        jobExecutionHistoryRepository.save(execution);
    }

    /**
     * Marks a directly managed execution as failed.
     * <p>
     * Used by synchronous in-process handlers that already own the work result.
     * Terminal executions are treated as closed and ignored to avoid reopening or
     * duplicating terminal side effects.
     *
     * @param execution execution to update
     * @param error failure message to persist
     * @param finishedAt finish timestamp
     * @param metadata metadata to merge into the execution row
     */
    public void markExecutionFailed(
            JobExecutionHistory execution,
            String error,
            Instant finishedAt,
            Map<String, Object> metadata) {

        if (isTerminal(execution.getStatus())) {
            log.debug("Ignoring failed transition for terminal execution {} with status {}",
                    execution.getId(), execution.getStatus());
            return;
        }

        mergeMetadata(execution, metadata);
        execution.setStatus(JobStatus.FAILED);
        execution.setRecordsSynced(0);
        execution.setRecordsSkipped(1);
        execution.setFinishedAt(finishedAt);
        execution.setError(error);

        jobExecutionHistoryRepository.save(execution);
    }

    /**
     * Applies an external job status message in one transaction.
     * <p>
     * The method normalizes incoming {@code ERROR} statuses to {@code FAILED},
     * persists the execution update, suppresses duplicate or regressive updates for
     * terminal rows, delegates child completion to locked parent aggregation, and
     * publishes a standalone notification only for the first non-terminal to
     * terminal transition.
     * <p>
     * The persisted {@code parentLogId} is authoritative. Any parent id present in
     * the incoming message is only validated and logged when it disagrees.
     *
     * @param response parsed status message from the job-status Kafka topic
     */
    @Transactional
    public void applyStatus(JobStatusMessage response) {
        log.info(
                "Applying job status executionId={} parentExecutionId={} symbolKey={} status={} txActive={} txName={} metaKeys={}",
                response.executionId(), response.parentExecutionId(), response.symbolKey(), response.status(),
                TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.getCurrentTransactionName(),
                response.metaJson() == null ? null : response.metaJson().keySet());
        UUID executionId = parseUuid(response.executionId(), "executionId");
        if (executionId == null) {
            return;
        }

        JobStatus incomingStatus = resolveStatus(response.status());
        if (incomingStatus == null) {
            log.warn("Ignoring job status message with invalid status for execution {}: status={}",
                    executionId, response.status());
            return;
        }

        if (hasInvalidOptionalIntMetaValue(response, "recordsProcessed")
                || hasInvalidOptionalIntMetaValue(response, "recordsInserted")) {
            log.warn("Ignoring job status message with invalid numeric metadata for execution {}", executionId);
            return;
        }
        int recordsProcessed = resolveRecordsProcessed(response);

        if (!isValidParentExecutionId(response)) {
            log.warn("Ignoring job status message with invalid parentExecutionId for execution {}: parentExecutionId={}",
                    executionId, response.parentExecutionId());
            return;
        }

        JobExecutionHistory history = jobExecutionHistoryRepository.findById(executionId)
                .orElse(null);
        if (history == null) {
            log.warn("Ignoring job status message for unknown execution: {}", executionId);
            return;
        }

        JobStatus previousStatus = history.getStatus();
        boolean wasTerminal = isTerminal(previousStatus);
        boolean incomingTerminal = isTerminal(incomingStatus);

        if (wasTerminal) {
            if (incomingTerminal) {
                log.debug("Ignoring duplicate terminal status for execution {}: previous={} incoming={}",
                        executionId, previousStatus, incomingStatus);
            } else {
                log.warn("Ignoring non-terminal regression for execution {}: previous={} incoming={}",
                        executionId, previousStatus, incomingStatus);
            }
            return;
        }

        history.setStatus(incomingStatus);
        history.setError(response.errorMessage());
        history.setStartedAt(response.startedAt());
        history.setFinishedAt(response.finishedAt());
        history.setRecordsSynced(recordsProcessed);
        history.setNewOffset(response.newOffset());
        history.setMetaJson(buildMetaJson(history, response));

        log.info("Saving job status child executionId={} previousStatus={} incomingStatus={} parentLogId={} metaKeys={}",
                executionId, previousStatus, incomingStatus, history.getParentLogId(),
                history.getMetaJson() == null ? null : history.getMetaJson().keySet());
        jobExecutionHistoryRepository.saveAndFlush(history);
        log.info("Saved job status child executionId={} txActive={}",
                executionId, TransactionSynchronizationManager.isActualTransactionActive());

        UUID persistedParentLogId = history.getParentLogId();
        validateMessageParentExecutionId(response, persistedParentLogId, executionId);

        if (persistedParentLogId != null) {
            log.info("Aggregating parent execution parentLogId={} after child executionId={}",
                    persistedParentLogId, executionId);
            aggregateParentExecution(persistedParentLogId);
            log.info("Aggregated parent execution parentLogId={} after child executionId={} txActive={}",
                    persistedParentLogId, executionId,
                    TransactionSynchronizationManager.isActualTransactionActive());
            return;
        }

        if (incomingTerminal) {
            publishNotification(new JobNotificationContext(history, List.of(), 0, 0, 0));
        }
    }

    /**
     * Recomputes a fan-out parent execution from its child rows under a pessimistic
     * parent-row write lock.
     * <p>
     * Locking the parent before reading children serializes concurrent final-child
     * completions for the same fan-out job. This prevents both missed terminal
     * transitions and duplicate parent digest notifications. Child rows remain the
     * source of truth for counts, record totals, timestamps, and final parent
     * status.
     *
     * @param parentLogId parent execution id to aggregate
     */
    @Transactional
    public void aggregateParentExecution(UUID parentLogId) {
        log.info("Starting parent aggregation parentLogId={} txActive={} txName={}",
                parentLogId, TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.getCurrentTransactionName());
        JobExecutionHistory parent = jobExecutionHistoryRepository.findByIdForUpdate(parentLogId)
                .orElseThrow(() -> new IllegalArgumentException("Parent job execution not found: " + parentLogId));
        JobStatus previousParentStatus = parent.getStatus();
        boolean wasTerminal = isTerminal(previousParentStatus);

        if (wasTerminal) {
            log.debug("Ignoring aggregation for terminal parent execution {} with status {}",
                    parentLogId, previousParentStatus);
            return;
        }

        List<JobExecutionHistory> children = jobExecutionHistoryRepository.findAllByParentLogId(parentLogId);
        if (children.isEmpty()) {
            markParentWithNoChildren(parent, Instant.now());
            return;
        }

        long successCount = children.stream()
                .filter(child -> child.getStatus() == JobStatus.SUCCESS)
                .count();
        long failedCount = children.stream()
                .filter(child -> child.getStatus() == JobStatus.FAILED
                        || child.getStatus() == JobStatus.ERROR)
                .count();
        long pendingCount = children.stream()
                .filter(child -> child.getStatus() == JobStatus.PENDING)
                .count();
        long runningCount = children.stream()
                .filter(child -> child.getStatus() == JobStatus.RUNNING)
                .count();
        boolean allTerminal = pendingCount == 0 && runningCount == 0;

        JobStatus parentStatus;
        if (!allTerminal) {
            parentStatus = JobStatus.RUNNING;
        } else if (failedCount > 0) {
            parentStatus = JobStatus.FAILED;
        } else {
            parentStatus = JobStatus.SUCCESS;
        }

        parent.setStatus(parentStatus);
        parent.setStartedAt(children.stream()
                .map(JobExecutionHistory::getStartedAt)
                .filter(startedAt -> startedAt != null)
                .min(Instant::compareTo)
                .orElse(parent.getStartedAt()));
        parent.setFinishedAt(allTerminal
                ? children.stream()
                        .map(JobExecutionHistory::getFinishedAt)
                        .filter(finishedAt -> finishedAt != null)
                        .max(Instant::compareTo)
                        .orElse(parent.getFinishedAt())
                : null);
        parent.setRecordsSynced(children.stream()
                .map(JobExecutionHistory::getRecordsSynced)
                .filter(records -> records != null)
                .mapToInt(Integer::intValue)
                .sum());
        parent.setRecordsSkipped(children.stream()
                .map(JobExecutionHistory::getRecordsSkipped)
                .filter(records -> records != null)
                .mapToInt(Integer::intValue)
                .sum());
        parent.setNewOffset(null);
        parent.setError(parentStatus == JobStatus.FAILED
                ? failedCount + "/" + children.size() + " tasks failed"
                : null);

        Map<String, Object> meta = new LinkedHashMap<>();
        putAllAsStrings(meta, parent.getMetaJson());
        MetadataUtils.putIfPresent(meta, "childCount", children.size());
        MetadataUtils.putIfPresent(meta, "successCount", successCount);
        MetadataUtils.putIfPresent(meta, "failedCount", failedCount);
        MetadataUtils.putIfPresent(meta, "pendingCount", pendingCount);
        MetadataUtils.putIfPresent(meta, "runningCount", runningCount);
        parent.setMetaJson(meta);

        log.info(
                "Saving parent aggregation parentLogId={} previousStatus={} parentStatus={} allTerminal={} successCount={} failedCount={} pendingCount={} runningCount={} childCount={}",
                parentLogId, previousParentStatus, parentStatus, allTerminal, successCount, failedCount, pendingCount,
                runningCount, children.size());
        jobExecutionHistoryRepository.saveAndFlush(parent);
        log.info("Saved parent aggregation parentLogId={} txActive={}",
                parentLogId, TransactionSynchronizationManager.isActualTransactionActive());

        if (allTerminal) {
            publishNotification(new JobNotificationContext(parent, children, children.size(), successCount, failedCount));
        }
    }

    private JobExecutionHistory createPendingLog(JobDefinition job) {

        JobExecutionHistory log = new JobExecutionHistory();
        log.setJob(job);
        log.setUsedSource(job.getSource());
        log.setStatus(JobStatus.PENDING);
        log.setAttempt(1);

        return log;
    }

    private void mergeMetadata(JobExecutionHistory execution, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        putAllAsStrings(meta, execution.getMetaJson());
        putAllAsStrings(meta, metadata);
        execution.setMetaJson(meta);
    }

    private Map<String, Object> buildMetaJson(JobExecutionHistory history, JobStatusMessage response) {
        Map<String, Object> meta = new HashMap<>();
        putAllAsStrings(meta, history.getMetaJson());
        putAllAsStrings(meta, response.metaJson());
        MetadataUtils.putIfPresent(meta, "symbolKey", response.symbolKey());
        MetadataUtils.putIfPresent(meta, "jobDefinitionId", response.jobDefinitionId());
        MetadataUtils.putIfPresent(meta, "executionId", response.executionId());
        MetadataUtils.putIfPresent(meta, "parentExecutionId", response.parentExecutionId());
        MetadataUtils.putIfPresent(meta, "durationMs", response.durationMs());
        MetadataUtils.putIfPresent(meta, "recordsProcessed", response.recordsProcessed());
        return meta;
    }

    private JobStatus resolveStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }

        try {
            JobStatus resolved = JobStatus.valueOf(status.toUpperCase());
            return resolved == JobStatus.ERROR ? JobStatus.FAILED : resolved;
        } catch (IllegalArgumentException exc) {
            return null;
        }
    }

    private int resolveRecordsProcessed(JobStatusMessage response) {
        if (response.recordsProcessed() != null) {
            return response.recordsProcessed();
        }
        Integer recordsProcessed = getOptionalIntMetaValue(response, "recordsProcessed");
        if (recordsProcessed != null) {
            return recordsProcessed;
        }
        Integer recordsInserted = getOptionalIntMetaValue(response, "recordsInserted");
        return recordsInserted == null ? 0 : recordsInserted;
    }

    private boolean hasInvalidOptionalIntMetaValue(JobStatusMessage response, String key) {
        if (response.metaJson() == null || !response.metaJson().containsKey(key)) {
            return false;
        }

        Object value = response.metaJson().get(key);
        if (value == null) {
            return false;
        }
        if (value instanceof Number) {
            return false;
        }
        if (value instanceof String stringValue) {
            if (stringValue.isBlank()) {
                return false;
            }
            try {
                Integer.parseInt(stringValue);
                return false;
            } catch (NumberFormatException exc) {
                return true;
            }
        }

        return true;
    }

    private Integer getOptionalIntMetaValue(JobStatusMessage response, String key) {
        if (response.metaJson() == null) {
            return null;
        }

        Object value = response.metaJson().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }

        if (value instanceof String stringValue && !stringValue.isBlank()) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException exc) {
                return null;
            }
        }

        return null;
    }

    private UUID parseUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            log.warn("Ignoring job status message with missing {}", fieldName);
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exc) {
            log.warn("Ignoring job status message with invalid {}: {}", fieldName, value);
            return null;
        }
    }

    private boolean isValidParentExecutionId(JobStatusMessage response) {
        if (response.parentExecutionId() == null || response.parentExecutionId().isBlank()) {
            return true;
        }

        return parseUuid(response.parentExecutionId(), "parentExecutionId") != null;
    }

    private boolean isTerminal(JobStatus status) {
        return status == JobStatus.SUCCESS
                || status == JobStatus.FAILED
                || status == JobStatus.ERROR;
    }

    private void validateMessageParentExecutionId(
            JobStatusMessage response,
            UUID persistedParentLogId,
            UUID executionId) {
        if (response.parentExecutionId() == null || response.parentExecutionId().isBlank()) {
            return;
        }

        UUID messageParentLogId = parseUuid(response.parentExecutionId(), "parentExecutionId");
        if (messageParentLogId == null) {
            return;
        }
        if (persistedParentLogId != null && !persistedParentLogId.equals(messageParentLogId)) {
            log.warn("Ignoring mismatched message parentExecutionId for execution {}: persisted={} message={}",
                    executionId, persistedParentLogId, messageParentLogId);
        }
    }

    private void publishNotification(JobNotificationContext context) {
        jobNotificationPolicyRegistry.buildNotification(context).ifPresent(event -> {
            try {
                eventPublisher.publishEvent(event);
            } catch (Exception exc) {
                log.warn("Failed to publish job notification event: {}", exc.getMessage(), exc);
            }
        });
    }

    private void putAllAsStrings(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }

        source.forEach((key, value) -> MetadataUtils.putIfPresent(target, key, value));
    }

    private void updateNextRun(
            JobDefinition job,
            Instant now) {

        if (job.getCronExpr() == null) {
            return;
        }

        CronExpression cron = CronExpression.parse(job.getCronExpr());
        ZoneId zone = ZoneId.of(schedulerZone);

        LocalDateTime nextRun = cron.next(
                LocalDateTime.ofInstant(
                        now,
                        zone));

        if (nextRun != null) {
            job.setNextRun(
                    nextRun.atZone(zone).toInstant());
        }
    }

}
