package com.omni.platform.modules.scheduler.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface SchedulerOutboxRepository extends BaseRepository<SchedulerOutboxMessage>, SchedulerOutboxRepositoryCustom {

    List<SchedulerOutboxMessage> findAllByExecution_IdOrderByMessageIndex(UUID executionId);

    @Query("""
            SELECT message FROM SchedulerOutboxMessage message
            WHERE message.execution.id = :executionId
               OR message.execution.parentLogId = :executionId
            ORDER BY message.messageIndex
            """)
    List<SchedulerOutboxMessage> findAllForExecution(@Param("executionId") UUID executionId);
}
