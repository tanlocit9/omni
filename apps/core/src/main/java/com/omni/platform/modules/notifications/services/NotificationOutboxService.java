package com.omni.platform.modules.notifications.services;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage.Provider;
import com.omni.platform.modules.notifications.repositories.NotificationOutboxClaim;
import com.omni.platform.modules.notifications.repositories.NotificationOutboxRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class NotificationOutboxService implements NotificationOutboxStore {

    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_ERROR_LENGTH = 1_000;

    private final NotificationOutboxRepository repository;
    private final JsonMapper jsonMapper;

    public NotificationOutboxService(NotificationOutboxRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public NotificationOutboxMessage enqueue(NotificationRequest request, Instant now) {
        requireDeliveryIdentity(request);
        Provider provider = Provider.TELEGRAM;
        repository.insertIfAbsent(
                provider.name(),
                request.channel().name(),
                request.kind().name(),
                SCHEMA_VERSION,
                serialize(request),
                request.deduplicationKey(),
                now);
        return repository.findByProviderAndChannelAndDeduplicationKey(
                provider, request.channel(), request.deduplicationKey())
                .orElseThrow(() -> new IllegalStateException("Notification enqueue did not produce a durable row"));
    }

    @Override
    @Transactional
    public List<NotificationOutboxClaim> claimPending(
            Instant now, String instanceId, Duration leaseDuration, int batchSize) {
        return repository.claimPending(now, instanceId, leaseDuration, batchSize);
    }

    @Override
    public NotificationRequest decode(NotificationOutboxClaim claim) {
        if (claim.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported notification schema version: " + claim.schemaVersion());
        }
        try {
            NotificationRequest request = jsonMapper.readValue(claim.payload(), NotificationRequest.class);
            if (request.kind() != claim.kind() || request.channel() != claim.channel()) {
                throw new IllegalArgumentException("Persisted notification envelope does not match payload");
            }
            return request;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid persisted notification payload", exception);
        }
    }

    @Override
    @Transactional
    public boolean markDelivered(NotificationOutboxClaim claim, Instant deliveredAt) {
        return markSent(claim, deliveredAt);
    }

    @Transactional
    public boolean markSent(NotificationOutboxClaim claim, Instant sentAt) {
        return repository.markSent(claim.messageId(), claim.claimToken(), claim.claimedBy(), sentAt);
    }

    @Override
    @Transactional
    public boolean scheduleRetry(NotificationOutboxClaim claim, Instant retryAt, String sanitizedError) {
        return markRetry(claim, retryAt, sanitizedError);
    }

    @Transactional
    public boolean markRetry(NotificationOutboxClaim claim, Instant retryAt, String error) {
        return repository.markRetry(
                claim.messageId(), claim.claimToken(), claim.claimedBy(), retryAt, sanitize(error));
    }

    @Override
    @Transactional
    public boolean markDead(NotificationOutboxClaim claim, Instant failedAt, String error) {
        return repository.markDead(
                claim.messageId(), claim.claimToken(), claim.claimedBy(), failedAt, sanitize(error));
    }

    @Override
    @Transactional
    public boolean acquireProviderPermit(Provider provider, Instant now, Duration interval) {
        return repository.acquireProviderPermit(provider.name(), now, interval);
    }

    private void requireDeliveryIdentity(NotificationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Notification request is required");
        }
        if (request.channel() == null) {
            throw new IllegalArgumentException("Notification channel is required");
        }
        if (request.deduplicationKey() == null || request.deduplicationKey().isBlank()) {
            throw new IllegalArgumentException("Notification deduplication key is required");
        }
        if (request.deduplicationKey().length() > 512) {
            throw new IllegalArgumentException("Notification deduplication key exceeds 512 characters");
        }
    }

    private String serialize(NotificationRequest request) {
        try {
            return jsonMapper.writeValueAsString(request);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Notification request cannot be serialized", exception);
        }
    }

    private String sanitize(String error) {
        String normalized = error == null ? "unknown" : error.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), MAX_ERROR_LENGTH));
    }
}
