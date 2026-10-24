package com.omni.platform.modules.scheduler.repositories;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

public class SchedulerOutboxRepositoryImpl implements SchedulerOutboxRepositoryCustom {

    private static final int MAX_BACKLOG_GROUPS = 10_000;

    @PersistenceContext private EntityManager entityManager;

    @Override
    public List<SchedulerBacklogGroup> findBacklogGroups(Instant snapshotAt, int groupLimit) {
        if (groupLimit <= 0 || groupLimit > MAX_BACKLOG_GROUPS) {
            throw new IllegalArgumentException(
                    "groupLimit must be between 1 and " + MAX_BACKLOG_GROUPS);
        }
        List<?> rows =
                entityManager
                        .createNativeQuery(
                                """
                SELECT j.id, j.source, j.job_type, j.cron_expr,
                       COUNT(*) AS pending_count,
                       COUNT(*) FILTER (WHERE o.available_at <= :snapshotAt
                           AND (o.claim_until IS NULL OR o.claim_until <= :snapshotAt)) AS dispatchable_count,
                       COUNT(*) FILTER (WHERE o.available_at > :snapshotAt
                           AND (o.claim_until IS NULL OR o.claim_until <= :snapshotAt)) AS retry_delayed_count,
                       COUNT(*) FILTER (WHERE o.claim_until > :snapshotAt) AS in_flight_count
                FROM scheduler_outbox_messages o
                JOIN job_execution_histories e ON e.id = o.execution_id
                JOIN job_definitions j ON j.id = e.job_id
                WHERE o.status = 'PENDING'
                GROUP BY j.id, j.source, j.job_type, j.cron_expr
                ORDER BY j.job_type ASC, j.source ASC, j.cron_expr ASC, j.id ASC
                LIMIT :queryLimit
                """)
                        .setParameter("snapshotAt", snapshotAt)
                        .setParameter("queryLimit", groupLimit + 1)
                        .getResultList();
        return rows.stream()
                .map(
                        row -> {
                            Object[] columns = (Object[]) row;
                            return new SchedulerBacklogGroup(
                                    (UUID) columns[0],
                                    com.omni.platform.modules.scheduler.entities.JobDefinition
                                            .DataSource.valueOf((String) columns[1]),
                                    com.omni.platform.modules.scheduler.entities.JobDefinition
                                            .JobType.valueOf((String) columns[2]),
                                    (String) columns[3],
                                    ((Number) columns[4]).longValue(),
                                    ((Number) columns[5]).longValue(),
                                    ((Number) columns[6]).longValue(),
                                    ((Number) columns[7]).longValue());
                        })
                .toList();
    }

    @Override
    public List<UUID> findPendingCandidateIds(Instant now, int candidateLimit) {
        return entityManager
                .createNativeQuery(
                        """
                SELECT id FROM scheduler_outbox_messages
                WHERE status = 'PENDING' AND available_at <= :now
                  AND (claim_until IS NULL OR claim_until <= :now)
                ORDER BY available_at ASC, created_at ASC, id ASC
                LIMIT :candidateLimit
                """,
                        UUID.class)
                .setParameter("now", now)
                .setParameter("candidateLimit", candidateLimit)
                .getResultList();
    }

