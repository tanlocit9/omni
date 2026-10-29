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
public interface JobDefinitionRepository
        extends BaseRepository<JobDefinition>, JobDefinitionClaimRepository {

    @Query(
            """
            SELECT j FROM JobDefinition j
            WHERE j.isActive = true AND j.scheduleEnabled = true
            AND (j.nextRun <= :now OR j.nextRun IS NULL)
            ORDER BY CASE WHEN j.nextRun IS NULL THEN 0 ELSE 1 END ASC,
            j.nextRun ASC,
            j.id ASC
            """)
    List<JobDefinition> findJobsDue(@Param("now") Instant now);

    Optional<JobDefinition> findBySourceAndJobTypeAndCronExpr(
            JobDefinition.DataSource source, JobDefinition.JobType jobType, String cronExpr);
    @Query(value = "SELECT EXISTS(SELECT 1 FROM job_definition_consolidations c "
            + "WHERE c.state='APPLIED' AND (c.retained_definition_id=:id "
            + "OR EXISTS(SELECT 1 FROM jsonb_array_elements(c.inventory_json) v WHERE v->>'id'=CAST(:id AS text))))",
            nativeQuery = true)
    boolean isConsolidatedPreset(@Param("id") java.util.UUID id);

}
