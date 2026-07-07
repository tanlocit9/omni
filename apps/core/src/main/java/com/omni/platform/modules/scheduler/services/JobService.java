package com.omni.platform.modules.scheduler.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;
import com.omni.platform.modules.scheduler.repositories.JobExecutionHistoryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobDefinitionRepository jobDefinitionRepository;
    private final JobExecutionHistoryRepository jobExecutionHistoryRepository;

    public JobExecutionHistory prepareForExecution(
            JobDefinition job,
            Instant now) {

        JobExecutionHistory log = createPendingLog(job);

        updateNextRun(job, now);

        jobExecutionHistoryRepository.save(log);
        jobDefinitionRepository.save(job);

        return log;
    }

    private JobExecutionHistory createPendingLog(JobDefinition job) {

        JobExecutionHistory log = new JobExecutionHistory();
        log.setJob(job);
        log.setUsedSource(job.getSource());
        log.setStatus(JobExecutionHistory.JobStatus.PENDING);
        log.setAttempt(1);

        return log;
    }

    private void updateNextRun(
            JobDefinition job,
            Instant now) {

        if (job.getCronExpr() == null) {
            return;
        }

        CronExpression cron = CronExpression.parse(job.getCronExpr());

        LocalDateTime nextRun = cron.next(
                LocalDateTime.ofInstant(
                        now,
                        ZoneOffset.UTC));

        if (nextRun != null) {
            job.setNextRun(
                    nextRun.toInstant(
                            ZoneOffset.UTC));
        }
    }

}