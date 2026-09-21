package com.omni.platform.modules.notifications.repositories;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.omni.platform.modules.notifications.dtos.NotificationChannel;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage;
import com.omni.platform.modules.notifications.entities.NotificationOutboxMessage.Provider;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface NotificationOutboxRepository
        extends BaseRepository<NotificationOutboxMessage>, NotificationOutboxRepositoryCustom {

    Optional<NotificationOutboxMessage> findByProviderAndChannelAndDeduplicationKey(
            Provider provider,
            NotificationChannel channel,
            String deduplicationKey);

    @Modifying
    @Query(value = """
            INSERT INTO notification_outbox_messages (
                provider, channel, notification_kind, schema_version, payload,
                deduplication_key, status, attempts, available_at)
            VALUES (
                CAST(:provider AS VARCHAR), CAST(:channel AS VARCHAR), CAST(:kind AS VARCHAR),
                :schemaVersion, :payload, :deduplicationKey, 'PENDING', 0, :availableAt)
            ON CONFLICT (provider, channel, deduplication_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            String provider,
            String channel,
            String kind,
            int schemaVersion,
            String payload,
            String deduplicationKey,
            Instant availableAt);

    long countByStatus(NotificationOutboxMessage.Status status);

    @Query(value = "select min(created_at) from notification_outbox_messages where status = 'PENDING'", nativeQuery = true)
    Optional<Instant> findOldestPendingCreatedAt();
}
