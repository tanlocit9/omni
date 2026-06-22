package com.omni.platform.modules.scheduler;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.entities.SyncJob;
import com.omni.platform.modules.scheduler.producers.SyncSymbolsJobProducer;
import com.omni.platform.modules.scheduler.repositories.SyncJobRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncJobScheduler {

    private final SyncJobRepository syncJobRepository;
    private final SyncSymbolsJobProducer syncSymbolsJobProducer;

    @Scheduled(fixedDelayString = "${scheduler.scan.interval-ms:60000}")
    @Transactional
    public void scan() {
        Instant now = Instant.now();
        List<SyncJob> dueJobs = syncJobRepository.findJobsDue(now);

        if (dueJobs.isEmpty()) {
            log.debug("No due jobs at {}", now);
            return;
        }

        log.info("Found {} due job(s)", dueJobs.size());

        for (SyncJob job : dueJobs) {
            try {
                switch (job.getJobType()) {
                    case STOCK_PRICE -> syncSymbolsJobProducer.publish(job, now);
                    default -> {
                    }
                }
            } catch (Exception e) {
                log.error("Failed to dispatch job [{}]: {}", job.getId(), e.getMessage(), e);
            }
        }
    }
}