package com.omni.platform.modules.scheduler;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.producers.EvaluateSignalsJobProducer;
import com.omni.platform.modules.scheduler.producers.PrecomputeSectorFeaturesJobProducer;
import com.omni.platform.modules.scheduler.producers.PrecomputeSymbolFeaturesJobProducer;
import com.omni.platform.modules.scheduler.producers.SectorRotationBacktestJobProducer;
import com.omni.platform.modules.scheduler.producers.SectorTransitionAnalyzeJobProducer;
import com.omni.platform.modules.scheduler.producers.SectorTransitionOutcomeEvaluationJobProducer;
import com.omni.platform.modules.scheduler.producers.SyncIndicatorsJobProducer;
import com.omni.platform.modules.scheduler.producers.SyncSignalsJobProducer;
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
    private final SyncSignalsJobProducer syncSignalsJobProducer;
    private final EvaluateSignalsJobProducer evaluateSignalsJobProducer;
    private final PrecomputeSymbolFeaturesJobProducer precomputeSymbolFeaturesJobProducer;
    private final PrecomputeSectorFeaturesJobProducer precomputeSectorFeaturesJobProducer;
    private final SectorRotationBacktestJobProducer sectorRotationBacktestJobProducer;
    private final SectorTransitionAnalyzeJobProducer sectorTransitionAnalyzeJobProducer;
    private final SectorTransitionOutcomeEvaluationJobProducer sectorTransitionOutcomeEvaluationJobProducer;

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
            log.info("Dispatching due job [{}] type [{}] source [{}] nextRun [{}] active [{}]", job.getId(),
                    job.getJobType(), job.getSource(), job.getNextRun(), job.getIsActive());
            try {
                switch (job.getJobType()) {
                    case SYNC_STOCK_PRICE -> syncStockPriceProducer.publish(job, now);
                    case SYNC_SYMBOLS -> symbolsJobProducer.publish(job, now);
                    case SYNC_INDICATORS -> syncIndicatorsJobProducer.publish(job, now);
                    case SYNC_SIGNALS -> syncSignalsJobProducer.publish(job, now);
                    case EVALUATE_SIGNALS -> evaluateSignalsJobProducer.publish(job, now);
                    case PRECOMPUTE_SYMBOL_FEATURES -> precomputeSymbolFeaturesJobProducer.publish(job, now);
                    case PRECOMPUTE_SECTOR_FEATURES -> precomputeSectorFeaturesJobProducer.publish(job, now);
                    case SECTOR_ROTATION_BACKTEST -> sectorRotationBacktestJobProducer.publish(job, now);
                    case SECTOR_TRANSITION_ANALYZE -> sectorTransitionAnalyzeJobProducer.publish(job, now);
                    case SECTOR_TRANSITION_EVALUATE_OUTCOMES -> sectorTransitionOutcomeEvaluationJobProducer.publish(job, now);
                }
            } catch (Exception e) {
                log.error("Failed to dispatch job [{}]: {}", job.getId(), e.getMessage(), e);
            }
        }
    }
}