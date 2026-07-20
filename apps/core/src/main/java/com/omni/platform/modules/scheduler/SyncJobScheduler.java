package com.omni.platform.modules.scheduler;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.producers.SyncIndicatorsJobProducer;
import com.omni.platform.modules.scheduler.producers.SyncStockPriceJobProducer;
import com.omni.platform.modules.scheduler.producers.SyncSymbolsJobProducer;
import com.omni.platform.modules.scheduler.repositories.JobDefinitionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncJobScheduler {

    private final JobDefinitionRepository jobDefinitionRepository;
    private final SyncStockPriceJobProducer syncStockPriceProducer;
    private final SyncSymbolsJobProducer symbolsJobProducer;
    private final SyncIndicatorsJobProducer syncIndicatorsJobProducer;

    @Scheduled(fixedDelayString = "${app.scheduler.global.fixedDelayString:30000}")
    public void scan() {
        Instant now = Instant.now();
        List<JobDefinition> dueJobs = jobDefinitionRepository.findJobsDue(now);

        if (dueJobs.isEmpty()) {
            log.debug("No due jobs at {}", now);
            return;
        }

        log.info("Found {} due job(s)", dueJobs.size());

        for (JobDefinition job : dueJobs) {
            try {
                switch (job.getJobType()) {
                    case SYNC_STOCK_PRICE -> syncStockPriceProducer.publish(job, now);
                    case SYNC_SYMBOLS -> symbolsJobProducer.publish(job, now);
                    case SYNC_INDICATORS -> syncIndicatorsJobProducer.publish(job, now);
                }
            } catch (Exception e) {
                log.error("Failed to dispatch job [{}]: {}", job.getId(), e.getMessage(), e);
            }
        }
    }
}