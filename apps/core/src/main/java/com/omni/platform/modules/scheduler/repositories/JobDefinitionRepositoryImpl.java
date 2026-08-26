package com.omni.platform.modules.scheduler.repositories;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

@Repository
public class JobDefinitionRepositoryImpl implements JobDefinitionClaimRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<SchedulerClaim> claimDueJobs(
            Instant now,
            String claimedBy,
            Duration leaseDuration,
            int batchSize) {
        Instant claimUntil = now.plus(leaseDuration);
        List<?> rows = entityManager.createNativeQuery("""
                SELECT id, next_run
                FROM job_definitions
                WHERE is_active = TRUE
                AND (next_run <= :now OR next_run IS NULL)
                AND (claim_until IS NULL OR claim_until <= :now)
                ORDER BY next_run ASC NULLS FIRST, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
                """)
                .setParameter("now", now)
                .setParameter("batchSize", batchSize)
                .getResultList();

        List<SchedulerClaim> claims = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            UUID jobDefinitionId = (UUID) columns[0];
            Instant scheduledFor = toInstant(columns[1], now);
            UUID claimToken = UUID.randomUUID();

            Query update = entityManager.createNativeQuery("""
                    UPDATE job_definitions
                    SET claim_token = :claimToken,
                        claimed_by = :claimedBy,
                        claimed_at = :claimedAt,
                        claim_until = :claimUntil
                    WHERE id = :jobDefinitionId
                    """);
            update.setParameter("claimToken", claimToken);
            update.setParameter("claimedBy", claimedBy);
            update.setParameter("claimedAt", now);
            update.setParameter("claimUntil", claimUntil);
            update.setParameter("jobDefinitionId", jobDefinitionId);
            update.executeUpdate();

            claims.add(new SchedulerClaim(
                    jobDefinitionId,
                    claimToken,
                    claimedBy,
                    now,
                    claimUntil,
                    scheduledFor));
        }

        entityManager.flush();
        // Native updates bypass Hibernate's first-level cache. Detach any job
        // definitions loaded earlier in the request so ownership checks reload the
        // fencing token and owner written above instead of observing stale fields.
        entityManager.clear();
        return List.copyOf(claims);
    }

    @Override
    public Optional<SchedulerClaim> claimJobDefinition(
            UUID jobDefinitionId,
            Instant now,
            String claimedBy,
            Duration leaseDuration) {
        List<?> rows = entityManager.createNativeQuery("""
                SELECT id
                FROM job_definitions
                WHERE id = :jobDefinitionId
                AND is_active = TRUE
                AND (claim_until IS NULL OR claim_until <= :now)
                FOR UPDATE SKIP LOCKED
                """)
                .setParameter("jobDefinitionId", jobDefinitionId)
                .setParameter("now", now)
                .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        UUID claimToken = UUID.randomUUID();
        Instant claimUntil = now.plus(leaseDuration);
        int updated = entityManager.createNativeQuery("""
                UPDATE job_definitions
                SET claim_token = :claimToken,
                    claimed_by = :claimedBy,
                    claimed_at = :claimedAt,
                    claim_until = :claimUntil
                WHERE id = :jobDefinitionId
                """)
                .setParameter("claimToken", claimToken)
                .setParameter("claimedBy", claimedBy)
                .setParameter("claimedAt", now)
                .setParameter("claimUntil", claimUntil)
                .setParameter("jobDefinitionId", jobDefinitionId)
                .executeUpdate();
        entityManager.flush();
        // The manual-trigger request may already have loaded this definition before
        // entering the claim transaction. Clear that stale managed instance after
        // the native update so the dispatch transaction reads the committed claim.
        entityManager.clear();
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(new SchedulerClaim(
                jobDefinitionId,
                claimToken,
                claimedBy,
                now,
                claimUntil,
                now));
    }

    @Override
    public boolean releaseClaim(SchedulerClaim claim) {
        return releaseClaim(claim.jobDefinitionId(), claim.claimToken(), claim.claimedBy());
    }

    @Override
    public boolean releaseClaim(UUID jobDefinitionId, UUID claimToken, String claimedBy) {
        int released = entityManager.createNativeQuery("""
                UPDATE job_definitions
                SET claim_token = NULL,
                    claimed_by = NULL,
                    claimed_at = NULL,
                    claim_until = NULL
                WHERE id = :jobDefinitionId
                AND claim_token = :claimToken
                AND claimed_by = :claimedBy
                """)
                .setParameter("jobDefinitionId", jobDefinitionId)
                .setParameter("claimToken", claimToken)
                .setParameter("claimedBy", claimedBy)
                .executeUpdate();
        entityManager.flush();
        // Keep the persistence context consistent with the native claim release.
        entityManager.clear();
        return released == 1;
    }

    private Instant toInstant(Object value, Instant fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalStateException("Unsupported next_run type: " + value.getClass().getName());
    }
}
