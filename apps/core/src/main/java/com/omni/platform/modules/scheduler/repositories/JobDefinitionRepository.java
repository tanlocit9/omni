package com.omni.platform.modules.scheduler.repositories;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.shared.repositories.BaseRepository;

@Repository
public interface JobDefinitionRepository extends BaseRepository<JobDefinition> {

    @Query("""
            SELECT j FROM JobDefinition j
            WHERE j.isActive = true
            AND j.nextRun <= :now OR j.nextRun IS NULL
            """)
    List<JobDefinition> findJobsDue(@Param("now") Instant now);

    Optional<JobDefinition> findBySourceAndJobTypeAndCronExpr(JobDefinition.DataSource source,
            JobDefinition.JobType jobType,
            String cronExpr);
}