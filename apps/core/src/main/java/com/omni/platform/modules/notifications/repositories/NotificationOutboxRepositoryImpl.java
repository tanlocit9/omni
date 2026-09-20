package com.omni.platform.modules.notifications.repositories;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage.Provider;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

public class NotificationOutboxRepositoryImpl implements NotificationOutboxRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<NotificationOutboxClaim> claimPending(
            Instant now,
            String claimedBy,
            Duration leaseDuration,
            int batchSize) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT id, provider, channel, notification_kind, schema_version, payload, attempts
                FROM notification_outbox_messages
                WHERE status = 'PENDING'
                  AND available_at <= :now
                  AND (claim_until IS NULL OR claim_until <= :now)
                ORDER BY available_at ASC, created_at ASC, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
                """)
                .setParameter("now", now)
                .setParameter("batchSize", batchSize)
                .getResultList();

        Instant claimUntil = now.plus(leaseDuration);
        List<NotificationOutboxClaim> claims = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            UUID messageId = (UUID) columns[0];
            UUID claimToken = UUID.randomUUID();
            entityManager.createNativeQuery("""
                    UPDATE notification_outbox_messages
                    SET claim_token = :claimToken, claimed_by = :claimedBy,
                        claim_until = :claimUntil, attempts = attempts + 1, updated_at = :now
                    WHERE id = :messageId
                    """)
                    .setParameter("claimToken", claimToken)
                    .setParameter("claimedBy", claimedBy)
                    .setParameter("claimUntil", claimUntil)
                    .setParameter("now", now)
                    .setParameter("messageId", messageId)
                    .executeUpdate();
            claims.add(new NotificationOutboxClaim(
                    messageId,
                    claimToken,
                    claimedBy,
                    Provider.valueOf((String) columns[1]),
                    NotificationChannel.valueOf((String) columns[2]),
                    NotificationKind.valueOf((String) columns[3]),
                    ((Number) columns[4]).intValue(),
                    (String) columns[5],
                    ((Number) columns[6]).intValue() + 1));
        }
        entityManager.flush();
        return List.copyOf(claims);
    }

    @Override
    public boolean markSent(UUID messageId, UUID claimToken, String claimedBy, Instant sentAt) {
        return terminalUpdate(messageId, claimToken, claimedBy, sentAt, null, "SENT") == 1;
    }

    @Override
    public boolean markRetry(
            UUID messageId,
            UUID claimToken,
            String claimedBy,
            Instant availableAt,
            String sanitizedError) {
        int updated = entityManager.createNativeQuery("""
                UPDATE notification_outbox_messages
                SET status = 'PENDING', available_at = :availableAt, last_error = :error,
                    claim_token = NULL, claimed_by = NULL, claim_until = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :messageId AND claim_token = :claimToken AND claimed_by = :claimedBy
                """)
                .setParameter("availableAt", availableAt)
                .setParameter("error", sanitizedError)
                .setParameter("messageId", messageId)
                .setParameter("claimToken", claimToken)
                .setParameter("claimedBy", claimedBy)
                .executeUpdate();
        entityManager.flush();
        return updated == 1;
    }

    @Override
    public boolean markDead(
            UUID messageId,
            UUID claimToken,
            String claimedBy,
            Instant failedAt,
            String sanitizedError) {
        return terminalUpdate(messageId, claimToken, claimedBy, failedAt, sanitizedError, "DEAD") == 1;
    }

    @Override
    public boolean acquireProviderPermit(String provider, Instant now, Duration interval) {
        entityManager.createNativeQuery("""
                INSERT INTO notification_provider_rate_limits (provider, next_permitted_at, updated_at)
                VALUES (:provider, :now, :now)
                ON CONFLICT (provider) DO NOTHING
                """)
                .setParameter("provider", provider)
                .setParameter("now", now)
                .executeUpdate();
        Object value = entityManager.createNativeQuery("""
                SELECT next_permitted_at
                FROM notification_provider_rate_limits
                WHERE provider = :provider
                FOR UPDATE
                """)
                .setParameter("provider", provider)
                .getSingleResult();
        Instant nextPermittedAt = (Instant) value;
        if (nextPermittedAt.isAfter(now)) {
            return false;
        }
        entityManager.createNativeQuery("""
                UPDATE notification_provider_rate_limits
                SET next_permitted_at = :nextPermittedAt, updated_at = :now
                WHERE provider = :provider
                """)
                .setParameter("nextPermittedAt", now.plus(interval))
                .setParameter("now", now)
                .setParameter("provider", provider)
                .executeUpdate();
        entityManager.flush();
        return true;
    }

    private int terminalUpdate(
            UUID messageId,
            UUID claimToken,
            String claimedBy,
            Instant timestamp,
            String error,
            String status) {
        String sql = "SENT".equals(status) ? """
                UPDATE notification_outbox_messages
                SET status = 'SENT', sent_at = :timestamp, last_error = NULL,
                    claim_token = NULL, claimed_by = NULL, claim_until = NULL, updated_at = :timestamp
                WHERE id = :messageId AND claim_token = :claimToken AND claimed_by = :claimedBy
                """ : """
                UPDATE notification_outbox_messages
                SET status = 'DEAD', last_error = :error,
                    claim_token = NULL, claimed_by = NULL, claim_until = NULL, updated_at = :timestamp
                WHERE id = :messageId AND claim_token = :claimToken AND claimed_by = :claimedBy
                """;
        var query = entityManager.createNativeQuery(sql)
                .setParameter("timestamp", timestamp)
                .setParameter("messageId", messageId)
                .setParameter("claimToken", claimToken)
                .setParameter("claimedBy", claimedBy);
        if (!"SENT".equals(status)) {
            query.setParameter("error", error);
        }
        int updated = query.executeUpdate();
        entityManager.flush();
        return updated;
    }
}
