package com.omni.platform.modules.scheduler.seeders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class JobDefinitionSeeder implements CommandLineRunner {

    private final JobDefinitionRepository jobDefinitionRepository;

    @Value("${app.seed.job-definitions.enabled:false}")
    private boolean seedEnabled;

    @Override
    public void run(String... args) {
        if (!seedEnabled) {
            log.info("Job definition seeding disabled (app.seed.job-definitions.enabled=false), skipping.");
            return;
        }

        log.info("Starting two-phase job definition seeding (config_json override mode)...");

        List<JobDefinition> bootstrapJobs = JobDefinitionConfig.BOOTSTRAP_JOB_DEFINITION_SEEDS.stream()
                .map(this::upsert)
                .toList();
        boolean bootstrapRequired = bootstrapJobs.stream()
                .anyMatch(job -> job.getNextRun() == null);
        Instant deferredNextRun = bootstrapRequired
                ? Instant.now().plus(1, ChronoUnit.DAYS)
                : null;

        JobDefinitionConfig.DEFERRED_JOB_DEFINITION_SEEDS.forEach(seed -> {
            JobDefinition job = upsert(seed);
            if (bootstrapRequired && job.getNextRun() == null) {
                job.setNextRun(deferredNextRun);
                jobDefinitionRepository.save(job);
                log.info("Deferred job definition [{}/{}] until [{}] while bootstrap jobs initialize",
                        seed.source(), seed.jobType(), deferredNextRun);
            }
        });

        log.info("Job definition seeding completed. Bootstrap deferral applied: {}", bootstrapRequired);
    }

    private JobDefinition upsert(JobDefinitionSeed seed) {
        validateCronExpression(seed);

        return jobDefinitionRepository.findBySourceAndJobTypeAndCronExpr(
                seed.source(),
                seed.jobType(),
                seed.cronExpr()).map(existing -> {
                    existing.setConfigJson(seed.config());
                    JobDefinition saved = jobDefinitionRepository.save(existing);
                    log.info("Overrode config_json for job definition [{}/{}] with cron [{}]",
                            seed.source(), seed.jobType(), seed.cronExpr());
                    return saved;
                }).orElseGet(() -> {
                    JobDefinition saved = jobDefinitionRepository.save(seed.toEntity());
                    log.info("Seeded job definition [{}/{}] with cron [{}]",
                            seed.source(), seed.jobType(), seed.cronExpr());
                    return saved;
                });
    }

    private static void validateCronExpression(JobDefinitionConfig.JobDefinitionSeed seed) {
        try {
            CronExpression.parse(seed.cronExpr());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Invalid cron expression for job definition [%s/%s]: %s"
                            .formatted(seed.source(), seed.jobType(), seed.cronExpr()),
                    exception);
        }
    }
}