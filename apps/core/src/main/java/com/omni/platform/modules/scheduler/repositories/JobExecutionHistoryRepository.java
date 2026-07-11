package com.omni.platform.modules.scheduler.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface JobExecutionHistoryRepository extends BaseRepository<JobExecutionHistory> {

    Optional<JobExecutionHistory> findTopByJobIdOrderByTriggeredAtDesc(UUID jobId);

    List<JobExecutionHistory> findByJobIdAndStatus(UUID jobId, JobExecutionHistory.JobStatus status);

    List<JobExecutionHistory> findAllByParentLogId(UUID parentLogId);

    @Query(value = """
            SELECT new_offset FROM job_execution_history
            WHERE job_id = :jobId
            AND meta_json ->> 'symbolKey' = :symbolKey
            AND new_offset IS NOT NULL
            ORDER BY finished_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findLastOffset(@Param("jobId") UUID jobId, @Param("symbolKey") String symbolKey);
}