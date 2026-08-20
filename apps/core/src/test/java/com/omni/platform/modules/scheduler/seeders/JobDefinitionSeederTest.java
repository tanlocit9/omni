package com.omni.platform.modules.scheduler.seeders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;

class JobDefinitionSeederTest {

    private JobDefinitionRepository repository;
    private JobDefinitionSeeder seeder;
    private Map<String, JobDefinition> persistedJobs;

    @BeforeEach
    void setUp() {
        repository = mock(JobDefinitionRepository.class);
        seeder = new JobDefinitionSeeder(repository);
        ReflectionTestUtils.setField(seeder, "seedEnabled", true);
        persistedJobs = new HashMap<>();

        when(repository.findBySourceAndJobTypeAndCronExpr(any(), any(), any()))
                .thenAnswer(invocation -> Optional.ofNullable(persistedJobs.get(key(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))));
        when(repository.save(any(JobDefinition.class))).thenAnswer(invocation -> {
            JobDefinition job = invocation.getArgument(0);
            persistedJobs.put(key(job.getSource(), job.getJobType(), job.getCronExpr()), job);
            return job;
        });
    }

    @Test
    void seedsBootstrapPhaseBeforeDeferredPhaseAndDefersNullNextRuns() throws Exception {
        Instant before = Instant.now();

        seeder.run();

        Instant after = Instant.now();
        JobDefinitionSeed firstBootstrap = JobDefinitionConfig.BOOTSTRAP_JOB_DEFINITION_SEEDS.getFirst();
        JobDefinitionSeed firstDeferred = JobDefinitionConfig.DEFERRED_JOB_DEFINITION_SEEDS.getFirst();
        InOrder ordered = inOrder(repository);
        ordered.verify(repository).findBySourceAndJobTypeAndCronExpr(
                firstBootstrap.source(), firstBootstrap.jobType(), firstBootstrap.cronExpr());
        ordered.verify(repository).save(any(JobDefinition.class));
        for (int i = 1; i < JobDefinitionConfig.BOOTSTRAP_JOB_DEFINITION_SEEDS.size(); i++) {
            ordered.verify(repository).findBySourceAndJobTypeAndCronExpr(any(), any(), any());
            ordered.verify(repository).save(any(JobDefinition.class));
        }
        ordered.verify(repository).findBySourceAndJobTypeAndCronExpr(
                firstDeferred.source(), firstDeferred.jobType(), firstDeferred.cronExpr());

        assertThat(JobDefinitionConfig.DEFERRED_JOB_DEFINITION_SEEDS)
                .allSatisfy(seed -> assertThat(job(seed).getNextRun())
                        .isBetween(before.plus(Duration.ofDays(1)), after.plus(Duration.ofDays(1))));
    }

    @Test
    void preservesExistingDeferredNextRunWhenBootstrapIsRequired() throws Exception {
        JobDefinitionSeed deferredSeed = JobDefinitionConfig.DEFERRED_JOB_DEFINITION_SEEDS.getFirst();
        JobDefinition existing = deferredSeed.toEntity();
        Instant scheduled = Instant.parse("2030-01-02T03:04:05Z");
        existing.setNextRun(scheduled);
        persistedJobs.put(key(existing.getSource(), existing.getJobType(), existing.getCronExpr()), existing);

        seeder.run();

        assertThat(job(deferredSeed).getNextRun()).isEqualTo(scheduled);
    }

    @Test
    void doesNotDeferRemainingJobsWhenEveryBootstrapJobHasNextRun() throws Exception {
        Instant scheduled = Instant.parse("2030-01-02T03:04:05Z");
        JobDefinitionConfig.BOOTSTRAP_JOB_DEFINITION_SEEDS.forEach(seed -> {
            JobDefinition existing = seed.toEntity();
            existing.setNextRun(scheduled);
            persistedJobs.put(key(existing.getSource(), existing.getJobType(), existing.getCronExpr()), existing);
        });

        seeder.run();

        assertThat(JobDefinitionConfig.DEFERRED_JOB_DEFINITION_SEEDS)
                .allSatisfy(seed -> assertThat(job(seed).getNextRun()).isNull());
    }

    @Test
    void skipsRepositoryAccessWhenDisabled() throws Exception {
        ReflectionTestUtils.setField(seeder, "seedEnabled", false);

        seeder.run();

        verify(repository, never()).findBySourceAndJobTypeAndCronExpr(any(), any(), any());
        verify(repository, never()).save(any(JobDefinition.class));
    }

    private JobDefinition job(JobDefinitionSeed seed) {
        return persistedJobs.get(key(seed.source(), seed.jobType(), seed.cronExpr()));
    }

    private static String key(Object source, Object jobType, String cronExpr) {
        return source + "|" + jobType + "|" + cronExpr;
    }
}
