package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import com.omni.platform.modules.scheduler.SchedulerOutboxDispatcher;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.dependencies.CachedManifestReader;
import com.omni.platform.modules.scheduler.dependencies.DatasetRef;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshot;
import com.omni.platform.modules.scheduler.planning.JobRunDispatchSnapshotReader;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.producers.JobProducerRegistry;
import com.omni.platform.modules.scheduler.runs.JobRunPreparationService;
import com.omni.platform.modules.scheduler.services.SchedulerClaimService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in actual DB/broker/storage/workers. Only external market data is a fixture. */
@EnabledIfEnvironmentVariable(named = "OMNI_P14_E2E", matches = "1")
@Timeout(600)
class JobRunCrossServiceIntegrationTest {
    static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    static final List<Process> WORKERS = new ArrayList<>();
    static PostgreSQLContainer<?> database;
    static GenericContainer<?> kafka;
    static GenericContainer<?> minio;
    static ConfigurableApplicationContext context;
    static JdbcTemplate jdbc;
    static Path directory;
    static Path config;
    static String bucket = "p14-integration";
    static final Instant BUSINESS_CLOCK = workingClock();
    static final AtomicInteger REQUEST_SEQUENCE = new AtomicInteger();

    static Instant workingClock() {
        var now = ZonedDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        while (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY)
            now = now.minusDays(1);
        return now.toInstant();
    }

    @BeforeAll
    static void start() throws Exception {
        start(false);
    }

    static void start(boolean overlapControls) throws Exception {
        start(overlapControls, false);
    }

    static void start(boolean overlapControls, boolean graphExpansion) throws Exception {
        start(overlapControls, graphExpansion, false);
    }

    static void start(boolean overlapControls, boolean graphExpansion, boolean enrollment) throws Exception {
        start(overlapControls, graphExpansion, enrollment, enrollment);
    }

