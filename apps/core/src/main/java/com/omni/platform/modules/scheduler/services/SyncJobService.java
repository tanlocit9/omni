package com.omni.platform.modules.scheduler.services;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import com.omni.platform.modules.scheduler.entities.SyncJob;
import com.omni.platform.modules.scheduler.entities.SyncJobLog;
import com.omni.platform.modules.scheduler.repositories.SyncJobLogRepository;
import com.omni.platform.modules.scheduler.repositories.SyncJobRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SyncJobService {

    private final SyncJobRepository syncJobRepository;
    private final SyncJobLogRepository syncJobLogRepository;

    public SyncJobLog prepareForExecution(
            SyncJob job,
            Instant now) {

        SyncJobLog log = createPendingLog(job);

        updateNextRun(job, now);

        syncJobLogRepository.save(log);
        syncJobRepository.save(job);

        return log;
    }

    private SyncJobLog createPendingLog(SyncJob job) {

        SyncJobLog log = new SyncJobLog();
        log.setJob(job);
        log.setUsedSource(job.getSource());
        log.setStatus(SyncJobLog.SyncStatus.PENDING);
        log.setAttempt(1);

        return log;
    }

    private void updateNextRun(
            SyncJob job,
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