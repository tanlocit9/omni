package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;
import com.omni.platform.modules.scheduler.repositories.JobRunRepository;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.StaticJobTopologyProvider;
import com.omni.platform.shared.infrastructure.minio.adapters.MinioStorageAdapter;

/** Real application startup and restart; only PostgreSQL runs in Docker, with outbound work disabled. */
@Testcontainers
class PlatformStartupPostgresTest {
    private static final List<JobDefinitionSeed> SEEDS = JobDefinitionConfig.JOB_DEFINITION_SEEDS;
    private static final Instant NEXT_RUN = Instant.parse("2035-01-02T03:04:05Z");
    private static final Instant CLAIMED_AT = Instant.parse("2035-01-02T03:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("omni_startup_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @Test
    @Timeout(180)
    void startsWithMigrationsAndTypedTopologyThenReseedsWithoutLosingRuntimeState()
            throws Exception {
        Map<JobDefinitionIdentity, UUID> identities;
        Map<UUID, Instant> nextRuns = new HashMap<>();
        UUID retainedId;
        UUID claimToken = UUID.randomUUID();
        int appliedMigrations;

        try (var first = startApplication()) {
            assertReadyAndIsolated(first);
            var flyway = first.getBean(Flyway.class);
            assertThat(flyway.info().pending()).isEmpty();
            appliedMigrations = flyway.info().applied().length;
            assertThat(appliedMigrations).isPositive();

            var repository = first.getBean(JobDefinitionRepository.class);
            assertSeedsAndTopology(first, repository);
            JobDefinition retained = find(repository, SEEDS.getFirst());
            retainedId = retained.getId();
            assertManualTriggerDeferred(first, retainedId);
            retained.setTitle("stale seed title");
            retained.setConfigJson(Map.of("stale", true));
            retained.setFallbackSources(List.of(DataSource.TCBS));
            retained.setIsActive(false);
            retained.setNextRun(NEXT_RUN);
            retained.setClaimToken(claimToken);
            retained.setClaimedBy("startup-test-owner");
            retained.setClaimedAt(CLAIMED_AT);
            retained.setClaimUntil(CLAIMED_AT.plusSeconds(120));
            repository.saveAndFlush(retained);

            List<JobDefinition> rows = repository.findAll();
            identities =
                    rows.stream()
                            .collect(
                                    Collectors.toMap(
                                            PlatformStartupPostgresTest::identity,
                                            JobDefinition::getId));
            rows.forEach(job -> nextRuns.put(job.getId(), job.getNextRun()));
        }

        // The first context is closed; this is a fresh boot over the same real PostgreSQL database.
        try (var restarted = startApplication()) {
            assertReadyAndIsolated(restarted);
            assertThat(restarted.getBean(Flyway.class).info().pending()).isEmpty();
            assertThat(restarted.getBean(Flyway.class).info().applied()).hasSize(appliedMigrations);
            var repository = restarted.getBean(JobDefinitionRepository.class);
            assertSeedsAndTopology(restarted, repository);
            assertThat(
                            repository.findAll().stream()
                                    .collect(
                                            Collectors.toMap(
                                                    PlatformStartupPostgresTest::identity,
                                                    JobDefinition::getId)))
                    .isEqualTo(identities);
            repository
                    .findAll()
                    .forEach(
                            job ->
                                    assertThat(job.getNextRun())
                                            .as("nextRun for %s", identity(job))
                                            .isEqualTo(nextRuns.get(job.getId())));

            JobDefinition retained = repository.findById(retainedId).orElseThrow();
            assertThat(retained.getIsActive()).isFalse();
            assertThat(retained.getClaimToken()).isEqualTo(claimToken);
            assertThat(retained.getClaimedBy()).isEqualTo("startup-test-owner");
            assertThat(retained.getClaimedAt()).isEqualTo(CLAIMED_AT);
            assertThat(retained.getClaimUntil()).isEqualTo(CLAIMED_AT.plusSeconds(120));
        }
    }

    @Test
    @Timeout(180)
    void persistsOptionalExecutionLinksUsingTheRealJpaTransactionManager() throws Exception {
        try (var context = startApplication()) {
            assertReadyAndIsolated(context);
            var definition = find(context.getBean(JobDefinitionRepository.class), SEEDS.getFirst());
            var key =
                    new RequiredPathResolver.StepKey(
                            identity(definition),
                            new RequiredPathResolver.Scope(
                                    RequiredPathResolver.ScopeType.GLOBAL,
                                    "VN",
                                    java.time.LocalDate.of(2035, 1, 2)));
            var path =
                    new RequiredPathResolver.RequiredPath(
                            1,
                            key,
                            List.of(
                                    new RequiredPathResolver.Step(
                                            key,
                                            TopologyNodeKey.forJobType(definition.getJobType()),
                                            List.of())));
            var run =
                    context.getBean(JobRunRepository.class)
                            .prepare(
                                    new JobRunSnapshot.Request(
                                            JobRunSnapshot.Mode.MANUAL,
                                            "startup-test",
                                            "jpa-mapping",
                                            "Verify persistence mapping",
                                            null,
                                            "{}"),
                                    path);
            var histories = context.getBean(JobExecutionHistoryRepository.class);
            var execution = new JobExecutionHistory();
            execution.setJob(definition);
            execution.setUsedSource(definition.getSource());
            execution.setStatus(JobExecutionHistory.JobStatus.PENDING);
            execution.setJobRunId(run.id());
            execution.setJobRunStepIndex(0);
            UUID id = histories.saveAndFlush(execution).getId();
            var reloaded = histories.findById(id).orElseThrow();
            assertThat(reloaded.getJobRunId()).isEqualTo(run.id());
            assertThat(reloaded.getJobRunStepIndex()).isZero();
            assertThat(reloaded.getParentLogId()).isNull();
            assertThat(context.getBean(JobRunRepository.class).findById(run.id())).contains(run);
            histories.deleteById(id);
            assertThat(histories.count()).isZero();
            assertThat(context.getBean(SchedulerOutboxRepository.class).count()).isZero();
        }
    }

    private ConfigurableApplicationContext startApplication() {
        return startApplication(POSTGRES, false);
    }

    static ConfigurableApplicationContext startApplication(
            PostgreSQLContainer<?> database, boolean preparationEnabled) {
        return startApplication(database, preparationEnabled, context -> {});
    }

    static ConfigurableApplicationContext startApplication(
            PostgreSQLContainer<?> database,
            boolean preparationEnabled,
            org.springframework.context.ApplicationContextInitializer<
                            org.springframework.context.ConfigurableApplicationContext>
                    extra) {
        return new SpringApplicationBuilder(PlatformApplication.class, StartupIsolation.class)
                .web(WebApplicationType.SERVLET)
                .registerShutdownHook(false)
                .initializers(
                        extra,
                        context ->
                                context.getBeanFactory()
                                        .registerSingleton(
                                                "startupTestTypeExcludeFilter",
                                                new StartupTestTypeExcludeFilter()))
                .run(
                        "--spring.profiles.active=startup-postgres",
                        "--server.address=127.0.0.1",
                        "--server.port=0",
                        "--spring.datasource.url=" + database.getJdbcUrl(),
                        "--spring.datasource.username=" + database.getUsername(),
                        "--spring.datasource.password=" + database.getPassword(),
                        "--app.scheduler.job-run.preparation-enabled=" + preparationEnabled,
                        "--spring.datasource.driver-class-name=org.postgresql.Driver",
                        "--spring.jpa.hibernate.ddl-auto=none",
                        "--spring.flyway.enabled=true",
                        "--spring.flyway.baseline-on-migrate=false",
                        "--spring.flyway.locations=filesystem:"
                                + Path.of("../../database/migrations")
                                        .toAbsolutePath()
                                        .normalize()
                                        .toString()
                                        .replace('\\', '/'),
                        "--spring.config.import="
                                + Path.of("../../configs/shared/topics.yaml")
                                        .toAbsolutePath()
                                        .toUri(),
                        "--app.seed.job-definitions.enabled=true",
                        "--app.seed.sectors.enabled=true",
                        "--app.scheduler.instance-id=startup-postgres-test",
                        "--app.notifications.telegram.enabled=false",
                        "--app.analyzer.base-url=http://127.0.0.1:1",
                        "--kafka.bootstrap-servers=127.0.0.1:1",
                        "--spring.kafka.bootstrap-servers=127.0.0.1:1",
                        "--spring.kafka.admin.auto-create=false",
                        "--spring.kafka.listener.auto-startup=false",
                        "--min-io.enabled=false",
                        "--minio.endpoint=http://127.0.0.1:1",
                        "--minio.access-key=startup-test",
                        "--minio.secret-key=startup-test",
                        "--app.minio.endpoint=http://127.0.0.1:1",
                        "--app.minio.access-key=startup-test",
                        "--app.minio.secret-key=startup-test",
                        "--management.endpoints.web.exposure.include=health",
                        "--management.endpoint.health.probes.enabled=true",
                        "--management.health.readinessstate.enabled=true",
                        "--management.endpoint.health.group.readiness.include=readinessState",
                        "--management.health.defaults.enabled=false");
    }

    private void assertManualTriggerDeferred(
            ConfigurableApplicationContext context, UUID definitionId) throws Exception {
        var jdbc = context.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        int runsBefore = jdbc.queryForObject("SELECT count(*) FROM job_runs", Integer.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT to_regclass('public.manual_job_triggers') IS NULL",
                                Boolean.class))
                .isTrue();
        assertThat(context.containsBean("manualJobTriggerService")).isFalse();
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        String base = "http://127.0.0.1:" + port + "/api/v1/jobs";
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var unauthorized =
                    HttpRequest.newBuilder(URI.create(base + "/triggers/" + UUID.randomUUID()))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
            assertThat(client.send(unauthorized, HttpResponse.BodyHandlers.ofString()).statusCode())
                    .isEqualTo(401);
            for (var request :
                    List.of(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    base
                                                            + "/definitions/"
                                                            + definitionId
                                                            + "/triggers"))
                                    .header("X-Omni-User", "test-operator")
                                    .timeout(Duration.ofSeconds(10))
                                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                    .build(),
                            HttpRequest.newBuilder(
                                            URI.create(base + "/triggers/" + UUID.randomUUID()))
                                    .header("X-Omni-User", "test-operator")
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build())) {
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(410);
                assertThat(response.body()).contains("manual_trigger_deferred");
            }
            var catalog =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(base + "/definitions/" + definitionId))
                                    .header("X-Omni-User", "test-operator")
                                    .timeout(Duration.ofSeconds(10))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(catalog.statusCode()).isEqualTo(200);
            assertThat(catalog.body()).containsPattern("\"triggerable\"\\s*:\\s*false");
            assertThat(catalog.body()).contains("Manual job triggering is deferred");
        }
        assertThat(context.getBean(JobExecutionHistoryRepository.class).count()).isZero();
        assertThat(context.getBean(SchedulerOutboxRepository.class).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_runs", Integer.class))
                .isEqualTo(runsBefore);
    }

    private void assertReadyAndIsolated(ConfigurableApplicationContext context) throws Exception {
        assertThat(context.isActive()).isTrue();
        assertThat(context.getBean(ApplicationAvailability.class).getReadinessState())
                .isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
        assertThat(context.containsBean(SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse();
        assertThat(context.getBeansOfType(MinioStorageAdapter.class)).isEmpty();
        assertThat(
                        context.getBean(
                                        com.omni.platform.modules.scheduler.runs
                                                .JobRunPreparationService.class)
                                .isEnabled())
                .isFalse();
        assertThat(context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers())
                .isNotEmpty()
                .allSatisfy(container -> assertThat(container.isRunning()).isFalse());
        assertThat(context.getBean(JobExecutionHistoryRepository.class).count()).isZero();
        assertThat(context.getBean(SchedulerOutboxRepository.class).count()).isZero();
        int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
        var request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://127.0.0.1:" + port + "/actuator/health/readiness"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).containsPattern("\"status\"\\s*:\\s*\"UP\"");
        }
    }

    private void assertSeedsAndTopology(
            ConfigurableApplicationContext context, JobDefinitionRepository repository) {
        assertThat(repository.count()).isEqualTo(SEEDS.size());
        var provider = context.getBean(StaticJobTopologyProvider.class);
        assertThat(context.getBeansOfType(StaticJobTopologyProvider.class)).hasSize(1);
        assertThat(provider.definitionDependencies().predecessors().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        SEEDS.stream().map(JobDefinitionIdentity::from).toList());
        for (JobDefinitionSeed seed : SEEDS) {
            JobDefinition persisted = find(repository, seed);
            assertThat(persisted.getTitle()).isEqualTo(seed.title());
            assertThat(persisted.getFallbackSources()).isEqualTo(seed.fallbackSources());
            assertThat(persisted.getConfigJson()).isEqualTo(seed.config());
            Map<?, ?> metadata =
                    (Map<?, ?>) seed.config().get(JobDefinitionConfig.CONFIG_KEY_DATA_DEPENDENCIES);
            List<?> legacyTypes =
                    (List<?>) metadata.get(JobDefinitionConfig.CONFIG_KEY_DEPENDS_ON_JOBS);
            var requiredTypes =
                    legacyTypes.stream().map(value -> JobType.valueOf((String) value)).toList();
            var expected =
                    SEEDS.stream()
                            .filter(upstream -> requiredTypes.contains(upstream.jobType()))
                            .map(JobDefinitionIdentity::from)
                            .toList();
            assertThat(
                            provider.definitionDependencies()
                                    .predecessors()
                                    .get(JobDefinitionIdentity.from(seed)))
                    .as("legacy edge parity for %s", JobDefinitionIdentity.from(seed))
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(provider.topology().nodeFor(identity(persisted)).value())
                    .isEqualTo(
                            provider.topology().nodeFor(JobDefinitionIdentity.from(seed)).value());
        }
    }

    private JobDefinition find(JobDefinitionRepository repository, JobDefinitionSeed seed) {
        return repository
                .findBySourceAndJobTypeAndCronExpr(seed.source(), seed.jobType(), seed.cronExpr())
                .orElseThrow();
    }

    private static JobDefinitionIdentity identity(JobDefinition job) {
        return new JobDefinitionIdentity(job.getSource(), job.getJobType(), job.getCronExpr());
    }

    // Programmatic SpringApplication startup does not install Spring Test's usual test-component
    // filter.
    static final class StartupTestTypeExcludeFilter extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) {
            return reader.getAnnotationMetadata().hasAnnotation(TestConfiguration.class.getName());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StartupTestTypeExcludeFilter;
        }

        @Override
        public int hashCode() {
            return StartupTestTypeExcludeFilter.class.hashCode();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StartupIsolation {
        @Bean
        static BeanFactoryPostProcessor disableScheduledWork() {
            // @EnableScheduling is explicit in PlatformApplication; Boot's scheduling property is
            // insufficient.
            return factory ->
                    ((BeanDefinitionRegistry) factory)
                            .removeBeanDefinition(SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
        }

        @Bean
        static BeanPostProcessor disableCustomKafkaListenerStartup() {
            // The production custom factory does not use Boot's listener auto-startup property.
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof ConcurrentKafkaListenerContainerFactory<?, ?> factory) {
                        factory.setAutoStartup(false);
                    }
                    return bean;
                }
            };
        }
    }
}
