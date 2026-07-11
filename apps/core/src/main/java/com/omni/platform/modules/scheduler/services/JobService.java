package com.omni.platform.modules.scheduler.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobDefinitionRepository jobDefinitionRepository;
    private final JobExecutionHistoryRepository jobExecutionHistoryRepository;

    public JobExecutionHistory prepareForExecution(
            JobDefinition job,
            Instant now) {

        JobExecutionHistory log = createPendingLog(job);

        updateNextRun(job, now);

        jobExecutionHistoryRepository.save(log);
        jobDefinitionRepository.save(job);

        return log;
    }

    public JobExecutionHistory createSymbolChildExecution(
            UUID parentLogId,
            String symbolKey,
            Map<String, Object> metadata,
            Instant now) {

        JobExecutionHistory parent = jobExecutionHistoryRepository.findById(parentLogId)
                .orElseThrow(() -> new IllegalArgumentException("Parent job execution not found: " + parentLogId));

        JobExecutionHistory child = createPendingLog(parent.getJob());
        child.setParentLogId(parent.getId());
        child.setStartedAt(now);
        child.setStatus(JobExecutionHistory.JobStatus.RUNNING);

        Map<String, Object> meta = new LinkedHashMap<>();
        putIfPresent(meta, "symbolKey", symbolKey);
        putAllAsStrings(meta, metadata);
        child.setMetaJson(meta);

        return jobExecutionHistoryRepository.save(child);
    }

    public void markExecutionSuccess(
            JobExecutionHistory execution,
            int recordsSynced,
            int recordsSkipped,
            Instant finishedAt,
            Map<String, Object> metadata) {

        mergeMetadata(execution, metadata);
        execution.setStatus(JobExecutionHistory.JobStatus.SUCCESS);
        execution.setRecordsSynced(recordsSynced);
        execution.setRecordsSkipped(recordsSkipped);
        execution.setFinishedAt(finishedAt);
        execution.setError(null);

        jobExecutionHistoryRepository.save(execution);
    }

    public void markExecutionFailed(
            JobExecutionHistory execution,
            String error,
            Instant finishedAt,
            Map<String, Object> metadata) {

        mergeMetadata(execution, metadata);
        execution.setStatus(JobExecutionHistory.JobStatus.FAILED);
        execution.setRecordsSynced(0);
        execution.setRecordsSkipped(1);
        execution.setFinishedAt(finishedAt);
        execution.setError(error);

        jobExecutionHistoryRepository.save(execution);
    }

    public void aggregateParentExecution(UUID parentLogId) {
        JobExecutionHistory parent = jobExecutionHistoryRepository.findById(parentLogId)
                .orElseThrow(() -> new IllegalArgumentException("Parent job execution not found: " + parentLogId));

        List<JobExecutionHistory> children = jobExecutionHistoryRepository.findAllByParentLogId(parentLogId);
        if (children.isEmpty()) {
            return;
        }

        long successCount = children.stream()
                .filter(child -> child.getStatus() == JobExecutionHistory.JobStatus.SUCCESS)
                .count();
        long failedCount = children.stream()
                .filter(child -> child.getStatus() == JobExecutionHistory.JobStatus.FAILED)
                .count();
        long pendingCount = children.stream()
                .filter(child -> child.getStatus() == JobExecutionHistory.JobStatus.PENDING)
                .count();
        long runningCount = children.stream()
                .filter(child -> child.getStatus() == JobExecutionHistory.JobStatus.RUNNING)
                .count();
        boolean allTerminal = pendingCount == 0 && runningCount == 0;

        JobExecutionHistory.JobStatus parentStatus;
        if (!allTerminal) {
            parentStatus = JobExecutionHistory.JobStatus.RUNNING;
        } else if (failedCount > 0) {
            parentStatus = JobExecutionHistory.JobStatus.FAILED;
        } else {
            parentStatus = JobExecutionHistory.JobStatus.SUCCESS;
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
        parent.setError(parentStatus == JobExecutionHistory.JobStatus.FAILED
                ? failedCount + "/" + children.size() + " symbol tasks failed"
                : null);

        Map<String, Object> meta = new LinkedHashMap<>();
        putAllAsStrings(meta, parent.getMetaJson());
        putIfPresent(meta, "childCount", children.size());
        putIfPresent(meta, "successCount", successCount);
        putIfPresent(meta, "failedCount", failedCount);
        putIfPresent(meta, "pendingCount", pendingCount);
        putIfPresent(meta, "runningCount", runningCount);
        parent.setMetaJson(meta);

        jobExecutionHistoryRepository.save(parent);
    }

    private JobExecutionHistory createPendingLog(JobDefinition job) {

        JobExecutionHistory log = new JobExecutionHistory();
        log.setJob(job);
        log.setUsedSource(job.getSource());
        log.setStatus(JobExecutionHistory.JobStatus.PENDING);
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

    private void putAllAsStrings(Map<String, Object> target, Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }

        source.forEach((key, value) -> putIfPresent(target, key, value));
    }

    private void putIfPresent(Map<String, Object> meta, String key, Object value) {
        if (value != null) {
            meta.put(key, String.valueOf(value));
        }
    }

    private void updateNextRun(
            JobDefinition job,
            Instant now) {

        if (job.getCronExpr() == null) {
            return;
        }

        CronExpression cron = CronExpression.parse(job.getCronExpr());

        LocalDateTime nextRun = cron.next(
                LocalDateTime.ofInstant(
                        now,
                        ZoneOffset.UTC));

        if (nextRun != null) {
            job.setNextRun(
                    nextRun.toInstant(
                            ZoneOffset.UTC));
        }
    }

}