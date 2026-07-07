package com.omni.platform.modules.scheduler.seeders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
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

        log.info("Starting job definition seeding...");

        JobDefinitionConfig.JOB_DEFINITION_SEEDS.forEach(seed -> {

            jobDefinitionRepository.findBySourceAndJobTypeAndCronExpr(
                    seed.source(),
                    seed.jobType(),
                    seed.cronExpr()).ifPresentOrElse(
                            existing -> log.info("Job definition [{}/{}/{}] already exists, skipping.",
                                    seed.source(), seed.jobType(), seed.cronExpr()),
                            () -> {
                                jobDefinitionRepository.save(seed.toEntity());
                                log.info("Seeded job definition [{}/{}] with cron [{}]",
                                        seed.source(), seed.jobType(), seed.cronExpr());
                            });
        });

        log.info("Job definition seeding completed.");
    }
}