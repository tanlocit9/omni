package com.omni.platform.modules.scheduler.repositories;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.omni.platform.modules.scheduler.entities.SyncJob;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface SyncJobRepository extends BaseRepository<SyncJob> {

    @Query("""
            SELECT j FROM SyncJob j
            WHERE j.isActive = true
            AND j.nextRun <= :now
            """)
    List<SyncJob> findJobsDue(@Param("now") Instant now);
}