    @Override
    public SchedulerOutboxClaim claimEligible(
            UUID messageId, Instant now, String claimedBy, Duration leaseDuration) {
        List<?> rows =
                entityManager
                        .createNativeQuery(
                                """
                SELECT execution_id, topic, message_key, payload, attempts
                FROM scheduler_outbox_messages
                WHERE id = :messageId AND status = 'PENDING' AND available_at <= :now
                  AND (claim_until IS NULL OR claim_until <= :now)
                FOR UPDATE SKIP LOCKED
                """)
                        .setParameter("messageId", messageId)
                        .setParameter("now", now)
                        .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] columns = (Object[]) rows.getFirst();
        UUID claimToken = UUID.randomUUID();
        Instant claimUntil = now.plus(leaseDuration);
        int updated =
                entityManager
                        .createNativeQuery(
                                """
                UPDATE scheduler_outbox_messages
                SET claim_token = :claimToken, claimed_by = :claimedBy,
                    claim_until = :claimUntil, attempts = attempts + 1, updated_at = :now
                WHERE id = :messageId AND status = 'PENDING'
                """)
                        .setParameter("claimToken", claimToken)
                        .setParameter("claimedBy", claimedBy)
                        .setParameter("claimUntil", claimUntil)
                        .setParameter("now", now)
                        .setParameter("messageId", messageId)
                        .executeUpdate();
        if (updated != 1) {
            return null;
        }
        entityManager.flush();
        return new SchedulerOutboxClaim(
                messageId,
                claimToken,
                claimedBy,
                (UUID) columns[0],
                (String) columns[1],
                (String) columns[2],
                (String) columns[3],
                ((Number) columns[4]).intValue() + 1);
    }

    @Override
    public List<SchedulerOutboxClaim> claimPending(
            Instant now, String claimedBy, Duration leaseDuration, int batchSize) {
        List<?> rows =
                entityManager
                        .createNativeQuery(
                                """
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
            entityManager
                    .createNativeQuery(
                            """
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
            claims.add(
                    new SchedulerOutboxClaim(
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
    public boolean markWaiting(UUID messageId, Instant availableAt, String reason) {
        return entityManager
                        .createNativeQuery(
                                """
                UPDATE scheduler_outbox_messages
                SET available_at = :availableAt, dependency_reason = :reason, updated_at = CURRENT_TIMESTAMP
                WHERE id = :messageId AND status = 'PENDING' AND claim_token IS NULL
                """)
                        .setParameter("availableAt", availableAt)
                        .setParameter("reason", reason)
                        .setParameter("messageId", messageId)
                        .executeUpdate()
                == 1;
    }

    @Override
    public boolean markBlocked(UUID messageId, Instant blockedAt, String reason) {
        int updated =
                entityManager
                        .createNativeQuery(
                                """
                UPDATE scheduler_outbox_messages o
                SET status = 'BLOCKED', dependency_reason = :reason,
                    claim_token = NULL, claimed_by = NULL, claim_until = NULL, updated_at = :blockedAt
                WHERE o.id = :messageId AND o.status = 'PENDING' AND o.claim_token IS NULL
                """)
                        .setParameter("reason", reason)
                        .setParameter("blockedAt", blockedAt)
                        .setParameter("messageId", messageId)
                        .executeUpdate();
        if (updated == 1) {
            entityManager
                    .createNativeQuery(
                            """
                    UPDATE job_execution_histories e
                    SET status = 'BLOCKED', finished_at = :blockedAt, error = :reason, updated_at = :blockedAt
                    FROM scheduler_outbox_messages o
                    WHERE o.id = :messageId AND e.id = o.execution_id AND e.status IN ('PENDING', 'RUNNING')
                    """)
                    .setParameter("reason", reason)
                    .setParameter("blockedAt", blockedAt)
                    .setParameter("messageId", messageId)
                    .executeUpdate();
        }
        entityManager.flush();
        return updated == 1;
    }

    @Override
    public boolean markPublished(
            UUID messageId, UUID claimToken, String claimedBy, Instant publishedAt) {
        return updateClaimed(messageId, claimToken, claimedBy, publishedAt, null, true) == 1;
    }

    @Override
    public boolean renewForPublish(SchedulerOutboxClaim claim, Instant at, Duration lease) {
        return entityManager
                        .createNativeQuery(
                                """
                UPDATE scheduler_outbox_messages SET claim_until=:until,updated_at=:at
                WHERE id=:message AND status='PENDING' AND claim_token=:token AND claimed_by=:owner
                  AND claim_until > :at
                """)
                        .setParameter("until", at.plus(lease))
                        .setParameter("at", at)
                        .setParameter("message", claim.messageId())
                        .setParameter("token", claim.claimToken())
                        .setParameter("owner", claim.claimedBy())
                        .executeUpdate()
                == 1;
    }

    @Override
    public boolean markFailed(
            UUID messageId, UUID claimToken, String claimedBy, Instant availableAt, String error) {
        return updateClaimed(messageId, claimToken, claimedBy, availableAt, error, false) == 1;
    }

    private int updateClaimed(
            UUID messageId,
            UUID claimToken,
            String claimedBy,
            Instant timestamp,
            String error,
            boolean published) {
        String sql =
                published
                        ? """
                UPDATE scheduler_outbox_messages
                SET status = 'PUBLISHED', published_at = :timestamp, last_error = NULL,
                    claim_token = NULL, claimed_by = NULL, claim_until = NULL, updated_at = :timestamp
                WHERE id = :messageId AND status = 'PENDING'
                  AND claim_token = :claimToken AND claimed_by = :claimedBy
                """
                        : """
                UPDATE scheduler_outbox_messages
                SET status = 'PENDING', available_at = :timestamp, last_error = :error,
                    claim_token = NULL, claimed_by = NULL, claim_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = :messageId AND status = 'PENDING'
                  AND claim_token = :claimToken AND claimed_by = :claimedBy
                """;
        var query =
                entityManager
                        .createNativeQuery(sql)
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
