package com.omni.platform.modules.scheduler.services;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxClaim;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SchedulerOutboxService {

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

    @Transactional
    public List<SchedulerOutboxClaim> claimPending(
            Instant now,
            String instanceId,
            Duration leaseDuration,
            int batchSize) {
        return repository.claimPending(now, instanceId, leaseDuration, batchSize);
    }

    @Transactional
    public boolean markPublished(SchedulerOutboxClaim claim, Instant publishedAt) {
        return repository.markPublished(claim.messageId(), claim.claimToken(), claim.claimedBy(), publishedAt);
    }

    @Transactional
    public boolean markFailed(SchedulerOutboxClaim claim, Instant availableAt, Throwable error) {
        String message = error.getMessage();
        if (message != null && message.length() > 4000) {
            message = message.substring(0, 4000);
        }
        return repository.markFailed(
                claim.messageId(), claim.claimToken(), claim.claimedBy(), availableAt, message);
    }

    public List<SchedulerOutboxMessage> findByExecution(UUID executionId) {
        return repository.findAllByExecution_IdOrderByMessageIndex(executionId);
    }
}
