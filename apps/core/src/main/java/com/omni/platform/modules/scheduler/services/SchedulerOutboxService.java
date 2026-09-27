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
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxCandidate;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;
import com.omni.platform.shared.outbox.ClaimableOutboxStore;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SchedulerOutboxService implements ClaimableOutboxStore<SchedulerOutboxClaim> {

    private final SchedulerOutboxRepository repository;
    private final KafkaPublisher kafkaPublisher;

    @Transactional
    public void enqueue(
            JobExecutionHistory execution,
            String topic,
            List<KafkaMessage> messages,
            Instant now) {
        for (int index = 0; index < messages.size(); index++) {
            KafkaMessage message = messages.get(index);
            SchedulerOutboxMessage outbox = new SchedulerOutboxMessage();
            outbox.setExecution(execution);
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
                .map(message -> {
                    JobExecutionHistory execution = message.getExecution();
                    var metadata = execution.getMetaJson() == null ? java.util.Map.<String, Object>of()
                            : execution.getMetaJson();
                    return new SchedulerOutboxCandidate(
                            message.getId(), execution.getId(), execution.getParentLogId(), execution.getJob(),
                            value(metadata, "workType", execution.getJob().getJobType().name()),
                            value(metadata, "workKey", execution.getJob().getJobType().name()
                                    + ":" + execution.getJob().getSource().name()),
                            value(metadata, "runKey", execution.getTriggeredAt().toString()),
                            metadata, message.getAvailableAt());
                })
                .toList();
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
        persistApprovedInputs(message.getExecution(), approvedInputVersions);
        return repository.claimEligible(messageId, now, instanceId, leaseDuration);
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
            Instant now,
            String instanceId,
            Duration leaseDuration,
            int batchSize) {
        return repository.claimPending(now, instanceId, leaseDuration, batchSize);
    }

    @Override
    @Transactional
    public boolean markDelivered(SchedulerOutboxClaim claim, Instant deliveredAt) {
        return markPublished(claim, deliveredAt);
    }

    @Transactional
    public boolean markPublished(SchedulerOutboxClaim claim, Instant publishedAt) {
        return repository.markPublished(claim.messageId(), claim.claimToken(), claim.claimedBy(), publishedAt);
    }

    @Override
    @Transactional
    public boolean scheduleRetry(SchedulerOutboxClaim claim, Instant retryAt, String sanitizedError) {
        return repository.markFailed(
                claim.messageId(), claim.claimToken(), claim.claimedBy(), retryAt, sanitize(sanitizedError));
    }

    @Transactional
    public boolean markFailed(SchedulerOutboxClaim claim, Instant availableAt, Throwable error) {
        return scheduleRetry(claim, availableAt, error == null ? null : error.getMessage());
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
        metadata.put("approvedInputs", approvedInputVersions.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "dataset", entry.getKey().getDataset(),
                        "partition", entry.getKey().getPartition(),
                        "dataVersion", entry.getValue()))
                .toList());
        execution.setMetaJson(metadata);
    }

    private static String value(java.util.Map<String, Object> metadata, String key, String fallback) {
        Object value = metadata.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String sanitize(String error) {
        String normalized = error == null ? "unknown" : error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), 4000));
    }

    public List<SchedulerOutboxMessage> findByExecution(UUID executionId) {
        return repository.findAllByExecution_IdOrderByMessageIndex(executionId);
    }
}
