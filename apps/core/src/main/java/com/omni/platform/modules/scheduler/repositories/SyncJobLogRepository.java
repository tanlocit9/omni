package com.omni.platform.modules.scheduler.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.omni.platform.modules.scheduler.entities.SyncJobLog;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface SyncJobLogRepository extends BaseRepository<SyncJobLog> {

    Optional<SyncJobLog> findTopByJobIdOrderByTriggeredAtDesc(UUID jobId);

    List<SyncJobLog> findByJobIdAndStatus(UUID jobId, SyncJobLog.SyncStatus status);
}
