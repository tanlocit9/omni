package com.omni.platform.modules.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.dependencies.ManifestReader;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import com.omni.platform.modules.scheduler.entities.BlockedJob;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.entities.Symbol;
import com.omni.platform.modules.scheduler.repositories.BlockedJobRepository;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;

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
    private JobDefinitionRepository jobRepository;

    @Autowired
    private SymbolRepository symbolRepository;

    @Autowired
    private BlockedJobRepository blockedJobRepository;

    @Autowired
    private JobExecutionHistoryRepository executionRepository;

    @Autowired
    private SchedulerOutboxRepository outboxRepository;

    @Autowired
    private InMemoryManifestReader manifestReader;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        manifestReader.clear();
    }

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAll();
        executionRepository.deleteAll();
        blockedJobRepository.deleteAll();
        jobRepository.deleteAll();
        symbolRepository.deleteAll();
        manifestReader.clear();
    }

    @Test
    void missingManifestBlocksWithoutSpamAndAllReadyManifestsDispatchOnce() {
        DatasetRef hpgRef = DatasetRef.of(
                "eod", Map.of("exchange", "hose", "code", "hpg"));
        DatasetRef vnmRef = DatasetRef.of(
                "eod", Map.of("exchange", "hose", "code", "vnm"));
        saveSymbol("HPG", "HOSE");
        saveSymbol("VNM", "HOSE");
        JobDefinition job = saveDueIndicatorsJob();

        scheduler.scan();

        BlockedJob blocked = blockedJobRepository.findByResolvedFalse()
                .stream()
                .findFirst()
                .orElseThrow();
        assertThat(blocked.getJobName()).isEqualTo("SYNC_INDICATORS_ANALYZER");
        assertThat(blocked.getFailedChecksJson()).contains("hpg", "vnm");
        assertThat(executionRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertClaimReleased(job);

        scheduler.scan();

        assertThat(blockedJobRepository.countByResolvedFalse()).isEqualTo(1);
        assertThat(executionRepository.count()).isZero();
        assertThat(outboxRepository.count()).isZero();
        assertClaimReleased(job);

        manifestReader.put(hpgRef, readyManifest(hpgRef, "sha256:eod-hpg"));
        manifestReader.put(vnmRef, readyManifest(vnmRef, "sha256:eod-vnm"));
        makeRetryDue(blocked);

        scheduler.scan();

        List<JobExecutionHistory> executions = executionRepository.findAll();
        List<JobExecutionHistory> parents = executions.stream()
                .filter(execution -> execution.getParentLogId() == null)
                .toList();
        assertThat(parents).singleElement().satisfies(parent -> {
            assertThat(parent.getMetaJson()).containsKey("approvedInputs");
            assertThat(parent.getMetaJson().toString())
                    .contains("eod", "hose", "hpg", "vnm")
                    .contains("sha256:eod-hpg", "sha256:eod-vnm")
                    .doesNotContain("path", "s3://", "r2://");
        });
        assertThat(executions).hasSize(3);
        assertThat(outboxRepository.count()).isEqualTo(2);
        assertThat(blockedJobRepository.countByResolvedFalse()).isZero();
        assertThat(blockedJobRepository.findAll())
                .singleElement()
                .extracting(BlockedJob::isResolved)
                .isEqualTo(true);
        assertClaimReleased(job);

        scheduler.scan();

        assertThat(executionRepository.count()).isEqualTo(3);
        assertThat(outboxRepository.count()).isEqualTo(2);
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

    void makeRetryDue(BlockedJob blocked) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                entityManager.createNativeQuery("""
                        UPDATE blocked_jobs
                        SET next_retry_at = :retryAt
                        WHERE id = :id
                        """)
                        .setParameter("retryAt", Instant.now().minusSeconds(1))
                        .setParameter("id", blocked.getId())
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
