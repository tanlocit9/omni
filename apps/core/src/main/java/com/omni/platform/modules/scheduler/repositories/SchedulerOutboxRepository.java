package com.omni.platform.modules.scheduler.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.omni.platform.modules.scheduler.entities.SchedulerOutboxMessage;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface SchedulerOutboxRepository extends BaseRepository<SchedulerOutboxMessage>, SchedulerOutboxRepositoryCustom {

    List<SchedulerOutboxMessage> findAllByExecution_IdOrderByMessageIndex(UUID executionId);
}