    static void start(boolean overlapControls, boolean graphExpansion, boolean enrollment, boolean globalReader) throws Exception {
        directory = Files.createTempDirectory("omni-p14-integration-");
        int kafkaPort;
        try (var socket = new ServerSocket(0)) {
            kafkaPort = socket.getLocalPort();
        }
        kafka =
                new GenericContainer<>(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
                        .withExposedPorts(9092)
                        .withEnv("KAFKA_NODE_ID", "1")
                        .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                        .withEnv(
                                "KAFKA_LISTENERS",
                                "INTERNAL://0.0.0.0:29092,EXTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:29093")
                        .withEnv(
                                "KAFKA_ADVERTISED_LISTENERS",
                                "INTERNAL://localhost:29092,EXTERNAL://127.0.0.1:" + kafkaPort)
                        .withEnv(
                                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                                "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT")
                        .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL")
                        .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                        .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:29093")
                        .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                        .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                        .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                        .withEnv("CLUSTER_ID", "MkU3OEVBNTcwNTJENDM2Qk")
                        .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1))
                        .withStartupTimeout(Duration.ofSeconds(90));
        kafka.setPortBindings(List.of("127.0.0.1:" + kafkaPort + ":9092"));
        kafka.start();
        database = new PostgreSQLContainer<>("postgres:16-alpine");
        database.start();
        String access = "test-" + UUID.randomUUID().toString().substring(0, 8);
        String secret = UUID.randomUUID().toString();
        minio =
                new GenericContainer<>(DockerImageName.parse("quay.io/minio/minio:latest"))
                        .withExposedPorts(9000)
                        .withEnv("MINIO_ROOT_USER", access)
                        .withEnv("MINIO_ROOT_PASSWORD", secret)
                        .withCommand("server", "/data")
                        .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
        minio.start();
        String endpoint = "http://127.0.0.1:" + minio.getMappedPort(9000);
        String bootstrap = "127.0.0.1:" + kafkaPort;
        var properties = new HashMap<String, Object>();
        properties.put("kafka.bootstrap-servers", bootstrap);
        properties.put("spring.kafka.bootstrap-servers", bootstrap);
        properties.put("minio.endpoint", endpoint);
        properties.put("minio.access-key", access);
        properties.put("minio.secret-key", secret);
        properties.put("app.minio.endpoint", endpoint);
        properties.put("app.minio.access-key", access);
        properties.put("app.minio.secret-key", secret);
        properties.put("minio.bucket", bucket);
        properties.put("app.minio.bucket", bucket);
        // Pin the route so external application flags cannot silently change the fixture.
        properties.put("app.scheduler.job-run.graph-expansion-enabled", graphExpansion);
        properties.put("app.scheduler.job-run.planning-enabled", true);
        properties.put("app.scheduler.job-run.definition-consolidation-enabled", false);
        properties.put("app.output-registration.enrollment-enabled", enrollment);
        properties.put("app.output-registration.shadow-enabled", enrollment);
        properties.put("app.output-registration.ready-promotion-enabled", enrollment);
        properties.put("app.output-registration.reader-enabled", globalReader);
        properties.put("app.output-registration.reader-token", "i10-isolated-fixture");
        context =
                PlatformStartupPostgresTest.startApplication(
                        database,
                        true,
                        ctx ->
                                ctx.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "p14Integration", properties)));
        jdbc = context.getBean(JdbcTemplate.class);
        assertThat(context.getEnvironment().getProperty("app.minio.endpoint")).isEqualTo(endpoint);
        config = directory.resolve("environment.json");
        var environment =
                new HashMap<String, Object>(
                        Map.of(
                                "kafka",
                                bootstrap,
                                "minio",
                                endpoint.replace("http://", ""),
                                "accessKey",
                                access,
                                "secretKey",
                                secret,
                                "bucket",
                                bucket,
                                "result",
                                directory.resolve("query-result.json").toString()));
        environment.put(
                "businessDate",
                BUSINESS_CLOCK.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate().toString());
        if (enrollment) {
            environment.put("outputReadinessUrl", "http://127.0.0.1:"
                    + context.getEnvironment().getRequiredProperty("local.server.port")
                    + "/api/v1/jobs/output-readiness");
            environment.put("outputReadinessToken", "i10-isolated-fixture");
            environment.put("providerControlDir", Files.createDirectory(directory.resolve("provider-control")).toString());
        }
        if (overlapControls) {
            Path control = Files.createDirectory(directory.resolve("control"));
            environment.put("overlapControlDir", control.toString());
            environment.put("expectedRows", 1);
        }
        Files.writeString(config, JSON.writeValueAsString(environment));
        // Production listeners are started explicitly; no schedules run in this isolated profile.
        var listeners = context.getBean(KafkaListenerEndpointRegistry.class);
        listeners.getListenerContainers().forEach(container -> container.start());
        await(
                () ->
                        listeners.getListenerContainers().stream()
                                .allMatch(
                                        c ->
                                                c.getAssignedPartitions() != null
                                                        && !c.getAssignedPartitions().isEmpty()),
                60,
                "Kafka listener assignment");
        launch("ingestor", "p14_worker.py", false);
        launch("analyzer", "p14_worker.py", false);
    }

    @AfterAll
    static void stop() throws Exception {
        for (Process worker : WORKERS) {
            var children = worker.descendants().toList();
            children.forEach(ProcessHandle::destroy);
            worker.destroy();
            if (!worker.waitFor(5, TimeUnit.SECONDS)) worker.destroyForcibly().waitFor();
            children.stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
        }
        WORKERS.clear();
        if (context != null) context.close();
        if (minio != null) minio.stop();
        if (database != null) database.stop();
        if (kafka != null) kafka.stop();
    }

    @Test
    void actualClosedRunDispatchesThroughWorkersAndPreservesReadyOnMetadataFailure()
            throws Exception {
        jdbc.execute("TRUNCATE job_runs, job_execution_histories, symbols CASCADE");
        jdbc.update(
                "UPDATE job_definitions SET config_json=jsonb_set(config_json,'{sectorCodes}','[]') WHERE job_type IN ('SYNC_STOCK_PRICE','SYNC_INDICATORS')");
        jdbc.update(
                "UPDATE job_definitions SET config_json=config_json || '{\"exchanges\":[\"HOSE\"],\"includeSectorClassification\":false}'::jsonb WHERE job_type='SYNC_SYMBOLS'");
        jdbc.update("INSERT INTO symbols(code,exchange,is_active) VALUES ('HPG','HOSE',true)");
        UUID target = prepare(JobType.SYNC_INDICATORS);
        UUID run =
                jdbc.queryForObject(
                        "SELECT job_run_id FROM job_execution_histories WHERE id=?",
                        UUID.class,
                        target);
        var dispatch = context.getBean(SchedulerOutboxDispatcher.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_run_steps WHERE job_run_id=?",
                                Integer.class,
                                run))
                .isGreaterThan(3);
        await(
                () -> {
                    dispatch.dispatch();
                    return jdbc.queryForObject(
                                    "SELECT count(*) FROM job_execution_histories h JOIN job_definitions d ON d.id=h.job_id WHERE h.job_run_id=? AND d.job_type IN ('SYNC_SYMBOLS','SYNC_STOCK_PRICE') AND h.status<>'SUCCESS'",
                                    Integer.class,
                                    run)
                            == 0;
                },
                180,
                "symbol and stock workers, parent aggregation");
        // No global discovery exists yet, so execution SUCCESS must not bypass dataset WAITING.
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM scheduler_outbox_messages o JOIN job_execution_histories h ON h.id=o.execution_id JOIN job_definitions d ON d.id=h.job_id WHERE h.job_run_id=? AND d.job_type='SYNC_INDICATORS' AND o.status='PUBLISHED'",
                                Integer.class,
                                run))
                .isZero();
        UUID metadata = prepare(JobType.SYNC_METADATA);
        finish(metadata, dispatch);
        finish(target, dispatch);
        var snapshot =
                context.getBean(JobRunDispatchSnapshotReader.class)
                        .capture(Instant.now(), List.of(run));
        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.runs().getFirst().steps())
                .allSatisfy(
                        step ->
                                assertThat(step.progress())
                                        .isEqualTo(JobRunDispatchSnapshot.Progress.SUCCESS));
        finish(prepare(JobType.SYNC_METADATA), dispatch);
        launch("query-service", "p14_query.py", true);
        var result = JSON.readTree(Files.readString(directory.resolve("query-result.json")));
        assertThat(result.path("rows").asInt()).isEqualTo(1);
        String eodVersion = result.path("eodDataVersion").asText();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories h JOIN job_definitions d ON d.id=h.job_id WHERE h.job_run_id=? AND d.job_type='SYNC_INDICATORS' AND h.parent_log_id IS NOT NULL AND h.meta_json::text LIKE ?",
                                Integer.class,
                                run,
                                "%" + eodVersion + "%"))
                .isEqualTo(1);
        var storage = context.getBean("minioClient", MinioClient.class);
        proveLegacyStaleReadyWaitsBeforeKafkaAndResumesAfterRepair(storage, dispatch);
        // The fixture provider has no classification endpoint. Existing symbol
        // data permits the actual fallback branch to complete with warnings.
        jdbc.update(
                "UPDATE job_definitions SET config_json=jsonb_set(config_json,'{includeSectorClassification}','true') WHERE job_type='SYNC_SYMBOLS'");
        UUID symbolsWithWarning = prepare(JobType.SYNC_SYMBOLS);
        finish(symbolsWithWarning, dispatch);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE parent_log_id=? AND status='SUCCESS' AND meta_json->>'result'='PARTIAL_SUCCESS' AND meta_json->>'warnings' IS NOT NULL",
                                Integer.class,
                                symbolsWithWarning))
                .isEqualTo(1);
        // A noncanonical auxiliary object produces a partial result, not a failed run.
        byte[] note = "auxiliary".getBytes(StandardCharsets.UTF_8);
        storage.putObject(
                PutObjectArgs.builder().bucket(bucket).object("eod/hose/notes.txt").stream(
                                new ByteArrayInputStream(note), note.length, -1)
                        .build());
        UUID partial = prepare(JobType.SYNC_METADATA);
        finish(partial, dispatch);
        String partialMeta =
                jdbc.queryForObject(
                        "SELECT meta_json::text FROM job_execution_histories WHERE id=?",
                        String.class,
                        partial);
        var partialResult = JSON.readTree(partialMeta);
        assertThat(partialResult.path("result").asText()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(partialResult.path("objectsSkipped").asInt()).isEqualTo(1);
        assertThat(partialResult.path("warning").asText()).contains("1 skipped object(s)");
        // Real bad Parquet still fails before replacing prior validated discovery.
        byte[] previous;
        try (var input =
                storage.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object("_metadata/metadata.json")
                                .build())) {
            previous = input.readAllBytes();
        }
        byte[] corrupt = "invalid-parquet".getBytes(StandardCharsets.UTF_8);
        storage.putObject(
                PutObjectArgs.builder().bucket(bucket).object("eod/hose/bad.parquet").stream(
                                new ByteArrayInputStream(corrupt), corrupt.length, -1)
                        .build());
        UUID failed = prepare(JobType.SYNC_METADATA);
        await(
                () -> {
                    dispatch.dispatch();
                    return "FAILED".equals(status(failed));
                },
                90,
                "failed metadata status applied to PostgreSQL");
        try (var input =
                storage.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object("_metadata/metadata.json")
                                .build())) {
            assertThat(input.readAllBytes()).isEqualTo(previous);
        }
        Path evidence =
                ROOT.resolve(
                        ".agent/check-results/P14-cross-service-integration/workflow-result.json");
        Files.createDirectories(evidence.getParent());
        Files.writeString(
                evidence,
                JSON.writeValueAsString(
                        Map.of(
                                "runId",
                                run.toString(),
                                "rows",
                                result.path("rows").asInt(),
                                "eodDataVersion",
                                eodVersion,
                                "indicatorDataVersion",
                                result.path("indicatorDataVersion").asText(),
                                "allStepsSuccess",
                                true,
                                "metadataFailureApplied",
                                true,
                                "partialWarningApplied",
                                true,
                                "symbolWarningApplied",
                                true,
                                "previousDiscoveryPreserved",
                                true)));
    }

    static UUID prepare(JobType type) {
        Instant acceptedAt = BUSINESS_CLOCK.plusSeconds(REQUEST_SEQUENCE.incrementAndGet());
        JobDefinition definition =
                context.getBean(JobDefinitionRepository.class).findAll().stream()
                        .filter(d -> d.getJobType() == type)
                        .findFirst()
                        .orElseThrow();
        Integer before =
                jdbc.queryForObject(
                        "SELECT count(*) FROM job_execution_histories WHERE job_id=? AND parent_log_id IS NULL",
                        Integer.class,
                        definition.getId());
        // Keep every ancestor definition available; schedule only this test entry.
        jdbc.update(
                "UPDATE job_definitions SET next_run=?",
                Timestamp.from(Instant.now().plusSeconds(86400)));
        jdbc.update(
                "UPDATE job_definitions SET next_run=?,claim_token=null,claim_until=null WHERE id=?",
                Timestamp.from(acceptedAt.minusSeconds(1)),
                definition.getId());
        // Preparation accepts an explicit clock. Keep the worker/provider fixture on the same
        // weekday; scheduler entry policy is covered by separate claim integration tests.
        var claim =
                context.getBean(SchedulerClaimService.class)
                        .claimJobDefinition(definition.getId(), acceptedAt, "integration")
                        .orElseThrow();
        context.getBean(JobRunPreparationService.class)
                .prepareScheduled(definition, claim, acceptedAt);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_execution_histories WHERE job_id=? AND parent_log_id IS NULL",
                                Integer.class,
                                definition.getId()))
                .isEqualTo(before + 1);
        return jdbc.queryForObject(
                "SELECT h.id FROM job_execution_histories h JOIN job_runs r ON r.id=h.job_run_id WHERE h.job_id=? AND h.parent_log_id IS NULL ORDER BY r.requested_at DESC LIMIT 1",
                UUID.class,
                definition.getId());
    }

    private static void proveLegacyStaleReadyWaitsBeforeKafkaAndResumesAfterRepair(
            MinioClient storage, SchedulerOutboxDispatcher dispatch) throws Exception {
        byte[] eodBefore = objectBytes(storage, "eod/hose/hpg.parquet");
        byte[] indicatorsBefore = objectBytes(storage, "indicators/ad_close/1d/hose/hpg.parquet");
        try {
            launch("analyzer", "p14_stale_input.py", true);
            finish(prepare(JobType.SYNC_METADATA), dispatch);
            // Isolate missing-day coverage from the independent 60-second version-cache window.
            context.getBean(CachedManifestReader.class)
                    .invalidate(DatasetRef.of("eod", Map.of("exchange", "hose", "code", "hpg")));
            var definition =
                    context.getBean(JobDefinitionRepository.class).findAll().stream()
                            .filter(job -> job.getJobType() == JobType.SYNC_INDICATORS)
                            .findFirst()
                            .orElseThrow();
            var claim =
                    context.getBean(SchedulerClaimService.class)
                            .claimJobDefinition(
                                    definition.getId(), BUSINESS_CLOCK, "stale-ready-fixture")
                            .orElseThrow();
            var claimedDefinition =
                    context.getBean(JobDefinitionRepository.class)
                            .findById(definition.getId())
                            .orElseThrow();
            // Direct legacy producer route exercises admission without a JobRun barrier.
            UUID root =
                    context.getBean(JobProducerRegistry.class)
                            .getProducer(JobType.SYNC_INDICATORS)
                            .prepareDispatch(claimedDefinition, claim, BUSINESS_CLOCK);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT job_run_id FROM job_execution_histories WHERE id=?",
                                    UUID.class,
                                    root))
                    .isNull();
            dispatch.dispatch();
            assertThat(status(root)).isNotIn("FAILED", "SUCCESS");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM scheduler_outbox_messages o "
                                            + "JOIN job_execution_histories h ON h.id=o.execution_id "
                                            + "WHERE h.parent_log_id=? AND o.status='PUBLISHED'",
                                    Integer.class,
                                    root))
                    .isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM scheduler_outbox_messages o JOIN job_execution_histories h "
                            + "ON h.id=o.execution_id WHERE h.parent_log_id=? AND o.status='PENDING' AND o.attempts=0",
                    Integer.class, root)).isEqualTo(1);
            assertThat(objectBytes(storage, "indicators/ad_close/1d/hose/hpg.parquet"))
                    .isEqualTo(indicatorsBefore);
            var input = JSON.readTree(Files.readString(directory.resolve("stale-eod.json")));
            assertThat(input.path("latestInputDay").asText())
                    .isLessThan(input.path("businessDate").asText());
            UUID waitingChild = jdbc.queryForObject(
                    "SELECT id FROM job_execution_histories WHERE parent_log_id=?", UUID.class, root);
            storage.putObject(PutObjectArgs.builder().bucket(bucket).object("eod/hose/hpg.parquet")
                    .stream(new ByteArrayInputStream(eodBefore), eodBefore.length, -1).build());
            finish(prepare(JobType.SYNC_METADATA), dispatch);
            context.getBean(CachedManifestReader.class).invalidate(
                    DatasetRef.of("eod", Map.of("exchange", "hose", "code", "hpg")));
            jdbc.update("UPDATE scheduler_outbox_messages SET available_at=now()-interval '1 second' WHERE execution_id=?",
                    waitingChild);
            finish(root, dispatch);
            assertThat(status(waitingChild)).isEqualTo("SUCCESS");
            Path evidence =
                    ROOT.resolve(
                            ".agent/check-results/P14-cross-service-integration/stale-ready-result.json");
            Files.createDirectories(evidence.getParent());
            Files.writeString(
                    evidence,
                    JSON.writeValueAsString(
                            Map.of(
                                    "businessDate",
                                    input.path("businessDate").asText(),
                                    "latestInputDay",
                                    input.path("latestInputDay").asText(),
                                    "legacyPublishedStaleReady",
                                    false,
                                    "dispatchWaitedForExactDay",
                                    true,
                                    "resumedSameExecutionAfterRepair",
                                    true,
                                    "previousOutputPreserved",
                                    true)));
        } finally {
            storage.putObject(
                    PutObjectArgs.builder().bucket(bucket).object("eod/hose/hpg.parquet").stream(
                                    new ByteArrayInputStream(eodBefore), eodBefore.length, -1)
                            .build());
        }
        finish(prepare(JobType.SYNC_METADATA), dispatch);
    }

    private static byte[] objectBytes(MinioClient storage, String path) throws Exception {
        try (var input =
                storage.getObject(GetObjectArgs.builder().bucket(bucket).object(path).build())) {
            return input.readAllBytes();
        }
    }

    static String status(UUID execution) {
        return jdbc.queryForObject(
                "SELECT status FROM job_execution_histories WHERE id=?", String.class, execution);
    }

    static void finish(UUID execution, SchedulerOutboxDispatcher dispatcher) throws Exception {
        await(
                () -> {
                    dispatcher.dispatch();
                    assertThat(status(execution)).isNotIn("FAILED", "BLOCKED");
                    return "SUCCESS".equals(status(execution));
                },
                150,
                "worker status completion " + execution);
    }

    static void launch(String app, String script, boolean wait) throws Exception {
        String python =
                System.getProperty("os.name").startsWith("Windows")
                        ? ".venv/Scripts/python.exe"
                        : ".venv/bin/python";
        Path log =
                directory.resolve(
                        script.equals("p14_stale_input.py")
                                ? "stale-input-fixture.log"
                                : app + ".log");
        var process =
                new ProcessBuilder(
                                ROOT.resolve(python).toString(),
                                ROOT.resolve("apps/" + app + "/tests/integration/" + script)
                                        .toString(),
                                config.toString())
                        .directory(ROOT.resolve("apps/" + app).toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        process.environment().put("PYTHONIOENCODING", "utf-8");
        process.environment().put("PYTHONUNBUFFERED", "1");
        var environment = JSON.readTree(Files.readString(config));
        process.environment().put("KAFKA_BOOTSTRAP_SERVERS", environment.path("kafka").asText());
        process.environment().put("MINIO_ENDPOINT", environment.path("minio").asText());
        process.environment().put("MINIO_ACCESS_KEY", environment.path("accessKey").asText());
        process.environment().put("MINIO_SECRET_KEY", environment.path("secretKey").asText());
        process.environment().put("MINIO_BUCKET", environment.path("bucket").asText());
        Process worker = process.start();
        if (!wait) WORKERS.add(worker);
        if (wait) {
            assertThat(worker.waitFor(90, TimeUnit.SECONDS))
                    .as("query process timeout; log %s", log)
                    .isTrue();
            assertThat(worker.exitValue()).as("process log %s", log).isZero();
        }
    }

    static void await(BooleanSupplier condition, int seconds, String description) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!condition.getAsBoolean()) {
            for (Process worker : WORKERS)
                assertThat(worker.isAlive()).as("worker failed; logs in %s", directory).isTrue();
            assertThat(System.nanoTime())
                    .as("Timeout: %s; worker logs in %s", description, directory)
                    .isLessThan(deadline);
            Thread.sleep(200);
        }
    }
}
