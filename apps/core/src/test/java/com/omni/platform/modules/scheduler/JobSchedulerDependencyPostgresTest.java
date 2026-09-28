package com.omni.platform.modules.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.ManifestReader;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage;
import com.omni.platform.modules.scheduler.entities.Symbol;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.services.SchedulerOutboxService;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

import jakarta.persistence.EntityManager;

@Testcontainers
@SpringBootTest(
        classes = {
                com.omni.platform.PlatformApplication.class,
                JobSchedulerDependencyPostgresTest.ManifestTestConfiguration.class
        },
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=filesystem:../../database/migrations",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.task.scheduling.enabled=false",
                "app.seed.job-definitions.enabled=false",
                "app.scheduler.instance-id=scheduler-dependency-test",
                "app.scheduler.claim.lease-duration=PT2M",
                "app.scheduler.claim.batch-size=10",
                "kafka.topics.topic-sync-indicators=sync-indicators-test"
        })
class JobSchedulerDependencyPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("omni_scheduler_dependency_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add(
                "spring.datasource.driver-class-name",
                POSTGRES::getDriverClassName);
    }

    @Autowired
    private JobScheduler scheduler;

    @Autowired
    private SchedulerOutboxDispatcher outboxDispatcher;

    @Autowired
    private JobDefinitionRepository jobRepository;

    @Autowired
    private SymbolRepository symbolRepository;

    @Autowired
    private JobExecutionHistoryRepository executionRepository;

    @Autowired
    private SchedulerOutboxRepository outboxRepository;

    @Autowired
    private SchedulerOutboxService outboxService;

    @Autowired
    private InMemoryManifestReader manifestReader;

    @MockitoBean
    private KafkaPublisher kafkaPublisher;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        manifestReader.clear();
        when(kafkaPublisher.serialize(any())).thenReturn("{}");
    }

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAll();
        executionRepository.deleteAll();
        jobRepository.deleteAll();
        symbolRepository.deleteAll();
        manifestReader.clear();
    }

    @Test
    void missingManifestWaitsWithoutAttemptsAndReadyManifestsPublishOnce() {
        DatasetRef hpgRef = DatasetRef.of(
                "eod", Map.of("exchange", "hose", "code", "hpg"));
        DatasetRef vnmRef = DatasetRef.of(
                "eod", Map.of("exchange", "hose", "code", "vnm"));
        saveSymbol("HPG", "HOSE");
        saveSymbol("VNM", "HOSE");
        JobDefinition job = saveDueIndicatorsJob();

        scheduler.scan();

        assertThat(executionRepository.count()).isEqualTo(3);
        assertThat(outboxRepository.count()).isEqualTo(2);
        UUID parentExecutionId = executionRepository.findAll().stream()
                .filter(execution -> execution.getParentLogId() == null)
                .findFirst().orElseThrow().getId();
        assertThat(outboxService.findByExecution(parentExecutionId)).hasSize(2);
        assertThat(outboxRepository.findAll()).allSatisfy(message -> {
            assertThat(message.getStatus()).isEqualTo(
                    com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage.Status.PENDING);
            assertThat(message.getAttempts()).isZero();
            assertThat(message.getDependencyReason()).isNull();
        });
        assertClaimReleased(job);

        outboxDispatcher.dispatchBatch(Instant.now());

        assertThat(outboxRepository.findAll()).allSatisfy(message -> {
            assertThat(message.getStatus()).isEqualTo(
                    com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage.Status.PENDING);
            assertThat(message.getAttempts()).isZero();
            assertThat(message.getDependencyReason()).contains("DEPENDENCY_WAITING");
        });

        manifestReader.put(hpgRef, readyManifest(hpgRef, "sha256:eod-hpg"));
        manifestReader.put(vnmRef, readyManifest(vnmRef, "sha256:eod-vnm"));
        makeOutboxRetryDue();

        outboxDispatcher.dispatchBatch(Instant.now());

        List<JobExecutionHistory> executions = executionRepository.findAll();
        List<JobExecutionHistory> children = executions.stream()
                .filter(execution -> execution.getParentLogId() != null)
                .toList();
        assertThat(children).hasSize(2).allSatisfy(child -> {
            assertThat(child.getMetaJson()).containsKey("approvedInputs");
            String workKey = (String) child.getMetaJson().get("workKey");
            String code = workKey.substring(workKey.indexOf('-') + 1).toLowerCase(java.util.Locale.ROOT);
            assertThat(child.getMetaJson().toString())
                    .contains("eod", "hose", code, "sha256:eod-" + code)
                    .doesNotContain("path", "s3://", "r2://");
        });
        assertThat(outboxRepository.findAll()).allSatisfy(message -> {
            assertThat(message.getStatus()).isEqualTo(
                    com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage.Status.PUBLISHED);
            assertThat(message.getAttempts()).isEqualTo(1);
        });
        verify(kafkaPublisher, times(2)).publishSerializedAndWait(
                anyString(), anyString(), anyString(), any());

        outboxDispatcher.dispatchBatch(Instant.now());

        assertThat(outboxRepository.count()).isEqualTo(2);
        verify(kafkaPublisher, times(2)).publishSerializedAndWait(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void waitingOldestCandidateDoesNotStarveLaterReadyCandidateAtPostgresBoundary() {
        DatasetRef readyRef = DatasetRef.of("eod", Map.of("exchange", "hose", "code", "vnm"));
        saveSymbol("HPG", "HOSE");
        saveSymbol("VNM", "HOSE");
        saveDueIndicatorsJob();
        scheduler.scan();
        manifestReader.put(readyRef, readyManifest(readyRef, "sha256:eod-vnm"));

        assertThat(outboxRepository.findAll()).allSatisfy(message -> {
            assertThat(message.getExecution().getParentLogId()).isNotNull();
            assertThat(message.getExecution().getMetaJson()).containsKeys("workType", "workKey");
        });

        outboxDispatcher.dispatchBatch(Instant.now());

        assertThat(outboxRepository.findAll()).extracting(SchedulerOutboxMessage::getStatus)
                .containsExactlyInAnyOrder(SchedulerOutboxMessage.Status.PENDING, SchedulerOutboxMessage.Status.PUBLISHED);
        assertThat(outboxRepository.findAll().stream()
                .filter(message -> message.getStatus() == SchedulerOutboxMessage.Status.PENDING)
                .findFirst().orElseThrow().getAttempts()).isZero();
        verify(kafkaPublisher, times(1)).publishSerializedAndWait(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void concurrentEligibleClaimsAreFencedAndExpiredLeaseCanRecover() throws Exception {
        JobExecutionHistory execution = savePendingExecution(saveDueIndicatorsJob(), "claim-fence");
        outboxService.enqueue(execution, "jobs", List.of(new KafkaMessage("claim-fence", Map.of())), Instant.now());
        UUID messageId = outboxRepository.findAll().getFirst().getId();
        Instant claimTime = Instant.now();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                start.await();
                return outboxService.claimEligible(messageId, claimTime, "node-a", Duration.ofSeconds(30), Map.of());
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                return outboxService.claimEligible(messageId, claimTime, "node-b", Duration.ofSeconds(30), Map.of());
            });
            start.countDown();
            assertThat(java.util.stream.Stream.of(first.get(), second.get())
                    .filter(java.util.Objects::nonNull)
                    .toList()).hasSize(1);
        }

        var recovered = outboxService.claimEligible(
                messageId, claimTime.plusSeconds(31), "node-c", Duration.ofMinutes(1), Map.of());
        assertThat(recovered).isNotNull();
        assertThat(recovered.attempts()).isEqualTo(2);
    }

    @Test
    void blockedRowIsTerminalAndCannotBeReclaimed() {
        JobExecutionHistory execution = savePendingExecution(saveDueIndicatorsJob(), "blocked");
        outboxService.enqueue(execution, "jobs", List.of(new KafkaMessage("blocked", Map.of())), Instant.now());
        SchedulerOutboxMessage message = outboxRepository.findAll().getFirst();

        assertThat(outboxService.markBlocked(message.getId(), Instant.now(), "{\"code\":\"UPSTREAM_FAILED\"}"))
                .isTrue();
        assertThat(outboxService.claimEligible(
                message.getId(), Instant.now().plusSeconds(1), "node-a", Duration.ofMinutes(1), Map.of()))
                .isNull();
        assertThat(outboxService.claimPending(
                Instant.now().plusSeconds(1), "node-a", Duration.ofMinutes(1), 10))
                .isEmpty();
        assertThat(executionRepository.findById(execution.getId()).orElseThrow().getStatus())
                .isEqualTo(JobExecutionHistory.JobStatus.BLOCKED);
    }

    @Test
    void v11KeepsLegacyPendingAndPublishedRowsCompatible() {
        JobDefinition job = saveDueIndicatorsJob();
        JobExecutionHistory pendingExecution = savePendingExecution(job, "legacy-pending");
        JobExecutionHistory publishedExecution = savePendingExecution(job, "legacy-published");
        outboxService.enqueue(
                pendingExecution, "jobs", List.of(new KafkaMessage("legacy-pending", Map.of())), Instant.now());
        outboxService.enqueue(
                publishedExecution, "jobs", List.of(new KafkaMessage("legacy-published", Map.of())), Instant.now());
        SchedulerOutboxMessage published = outboxRepository.findAll().stream()
                .filter(message -> message.getExecution().getId().equals(publishedExecution.getId()))
                .findFirst().orElseThrow();
        published.setStatus(SchedulerOutboxMessage.Status.PUBLISHED);
        published.setPublishedAt(Instant.now());
        outboxRepository.saveAndFlush(published);

        assertThat(outboxRepository.findAll()).extracting(SchedulerOutboxMessage::getStatus)
                .containsExactlyInAnyOrder(SchedulerOutboxMessage.Status.PENDING, SchedulerOutboxMessage.Status.PUBLISHED);
        assertThat(outboxRepository.findAll()).allSatisfy(message -> assertThat(message.getDependencyReason()).isNull());
        assertThat(outboxService.findCandidates(Instant.now().plusSeconds(1), 10))
                .singleElement().satisfies(candidate -> assertThat(candidate.messageId()).isNotEqualTo(published.getId()));
    }

    private JobExecutionHistory savePendingExecution(JobDefinition job, String workKey) {
        JobExecutionHistory execution = new JobExecutionHistory();
        execution.setJob(job);
        execution.setUsedSource(job.getSource());
        execution.setStatus(JobExecutionHistory.JobStatus.PENDING);
        execution.setTriggeredAt(Instant.now());
        execution.setMetaJson(Map.of("workType", "SYMBOL", "workKey", workKey, "runKey", "2026-09-27"));
        return executionRepository.saveAndFlush(execution);
    }

    private Symbol saveSymbol(String code, String exchange) {
        Symbol symbol = new Symbol();
        symbol.setCode(code);
        symbol.setExchange(exchange);
        symbol.setIsActive(true);
        symbol.setMetaJson(Map.of("sectorLv1Code", "BANK"));
        return symbolRepository.saveAndFlush(symbol);
    }

    private JobDefinition saveDueIndicatorsJob() {
        JobDefinition job = new JobDefinition();
        job.setSource(DataSource.ANALYZER);
        job.setJobType(JobType.SYNC_INDICATORS);
        job.setCronExpr("0 0 0 * * *");
        job.setTitle("P4-I2 enforced dependency integration");
        job.setIsActive(true);
        job.setNextRun(Instant.now().minusSeconds(60));
        job.setConfigJson(Map.of());
        return jobRepository.saveAndFlush(job);
    }

    void makeOutboxRetryDue() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        UPDATE scheduler_outbox_messages
                        SET available_at = :retryAt
                        WHERE status = 'PENDING'
                        """)
                        .setParameter("retryAt", Instant.now().minusSeconds(1))
                        .executeUpdate());
    }

    private void assertClaimReleased(JobDefinition job) {
        JobDefinition refreshed = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(refreshed.getClaimToken()).isNull();
        assertThat(refreshed.getClaimedBy()).isNull();
        assertThat(refreshed.getClaimedAt()).isNull();
        assertThat(refreshed.getClaimUntil()).isNull();
    }

    private DatasetManifest readyManifest(DatasetRef ref, String dataVersion) {
        return new DatasetManifest(
                1,
                ref.getDataset(),
                ref.getPartition(),
                "READY",
                dataVersion,
                "physical-path-must-not-propagate",
                1,
                128,
                10,
                1,
                List.of(),
                1,
                "sha256:schema",
                null,
                null,
                List.of(),
                null,
                Instant.now().toString());
    }

    @TestConfiguration
    static class ManifestTestConfiguration {

        @Bean
        @Primary
        InMemoryManifestReader inMemoryManifestReader() {
            return new InMemoryManifestReader();
        }
    }

    static final class InMemoryManifestReader implements ManifestReader {

        private final Map<DatasetRef, DatasetManifest> manifests =
                new ConcurrentHashMap<>();

        @Override
        public Optional<DatasetManifest> readManifest(DatasetRef datasetRef) {
            return Optional.ofNullable(manifests.get(datasetRef));
        }

        void put(DatasetRef ref, DatasetManifest manifest) {
            manifests.put(ref, manifest);
        }

        void clear() {
            manifests.clear();
        }
    }
}
