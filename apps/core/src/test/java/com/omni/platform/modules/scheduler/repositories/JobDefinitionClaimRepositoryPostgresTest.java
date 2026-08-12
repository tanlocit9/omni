package com.omni.platform.modules.scheduler.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory.JobStatus;
import com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.services.JobService;

import jakarta.persistence.EntityManager;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=filesystem:../../database/migrations",
        "spring.jpa.hibernate.ddl-auto=none",
        "app.seed.job-definitions.enabled=false",
        "app.scheduler.instance-id=postgres-claim-test",
        "app.scheduler.claim.lease-duration=PT2M",
        "app.scheduler.claim.batch-size=10"
})
class JobDefinitionClaimRepositoryPostgresTest {

    private static final Instant NOW = Instant.parse("2026-08-11T15:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(2);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("omni_claim_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private JobDefinitionRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JobExecutionHistoryRepository executionRepository;

    @Autowired
    private SchedulerOutboxRepository outboxRepository;

    @Autowired
    private JobService jobService;

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAll();
        executionRepository.deleteAll();
        repository.deleteAll();
    }

    @Test
    @DisplayName("two independent transactions racing for one due job produce exactly one claim")
    void racingTransactionsCannotClaimSameDueJob() throws Exception {
        saveJob("single due", true, NOW.minusSeconds(60));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<List<SchedulerClaim>> first = executor.submit(() -> claimAfterSignal(tx, ready, start, "core-a", 1));
        Future<List<SchedulerClaim>> second = executor.submit(() -> claimAfterSignal(tx, ready, start, "core-b", 1));

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<SchedulerClaim> firstClaims = first.get(10, TimeUnit.SECONDS);
        List<SchedulerClaim> secondClaims = second.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(firstClaims.size() + secondClaims.size()).isEqualTo(1);
        assertThat(firstClaims).doesNotHaveDuplicates();
        assertThat(secondClaims).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("SKIP LOCKED lets a concurrent transaction claim a different due row without blocking")
    void skipLockedClaimsDifferentDueRow() throws Exception {
        JobDefinition firstJob = saveJob("due first", true, NOW.minusSeconds(120));
        JobDefinition secondJob = saveJob("due second", true, NOW.minusSeconds(60));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstClaimed = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Future<List<SchedulerClaim>> first = executor.submit(() -> tx.execute(status -> {
            List<SchedulerClaim> claims = repository.claimDueJobs(NOW, "core-a", LEASE_DURATION, 1);
            firstClaimed.countDown();
            await(allowCommit);
            return claims;
        }));

        assertThat(firstClaimed.await(10, TimeUnit.SECONDS)).isTrue();

        Future<List<SchedulerClaim>> second = executor.submit(() -> tx.execute(status ->
                repository.claimDueJobs(NOW, "core-b", LEASE_DURATION, 1)));

        List<SchedulerClaim> secondClaims = second.get(10, TimeUnit.SECONDS);
        allowCommit.countDown();
        List<SchedulerClaim> firstClaims = first.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(firstClaims).singleElement().extracting(SchedulerClaim::jobDefinitionId).isEqualTo(firstJob.getId());
        assertThat(secondClaims).singleElement().extracting(SchedulerClaim::jobDefinitionId).isEqualTo(secondJob.getId());
    }

    @Test
    @DisplayName("only active overdue, equal-time, and null nextRun jobs are claimable")
    void claimDueJobsFiltersCandidates() {
        JobDefinition activeNull = saveJob("active null", true, null);
        JobDefinition activePast = saveJob("active past", true, NOW.minusSeconds(60));
        JobDefinition activeEqual = saveJob("active equal", true, NOW);
        JobDefinition activeFuture = saveJob("active future", true, NOW.plusSeconds(60));
        JobDefinition inactivePast = saveJob("inactive past", false, NOW.minusSeconds(60));
        JobDefinition inactiveNull = saveJob("inactive null", false, null);

        List<SchedulerClaim> claims = claim("core-a", 10);

        assertThat(claims)
                .extracting(SchedulerClaim::jobDefinitionId)
                .containsExactlyInAnyOrder(activeNull.getId(), activePast.getId(), activeEqual.getId());
        assertThat(claims)
                .extracting(SchedulerClaim::jobDefinitionId)
                .doesNotContain(activeFuture.getId(), inactivePast.getId(), inactiveNull.getId());
        assertThat(claims)
                .filteredOn(claim -> claim.jobDefinitionId().equals(activeNull.getId()))
                .singleElement()
                .extracting(SchedulerClaim::scheduledFor)
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName("live claims are not reclaimable and expired claims receive a new fencing token")
    void liveAndExpiredLeaseBehavior() {
        JobDefinition job = saveJob("reclaimable", true, NOW.minusSeconds(60));
        SchedulerClaim firstClaim = claim("core-a", 1).getFirst();

        assertThat(claim("core-b", 1)).isEmpty();

        expireClaim(job.getId(), NOW.minusSeconds(1));
        SchedulerClaim secondClaim = claim("core-b", 1).getFirst();

        assertThat(secondClaim.jobDefinitionId()).isEqualTo(job.getId());
        assertThat(secondClaim.claimToken()).isNotEqualTo(firstClaim.claimToken());
        assertThat(secondClaim.claimedBy()).isEqualTo("core-b");
    }

    @Test
    @DisplayName("deterministic ordering and batch limit are preserved")
    void deterministicOrderingAndBatchLimit() {
        JobDefinition activeNull = saveJob("active null", true, null);
        JobDefinition oldest = saveJob("oldest", true, NOW.minusSeconds(300));
        JobDefinition newer = saveJob("newer", true, NOW.minusSeconds(60));
        saveJob("equal one", true, NOW.minusSeconds(30));
        saveJob("equal two", true, NOW.minusSeconds(30));

        List<SchedulerClaim> claims = claim("core-a", 3);

        assertThat(claims)
                .extracting(SchedulerClaim::jobDefinitionId)
                .containsExactly(activeNull.getId(), oldest.getId(), newer.getId());
    }

    @Test
    @DisplayName("exact owner and token release a claim, while wrong owner or stale token cannot")
    void releaseRequiresExactOwnerAndToken() {
        JobDefinition staleTokenJob = saveJob("stale token", true, NOW.minusSeconds(60));
        SchedulerClaim staleClaim = claim("core-a", 1).getFirst();
        expireClaim(staleTokenJob.getId(), NOW.minusSeconds(1));
        SchedulerClaim newerClaim = claim("core-b", 1).getFirst();

        assertThat(release(newerClaim.jobDefinitionId(), newerClaim.claimToken(), "wrong-owner")).isFalse();
        assertThat(release(staleClaim.jobDefinitionId(), staleClaim.claimToken(), staleClaim.claimedBy())).isFalse();
        assertThat(release(newerClaim.jobDefinitionId(), newerClaim.claimToken(), newerClaim.claimedBy())).isTrue();
        assertThat(claim("core-c", 1)).singleElement().extracting(SchedulerClaim::jobDefinitionId).isEqualTo(staleTokenJob.getId());
    }

    @Test
    @DisplayName("claim state constraint rejects partial claim metadata")
    void claimStateConstraintRejectsPartialMetadata() {
        JobDefinition job = saveJob("partial metadata", true, NOW.minusSeconds(60));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("""
                    UPDATE job_definitions
                    SET claim_token = :claimToken,
                        claimed_by = :claimedBy,
                        claimed_at = NULL,
                        claim_until = :claimUntil
                    WHERE id = :jobDefinitionId
                    """)
                    .setParameter("claimToken", UUID.randomUUID())
                    .setParameter("claimedBy", "core-a")
                    .setParameter("claimUntil", NOW.plus(LEASE_DURATION))
                    .setParameter("jobDefinitionId", job.getId())
                    .executeUpdate();
            entityManager.flush();
        })).hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
    }

    @Test
    @DisplayName("two dispatchers claim one outbox message once and a failed attempt reuses its stable identity")
    void outboxClaimingIsFencedAndRecoverable() throws Exception {
        JobDefinition job = saveJob("outbox owner", true, NOW.plusSeconds(3600));
        JobExecutionHistory execution = new JobExecutionHistory();
        execution.setJob(job);
        execution.setUsedSource(job.getSource());
        execution.setStatus(JobStatus.PENDING);
        execution = executionRepository.saveAndFlush(execution);

        SchedulerOutboxMessage message = new SchedulerOutboxMessage();
        message.setExecution(execution);
        message.setMessageIndex(0);
        message.setTopic("jobs");
        message.setMessageKey("stable-key");
        message.setPayload("{\"executionId\":\"" + execution.getId() + "\"}");
        message.setAvailableAt(NOW);
        message = outboxRepository.saveAndFlush(message);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<List<SchedulerOutboxClaim>> first = executor.submit(() -> claimOutboxAfterSignal(
                tx, ready, start, "core-a"));
        Future<List<SchedulerOutboxClaim>> second = executor.submit(() -> claimOutboxAfterSignal(
                tx, ready, start, "core-b"));

        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<SchedulerOutboxClaim> firstClaims = first.get(10, TimeUnit.SECONDS);
        List<SchedulerOutboxClaim> secondClaims = second.get(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(firstClaims.size() + secondClaims.size()).isEqualTo(1);
        SchedulerOutboxClaim firstClaim = firstClaims.isEmpty() ? secondClaims.getFirst() : firstClaims.getFirst();
        assertThat(firstClaim.messageId()).isEqualTo(message.getId());
        assertThat(firstClaim.executionId()).isEqualTo(execution.getId());

        Instant retryAt = NOW.plusSeconds(30);
        assertThat(tx.execute(status -> outboxRepository.markFailed(
                firstClaim.messageId(), firstClaim.claimToken(), firstClaim.claimedBy(), retryAt, "broker down")))
                .isTrue();
        assertThat(tx.execute(status -> outboxRepository.claimPending(
                retryAt.minusMillis(1), "core-c", LEASE_DURATION, 1))).isEmpty();

        SchedulerOutboxClaim retryClaim = tx.execute(status -> outboxRepository.claimPending(
                retryAt, "core-c", LEASE_DURATION, 1)).getFirst();
        assertThat(retryClaim.messageId()).isEqualTo(firstClaim.messageId());
        assertThat(retryClaim.executionId()).isEqualTo(firstClaim.executionId());
        assertThat(retryClaim.claimToken()).isNotEqualTo(firstClaim.claimToken());
        assertThat(retryClaim.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("claimed preparation commits one logical execution, outbox payload, nextRun, and exact release")
    void claimedPreparationIsAtomicAndIdempotentAtTheSchedulingBoundary() {
        JobDefinition job = new JobDefinition();
        job.setSource(DataSource.VND);
        job.setJobType(JobType.SYNC_STOCK_PRICE);
        job.setCronExpr("0 0 * * * *");
        job.setTitle("atomic preparation");
        job.setIsActive(true);
        job.setNextRun(NOW.minusSeconds(60));
        job = repository.saveAndFlush(job);
        SchedulerClaim claim = claim("core-a", 1).getFirst();

        UUID executionId = new TransactionTemplate(transactionManager).execute(status -> {
            JobDefinition claimedJob = repository.findById(claim.jobDefinitionId()).orElseThrow();
            JobExecutionHistory execution = jobService.prepareClaimedExecution(claimedJob, claim, NOW);
            jobService.enqueueDispatch(
                    execution,
                    "jobs",
                    List.of(new KafkaMessage("stable-key", java.util.Map.of("executionId", execution.getId()))),
                    NOW);
            jobService.releaseClaim(claim);
            return execution.getId();
        });

        JobDefinition preparedJob = repository.findById(job.getId()).orElseThrow();
        assertThat(preparedJob.getNextRun()).isAfter(NOW);
        assertThat(preparedJob.getClaimToken()).isNull();
        assertThat(executionRepository.findById(executionId)).isPresent();
        assertThat(outboxRepository.findAllByExecution_IdOrderByMessageIndex(executionId))
                .singleElement()
                .satisfies(outbox -> {
                    assertThat(outbox.getMessageIndex()).isZero();
                    assertThat(outbox.getMessageKey()).isEqualTo("stable-key");
                    assertThat(outbox.getPayload()).contains(executionId.toString());
                });
        assertThat(claim("core-b", 1)).isEmpty();
        assertThat(executionRepository.count()).isEqualTo(1);
    }

    private List<SchedulerClaim> claim(String claimedBy, int batchSize) {
        return new TransactionTemplate(transactionManager).execute(status ->
                repository.claimDueJobs(NOW, claimedBy, LEASE_DURATION, batchSize));
    }

    private boolean release(UUID jobDefinitionId, UUID claimToken, String claimedBy) {
        return new TransactionTemplate(transactionManager).execute(status ->
                repository.releaseClaim(jobDefinitionId, claimToken, claimedBy));
    }

    private List<SchedulerClaim> claimAfterSignal(
            TransactionTemplate tx,
            CountDownLatch ready,
            CountDownLatch start,
            String claimedBy,
            int batchSize) {
        ready.countDown();
        await(start);
        return tx.execute(status -> repository.claimDueJobs(NOW, claimedBy, LEASE_DURATION, batchSize));
    }

    private List<SchedulerOutboxClaim> claimOutboxAfterSignal(
            TransactionTemplate tx,
            CountDownLatch ready,
            CountDownLatch start,
            String claimedBy) {
        ready.countDown();
        await(start);
        return tx.execute(status -> outboxRepository.claimPending(NOW, claimedBy, LEASE_DURATION, 1));
    }

    private void expireClaim(UUID jobDefinitionId, Instant claimUntil) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("""
                    UPDATE job_definitions
                    SET claim_until = :claimUntil
                    WHERE id = :jobDefinitionId
                    """)
                    .setParameter("claimUntil", claimUntil)
                    .setParameter("jobDefinitionId", jobDefinitionId)
                    .executeUpdate();
            entityManager.flush();
        });
    }

    private JobDefinition saveJob(String title, boolean active, Instant nextRun) {
        JobDefinition job = new JobDefinition();
        job.setSource(DataSource.VND);
        job.setJobType(JobType.SYNC_STOCK_PRICE);
        job.setCronExpr(title);
        job.setTitle(title);
        job.setIsActive(active);
        job.setNextRun(nextRun);
        return repository.saveAndFlush(job);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
