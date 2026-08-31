package com.omni.platform.modules.scheduler.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface JobExecutionHistoryRepository extends BaseRepository<JobExecutionHistory> {

    List<JobExecutionHistory> findAllByParentLogId(UUID parentLogId);

    Optional<JobExecutionHistory> findFirstByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(UUID jobId);

    List<JobExecutionHistory> findTop20ByJob_IdAndParentLogIdIsNullOrderByTriggeredAtDesc(UUID jobId);

    /**
     * Loads a job execution row with a database write lock.
     * <p>
     * Parent aggregation must call this inside the same transaction that reads child
     * executions and updates the parent. The lock serializes concurrent final-child
     * completions for the same parent so exactly one transaction can observe and
     * publish the first terminal parent transition.
     *
     * @param id execution id to lock
     * @return locked execution row when it exists
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select history from JobExecutionHistory history where history.id = :id")
    Optional<JobExecutionHistory> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT new_offset FROM job_execution_histories
            WHERE job_id = :jobId
            AND meta_json ->> 'workType' = 'SYMBOL'
            AND meta_json ->> 'workKey' = :workKey
            AND new_offset IS NOT NULL
            ORDER BY finished_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<String> findLastOffset(@Param("jobId") UUID jobId, @Param("workKey") String workKey);

}
