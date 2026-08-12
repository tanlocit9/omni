package com.omni.platform.modules.scheduler.repositories;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

public class SchedulerOutboxRepositoryImpl implements SchedulerOutboxRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<SchedulerOutboxClaim> claimPending(
            Instant now,
            String claimedBy,
            Duration leaseDuration,
            int batchSize) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT id, execution_id, topic, message_key, payload, attempts
                FROM scheduler_outbox_messages
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
        List<SchedulerOutboxClaim> claims = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            UUID messageId = (UUID) columns[0];
            UUID claimToken = UUID.randomUUID();
            entityManager.createNativeQuery("""
                    UPDATE scheduler_outbox_messages
                    SET claim_token = :claimToken,
                        claimed_by = :claimedBy,
                        claim_until = :claimUntil,
                        attempts = attempts + 1,
                        updated_at = :now
                    WHERE id = :messageId
                    """)
                    .setParameter("claimToken", claimToken)
                    .setParameter("claimedBy", claimedBy)
                    .setParameter("claimUntil", claimUntil)
                    .setParameter("now", now)
                    .setParameter("messageId", messageId)
                    .executeUpdate();
            claims.add(new SchedulerOutboxClaim(
                    messageId,
                    claimToken,
                    claimedBy,
                    (UUID) columns[1],
                    (String) columns[2],
                    (String) columns[3],
                    (String) columns[4],
                    ((Number) columns[5]).intValue() + 1));
        }
        entityManager.flush();
        return List.copyOf(claims);
    }

    @Override
    public boolean markPublished(UUID messageId, UUID claimToken, String claimedBy, Instant publishedAt) {
        return updateClaimed(messageId, claimToken, claimedBy, publishedAt, null, true) == 1;
    }

    @Override
    public boolean markFailed(
            UUID messageId,
            UUID claimToken,
            String claimedBy,
            Instant availableAt,
            String error) {
        return updateClaimed(messageId, claimToken, claimedBy, availableAt, error, false) == 1;
    }

    private int updateClaimed(
            UUID messageId,
            UUID claimToken,
            String claimedBy,
            Instant timestamp,
            String error,
            boolean published) {
        String sql = published ? """
                UPDATE scheduler_outbox_messages
                SET status = 'PUBLISHED', published_at = :timestamp, last_error = NULL,
                    claim_token = NULL, claimed_by = NULL, claim_until = NULL, updated_at = :timestamp
                WHERE id = :messageId AND claim_token = :claimToken AND claimed_by = :claimedBy
                """ : """
                UPDATE scheduler_outbox_messages
                SET status = 'PENDING', available_at = :timestamp, last_error = :error,
                    claim_token = NULL, claimed_by = NULL, claim_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = :messageId AND claim_token = :claimToken AND claimed_by = :claimedBy
                """;
        var query = entityManager.createNativeQuery(sql)
                .setParameter("timestamp", timestamp)
                .setParameter("messageId", messageId)
                .setParameter("claimToken", claimToken)
                .setParameter("claimedBy", claimedBy);
        if (!published) {
            query.setParameter("error", error);
        }
        int updated = query.executeUpdate();
        entityManager.flush();
        return updated;
    }
}
