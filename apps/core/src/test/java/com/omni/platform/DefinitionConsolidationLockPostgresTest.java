package com.omni.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.omni.platform.modules.scheduler.work.DefinitionConsolidationService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Rollback must not invert PostgreSQL UUID row order across the signed-long boundary. */
@Testcontainers
@Timeout(120)
class DefinitionConsolidationLockPostgresTest {
    @Container
    static final PostgreSQLContainer<?> DATABASE = new PostgreSQLContainer<>("postgres:16-alpine");

    static ConfigurableApplicationContext context;

    @BeforeAll
    static void boot() {
        context =
                PlatformStartupPostgresTest.startApplication(
                        DATABASE,
                        true,
                        ctx ->
                                ctx.getEnvironment()
                                        .getPropertySources()
                                        .addFirst(
                                                new MapPropertySource(
                                                        "consolidation-lock-test",
                                                        Map.of(
                                                                "app.scheduler.job-run.graph-expansion-enabled",
                                                                true))));
    }

    @AfterAll
    static void shutdown() {
        if (context != null) context.close();
    }

    @Test
    void rollbackWaitsForLowestDatabaseUuidWithoutHoldingHigherDefinitionLock() throws Exception {
        var jdbc = context.getBean(JdbcTemplate.class);
        var service = context.getBean(DefinitionConsolidationService.class);
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID high = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff1");
        UUID template =
                jdbc.queryForObject(
                        "SELECT id FROM job_definitions WHERE job_type='SYNC_STOCK_PRICE' ORDER BY id LIMIT 1",
                        UUID.class);
        jdbc.update("UPDATE job_definitions SET is_active=false");
        int hour = 1;
        for (UUID id : new UUID[] {low, high}) {
            jdbc.update(
                    "INSERT INTO job_definitions(id,title,source,job_type,cron_expr,config_json,fallback_sources,is_active,schedule_enabled) "
                            + "SELECT ?,title,source,job_type,?,config_json,fallback_sources,true,true FROM job_definitions WHERE id=?",
                    id,
                    "0 0 " + hour++ + " 1 1 *",
                    template);
        }
        var now = Instant.parse("2035-01-02T12:00:00Z");
        UUID change = service.consolidate(now).getFirst();
        var backend = new AtomicInteger();
        var entered = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor();
                var blocker = context.getBean(DataSource.class).getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement =
                    blocker.prepareStatement(
                            "SELECT id FROM job_definitions WHERE id=? FOR UPDATE")) {
                statement.setObject(1, low);
                statement.executeQuery().close();
            }
            var rollback =
                    workers.submit(
                            () -> {
                                var transaction =
                                        new TransactionTemplate(
                                                context.getBean(PlatformTransactionManager.class));
                                transaction.setTimeout(30);
                                return transaction.execute(
                                        ignored -> {
                                            backend.set(
                                                    jdbc.queryForObject(
                                                            "SELECT pg_backend_pid()",
                                                            Integer.class));
                                            entered.countDown();
                                            return service.rollback(change, now);
                                        });
                            });
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                while (!Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE pid=? AND wait_event_type='Lock')",
                                Boolean.class,
                                backend.get()))) {
                    assertThat(System.nanoTime())
                            .as("rollback must reach a real database lock wait")
                            .isLessThan(deadline);
                    Thread.sleep(20);
                }
                // Preparation/consolidation already owns low and must still be able to lock high.
                // Before the fix, rollback held high first and this NOWAIT query failed.
                try (var statement =
                        blocker.prepareStatement(
                                "SELECT id FROM job_definitions WHERE id=? FOR UPDATE NOWAIT")) {
                    statement.setObject(1, high);
                    try (var row = statement.executeQuery()) {
                        assertThat(row.next()).isTrue();
                    }
                }
            } finally {
                blocker.rollback();
            }
            assertThat(rollback.get(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT state FROM job_definition_consolidations WHERE id=?",
                                String.class,
                                change))
                .isEqualTo("ROLLED_BACK");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM job_definitions WHERE id IN (?,?) "
                                        + "AND schedule_enabled AND canonical_definition_id IS NULL",
                                Integer.class,
                                low,
                                high))
                .isEqualTo(2);
    }
}
