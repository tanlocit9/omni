package com.omni.platform.modules.scheduler.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;

@ActiveProfiles("test")
@DataJpaTest(properties = {
        "spring.test.database.replace=none",
        "spring.datasource.url=jdbc:h2:mem:job-definition-repository-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class JobDefinitionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-11T15:00:00Z");

    @Autowired
    private JobDefinitionRepository repository;

    @Test
    @DisplayName("findJobsDue returns active due jobs in deterministic order")
    void findJobsDueFiltersAndOrdersByNextRunThenId() {
        JobDefinition activePastFirst = saveJob("active past first", true, NOW.minusSeconds(60));
        JobDefinition activePastSecond = saveJob("active past second", true, NOW.minusSeconds(60));
        JobDefinition activeEqual = saveJob("active equal", true, NOW);
        JobDefinition activeFuture = saveJob("active future", true, NOW.plusSeconds(60));
        JobDefinition activeNull = saveJob("active null", true, null);
        JobDefinition inactivePast = saveJob("inactive past", false, NOW.minusSeconds(60));
        JobDefinition inactiveFuture = saveJob("inactive future", false, NOW.plusSeconds(60));
        JobDefinition inactiveNull = saveJob("inactive null", false, null);

        List<JobDefinition> dueJobs = repository.findJobsDue(NOW);

        assertThat(dueJobs)
                .extracting(JobDefinition::getNextRun)
                .containsExactly(null, NOW.minusSeconds(60), NOW.minusSeconds(60), NOW);

        assertThat(dueJobs.subList(1, 3))
                .containsExactlyInAnyOrder(activePastFirst, activePastSecond);

        assertThat(repository.findJobsDue(NOW))
                .containsExactlyElementsOf(dueJobs);

        assertThat(dueJobs)
                .doesNotContain(activeFuture, inactivePast, inactiveFuture, inactiveNull);
    }

    private JobDefinition saveJob(String title, boolean active, Instant nextRun) {
        JobDefinition job = new JobDefinition();
        job.setSource(DataSource.VND);
        job.setJobType(JobType.SYNC_STOCK_PRICE);
        job.setCronExpr(title);
        job.setTitle(title);
        job.setIsActive(active);
        job.setNextRun(nextRun);
        return repository.saveAndFlush(job);
    }
}
