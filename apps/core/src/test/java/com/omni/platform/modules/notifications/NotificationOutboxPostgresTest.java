package com.omni.platform.modules.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.dtos.NotificationRequest;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationKind;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationSeverity;
import com.omni.platform.modules.notifications.dtos.NotificationRequest.NotificationType;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage.Provider;
import com.omni.platform.modules.notifications.repositories.NotificationOutboxClaim;
import com.omni.platform.modules.notifications.repositories.NotificationOutboxRepository;
import com.omni.platform.modules.notifications.services.NotificationOutboxService;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=filesystem:../../database/migrations",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.task.scheduling.enabled=false",
        "app.seed.job-definitions.enabled=false"
})
class NotificationOutboxPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("omni_notification_outbox_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired private NotificationOutboxService service;
    @Autowired private NotificationOutboxRepository repository;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
        jdbcTemplate.update("DELETE FROM notification_provider_rate_limits");
    }

    @Test
    void concurrentClaimsFenceOneOwnerAndTerminalRowsStayExcluded() throws Exception {
        NotificationOutboxMessage message = service.enqueue(request("claim-once"), NOW);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<List<NotificationOutboxClaim>> first = executor.submit(() -> {
                start.await();
                return service.claimPending(NOW, "node-a", Duration.ofMinutes(2), 1);
            });
            Future<List<NotificationOutboxClaim>> second = executor.submit(() -> {
                start.await();
                return service.claimPending(NOW, "node-b", Duration.ofMinutes(2), 1);
            });
            start.countDown();
            List<NotificationOutboxClaim> combined = new java.util.ArrayList<>();
            combined.addAll(first.get());
            combined.addAll(second.get());

            assertThat(combined).singleElement().satisfies(claim -> {
                assertThat(claim.messageId()).isEqualTo(message.getId());
                assertThat(service.markDelivered(claim, NOW.plusSeconds(1))).isTrue();
                assertThat(service.markDead(claim, NOW.plusSeconds(2), "stale")).isFalse();
            });
        }

        assertThat(service.claimPending(NOW.plus(Duration.ofMinutes(3)), "node-c", Duration.ofMinutes(2), 10))
                .isEmpty();
    }

    @Test
    void expiredLeaseIsRecoveredButUnexpiredLeaseIsNotStolen() {
        NotificationOutboxMessage message = service.enqueue(request("lease-recovery"), NOW);
        NotificationOutboxClaim original = service.claimPending(NOW, "node-a", Duration.ofSeconds(30), 1)
                .getFirst();

        assertThat(service.claimPending(NOW.plusSeconds(29), "node-b", Duration.ofMinutes(1), 1)).isEmpty();
        NotificationOutboxClaim recovered = service.claimPending(
                NOW.plusSeconds(31), "node-b", Duration.ofMinutes(1), 1).getFirst();

        assertThat(recovered.messageId()).isEqualTo(message.getId());
        assertThat(recovered.claimToken()).isNotEqualTo(original.claimToken());
        assertThat(recovered.attempts()).isEqualTo(2);
        assertThat(service.markDelivered(original, NOW.plusSeconds(32))).isFalse();
        assertThat(service.markDelivered(recovered, NOW.plusSeconds(32))).isTrue();
    }

    @Test
    void providerPermitIsDistributedAndReopensAtPersistedBoundary() {
        Duration interval = Duration.ofSeconds(2);

        assertThat(service.acquireProviderPermit(Provider.TELEGRAM, NOW, interval)).isTrue();
        assertThat(service.acquireProviderPermit(Provider.TELEGRAM, NOW.plusSeconds(1), interval)).isFalse();
        assertThat(service.acquireProviderPermit(Provider.TELEGRAM, NOW.plusSeconds(2), interval)).isTrue();
    }

    @Test
    void concurrentEnqueueReturnsOneCanonicalDeliveryRow() throws Exception {
        NotificationRequest request = request("same-delivery");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<UUID> first = executor.submit(() -> {
                start.await();
                return service.enqueue(request, NOW).getId();
            });
            Future<UUID> second = executor.submit(() -> {
                start.await();
                return service.enqueue(request, NOW).getId();
            });
            start.countDown();

            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(repository.count()).isOne();
    }

    @Test
    @Transactional
    void deadAndSentRowsAreNeverClaimed() {
        insertTerminal("terminal-sent", "SENT");
        insertTerminal("terminal-dead", "DEAD");

        assertThat(service.claimPending(NOW.plusSeconds(1), "node-a", Duration.ofMinutes(1), 10)).isEmpty();
    }

    private void insertTerminal(String key, String status) {
        entityManager.createNativeQuery("""
                INSERT INTO notification_outbox_messages (
                    provider, channel, notification_kind, schema_version, payload,
                    deduplication_key, status, attempts, available_at)
                VALUES ('TELEGRAM', 'SIGNALS', 'MANUAL_GENERIC', 1, '{}', :key, :status, 0, :now)
                """)
                .setParameter("key", key)
                .setParameter("status", status)
                .setParameter("now", NOW)
                .executeUpdate();
        entityManager.flush();
    }

    private static NotificationRequest request(String key) {
        return new NotificationRequest(
                NotificationChannel.SIGNALS,
                NotificationType.SIGNAL,
                NotificationKind.MANUAL_GENERIC,
                NotificationSeverity.INFO,
                "Title",
                "Message",
                Map.of("source", "test"),
                key,
                null);
    }
}
