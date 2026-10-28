package com.omni.platform.modules.scheduler.services;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage;
import com.omni.platform.modules.scheduler.messaging.JobMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxCandidate;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import com.omni.platform.shared.outbox.ClaimableOutboxStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerOutboxService implements ClaimableOutboxStore<SchedulerOutboxClaim> {

    private final SchedulerOutboxRepository repository;
    private final JobExecutionHistoryRepository executionRepository;
    private final KafkaPublisher kafkaPublisher;
    private final JsonMapper jsonMapper;

    @Transactional
    public void enqueue(
            JobExecutionHistory execution, String topic, List<KafkaMessage> messages, Instant now) {
        for (int index = 0; index < messages.size(); index++) {
            KafkaMessage message = messages.get(index);
            JobExecutionHistory messageExecution = execution;
            if (message.payload() instanceof JobMessage jobMessage
                    && !jobMessage.executionId().equals(execution.getId())) {
                if (!execution.getId().equals(jobMessage.parentExecutionId())) {
                    throw new IllegalArgumentException(
                            "Outbox message belongs to another parent execution");
                }
                messageExecution =
                        executionRepository
                                .findById(jobMessage.executionId())
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Outbox child execution not found: "
                                                                + jobMessage.executionId()));
                if (!execution.getId().equals(messageExecution.getParentLogId())) {
                    throw new IllegalArgumentException(
                            "Outbox child execution belongs to another parent");
                }
            }
            SchedulerOutboxMessage outbox = new SchedulerOutboxMessage();
            outbox.setExecution(messageExecution);
            outbox.setMessageIndex(index);
            outbox.setTopic(topic);
            outbox.setMessageKey(message.key());
            outbox.setPayload(kafkaPublisher.serialize(message.payload()));
            outbox.setAvailableAt(now);
            repository.save(outbox);
        }
    }

    @Transactional(readOnly = true)
    public List<SchedulerOutboxCandidate> findCandidates(Instant now, int limit) {
        return repository.findPendingCandidateIds(now, limit).stream()
                .map(repository::findById)
                .flatMap(java.util.Optional::stream)
                .map(this::toCandidate)
                .toList();
    }

    /** Batch-load only IDs selected by the bounded scoped query, preserving its order. */
    @Transactional(readOnly = true)
    public List<SchedulerOutboxCandidate> findCandidatesByIds(List<UUID> ids) {
        var messages = candidateRows(ids);
        return ids.stream()
                .map(messages::get)
                .filter(java.util.Objects::nonNull)
                .map(message -> toCandidate(message, true))
                .toList();
    }

    /** Poison intent is skipped diagnostically, without hiding other bounded candidates. */
    @Transactional(readOnly = true)
    public List<SchedulerOutboxCandidate> findCandidatesByIdsSafely(List<UUID> ids) {
        var messages = candidateRows(ids);
        List<SchedulerOutboxCandidate> result = new java.util.ArrayList<>();
        for (UUID id : ids) {
            var message = messages.get(id);
            if (message == null) continue;
            try {
                result.add(toCandidate(message, true));
            } catch (RuntimeException error) {
                log.warn(
                        "OUTBOX_CANDIDATE_INVALID messageId={} type={}",
                        id,
                        error.getClass().getSimpleName());
            }
        }
        return List.copyOf(result);
    }

    private Map<UUID, SchedulerOutboxMessage> candidateRows(List<UUID> ids) {
        if (ids.size() > 400 || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("Expected at most 400 distinct candidate IDs");
        }
        if (ids.isEmpty()) return Map.of();
        Map<UUID, SchedulerOutboxMessage> messages = new java.util.HashMap<>();
        repository
                .findAllCandidates(ids)
                .forEach(message -> messages.put(message.getId(), message));
        return messages;
    }

    /** All executions are already tracked by the caller; no per-message database lookup. */
    @Transactional
    public void enqueueRunStep(
            JobExecutionHistory parent,
            String topic,
            List<KafkaMessage> messages,
            Instant now,
            List<JobExecutionHistory> children) {
        if (messages.size() > 5000)
            throw new IllegalArgumentException("JOB_RUN_WORK_BOUND_EXCEEDED");
        Map<UUID, JobExecutionHistory> executions = new java.util.HashMap<>();
        for (JobExecutionHistory child : children) {
            if (!parent.getId().equals(child.getParentLogId())
                    || !parent.getJobRunId().equals(child.getJobRunId())
                    || !parent.getJobRunStepIndex().equals(child.getJobRunStepIndex())) {
                throw new IllegalArgumentException("JobRun child ownership mismatch");
            }
            executions.put(child.getId(), child);
        }
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        List<SchedulerOutboxMessage> rows = new java.util.ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            KafkaMessage message = messages.get(index);
            if (!(message.payload() instanceof JobMessage work)
                    || !parent.getJob().getId().equals(work.jobDefinitionId())
                    || !seen.add(work.executionId())) {
                throw new IllegalArgumentException("Invalid or duplicate JobRun message");
            }
            JobExecutionHistory execution = executions.get(work.executionId());
            if (execution == null) {
                if (!children.isEmpty()
                        || !parent.getId().equals(work.executionId())
                        || work.parentExecutionId() != null) {
                    throw new IllegalArgumentException("JobRun message execution mismatch");
                }
                execution = parent;
            } else if (!parent.getId().equals(work.parentExecutionId())) {
                throw new IllegalArgumentException("JobRun message parent mismatch");
            }
            if (!execution.getUsedSource().name().equals(work.source())
                    || work.workType() == null
                    || !work.workType().name().equals(execution.getMetaJson().get("workType"))
                    || !java.util.Objects.equals(
                            work.workKey(), execution.getMetaJson().get("workKey"))) {
                throw new IllegalArgumentException("JobRun message work identity mismatch");
            }
            SchedulerOutboxMessage row = new SchedulerOutboxMessage();
            row.setExecution(execution);
            row.setMessageIndex(index);
            row.setTopic(topic);
            row.setMessageKey(message.key());
            row.setPayload(kafkaPublisher.serialize(message.payload()));
            row.setAvailableAt(now);
            rows.add(row);
        }
        if (!children.isEmpty() && !seen.equals(executions.keySet())) {
            throw new IllegalArgumentException("Incomplete JobRun fan-out messages");
        }
        repository.saveAll(rows);
    }

    private SchedulerOutboxCandidate toCandidate(SchedulerOutboxMessage message) {
        return toCandidate(message, false);
    }

    private SchedulerOutboxCandidate toCandidate(SchedulerOutboxMessage message, boolean fetched) {
        JsonNode payload = parseCandidatePayload(message);
        JobExecutionHistory workExecution = candidateExecution(message, payload, fetched);
        Map<String, Object> metadata =
                workExecution.getMetaJson() == null ? Map.of() : workExecution.getMetaJson();
        String workType = optionalText(payload, "workType", value(metadata, "workType", null));
        String workKey = optionalText(payload, "workKey", value(metadata, "workKey", null));
        if (workType == null || workKey == null)
            throw new IllegalStateException(
                    "Missing scheduler outbox work identity messageId=" + message.getId());
        return new SchedulerOutboxCandidate(
                message.getId(),
                workExecution.getId(),
                workExecution.getParentLogId(),
                workExecution.getJob(),
                workType,
                workKey,
                value(metadata, "runKey", workExecution.getTriggeredAt().toString()),
                metadata,
                message.getAvailableAt());
    }

    private JobExecutionHistory candidateExecution(
            SchedulerOutboxMessage message, JsonNode payload, boolean fetched) {
        UUID persistedExecutionId = message.getExecution().getId();
        UUID workExecutionId = optionalUuid(payload, "executionId", message.getId(), null);
        if (!persistedExecutionId.equals(workExecutionId)) {
            throw new IllegalStateException(
                    "Outbox payload execution does not match stored work execution");
        }
        JobExecutionHistory workExecution =
                fetched
                        ? message.getExecution()
                        : executionRepository
                                .findByIdWithJob(workExecutionId)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Outbox payload execution not found messageId="
                                                                + message.getId()
                                                                + " executionId="
                                                                + workExecutionId));
        if (!workExecution
                .getJob()
                .getId()
                .equals(
                        optionalUuid(
                                payload,
                                "jobDefinitionId",
                                message.getId(),
                                workExecution.getJob().getId()))) {
            throw new IllegalStateException(
                    "Outbox payload jobDefinitionId mismatch messageId=" + message.getId());
        }
        verifyPayloadParent(message, payload, workExecution);
        return workExecution;
    }

    private JsonNode parseCandidatePayload(SchedulerOutboxMessage message) {
        try {
            return jsonMapper.readTree(message.getPayload());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Invalid scheduler outbox payload messageId=" + message.getId(), exception);
        }
    }

    private static UUID optionalUuid(
            JsonNode payload, String field, UUID messageId, UUID fallback) {
        String value = optionalText(payload, field, null);
        if (value == null) {
            return fallback;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "Invalid scheduler outbox " + field + " messageId=" + messageId, exception);
        }
    }

    private static String optionalText(JsonNode payload, String field, String fallback) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) {
            return fallback;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? fallback : text;
    }

    private static void verifyPayloadParent(
            SchedulerOutboxMessage message, JsonNode payload, JobExecutionHistory workExecution) {
        String parentValue = optionalText(payload, "parentExecutionId", null);
        UUID expectedParent = workExecution.getParentLogId();
        if (expectedParent == null) {
            if (parentValue != null && !parentValue.isBlank()) {
                throw new IllegalStateException(
                        "Unexpected scheduler outbox parentExecutionId messageId="
                                + message.getId());
            }
            return;
        }
        if (!expectedParent.toString().equals(parentValue)) {
            throw new IllegalStateException(
                    "Scheduler outbox parentExecutionId mismatch messageId=" + message.getId());
        }
    }

    @Transactional
    public SchedulerOutboxClaim claimEligible(
            UUID messageId,
            Instant now,
            String instanceId,
            Duration leaseDuration,
            Map<DatasetRef, String> approvedInputVersions) {
        SchedulerOutboxMessage message = repository.findById(messageId).orElse(null);
        if (message == null || message.getStatus() != SchedulerOutboxMessage.Status.PENDING) {
            return null;
        }
        JobExecutionHistory work =
                candidateExecution(message, parseCandidatePayload(message), true);
        SchedulerOutboxClaim claim =
                repository.claimEligible(messageId, now, instanceId, leaseDuration);
        if (claim != null && !approvedInputVersions.isEmpty())
            persistApprovedInputs(work, approvedInputVersions);
        return claim;
    }

    @Transactional
    public boolean markWaiting(UUID messageId, Instant retryAt, String reason) {
        return repository.markWaiting(messageId, retryAt, sanitize(reason));
    }

    @Transactional
    public boolean markBlocked(UUID messageId, Instant blockedAt, String reason) {
        return repository.markBlocked(messageId, blockedAt, sanitize(reason));
    }

    @Override
    @Transactional
    public List<SchedulerOutboxClaim> claimPending(
            Instant now, String instanceId, Duration leaseDuration, int batchSize) {
        return repository.claimPending(now, instanceId, leaseDuration, batchSize);
    }

    @Override
    @Transactional
    public boolean markDelivered(SchedulerOutboxClaim claim, Instant deliveredAt) {
        return repository.markPublished(
                claim.messageId(), claim.claimToken(), claim.claimedBy(), deliveredAt);
    }

    @Override
    @Transactional
    public boolean scheduleRetry(
            SchedulerOutboxClaim claim, Instant retryAt, String sanitizedError) {
        return repository.markFailed(
                claim.messageId(),
                claim.claimToken(),
                claim.claimedBy(),
                retryAt,
                sanitize(sanitizedError));
    }

    private static void persistApprovedInputs(
            JobExecutionHistory execution, Map<DatasetRef, String> approvedInputVersions) {
        if (approvedInputVersions.isEmpty()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (execution.getMetaJson() != null) {
            metadata.putAll(execution.getMetaJson());
        }
        metadata.put(
                "approvedInputs",
                approvedInputVersions.entrySet().stream()
                        .map(
                                entry ->
                                        Map.<String, Object>of(
                                                "dataset", entry.getKey().getDataset(),
                                                "partition", entry.getKey().getPartition(),
                                                "dataVersion", entry.getValue()))
                        .toList());
        execution.setMetaJson(metadata);
    }

    @Transactional
    public boolean renewForPublish(SchedulerOutboxClaim claim, Instant at, Duration lease) {
        return repository.renewForPublish(claim, at, lease);
    }

    private static String value(Map<String, Object> metadata, String key, String fallback) {

        Object value = metadata.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String sanitize(String error) {
        String normalized =
                error == null ? "unknown" : error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 4000));
    }

    public List<SchedulerOutboxMessage> findByExecution(UUID executionId) {
        return repository.findAllForExecution(executionId);
    }
}
