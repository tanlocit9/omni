package com.omni.platform.modules.scheduler.producers;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobConfigMapper;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.entities.JobDefinition;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.entities.JobExecutionHistory;
import com.omni.platform.modules.scheduler.messaging.IntradayEodJobMessage;
import com.omni.platform.modules.scheduler.messaging.KafkaMessage;
import com.omni.platform.modules.scheduler.repositories.SymbolRepository;
import com.omni.platform.modules.scheduler.services.JobService;
import com.omni.platform.shared.executions.WorkIdentity;
import com.omni.platform.shared.executions.WorkType;
import com.omni.platform.shared.infrastructure.kafka.KafkaPublisher;

@Component
public class SyncIntradayEodJobProducer extends JobProducer {
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final SymbolRepository symbolRepository;

    @Value("${kafka.topics.topic-sync-intraday-eod}")
    private String topic;

    public SyncIntradayEodJobProducer(JobService jobService, KafkaPublisher kafkaPublisher,
            SymbolRepository symbolRepository) {
        super(jobService, kafkaPublisher);
        this.symbolRepository = symbolRepository;
    }

    @Override
    public JobType getJobType() {
        return JobType.SYNC_INTRADAY_EOD;
    }

    @Override
    protected String getTopic() {
        return topic;
    }

    @Override
    protected List<KafkaMessage> buildMessages(JobDefinition job, JobExecutionHistory parent, Instant now) {
        Map<String, Object> jobConfig = job.getConfigJson() == null ? Map.of() : job.getConfigJson();
        LocalDate tradingDate = latestCompletedWeekday(now.atZone(VIETNAM_ZONE).toLocalDate());

        return configuredExchanges(jobConfig).stream()
                .flatMap(exchange -> symbolRepository.findAllActiveSymbolKeysByExchange(exchange).stream())
                .map(symbol -> {
                    String symbolKey = symbol.symbolKey();
                    String projectionExchange = normalizeExchange(symbol.getExchange());
                    Map<String, Object> metadata = new HashMap<>(jobConfig);
                    metadata.put(JobDefinitionConfig.CONFIG_KEY_EXCHANGES, List.of(projectionExchange));
                    metadata.put("tradingDate", tradingDate.toString());
                    JobExecutionHistory child = jobService.createChildExecution(
                            parent.getId(), WorkIdentity.of(WorkType.SYMBOL, symbolKey), metadata, now);
                    return new KafkaMessage(symbolKey, new IntradayEodJobMessage(
                            job.getId(), child.getId(), parent.getId(), job.getSource().name(),
                            WorkType.SYMBOL, symbolKey, symbolKey, projectionExchange, tradingDate,
                            "VCI", metadata));
                })
                .toList();
    }

    private static List<String> configuredExchanges(Map<String, Object> config) {
        List<String> exchanges = JobConfigMapper.readStringList(
                config, JobDefinitionConfig.CONFIG_KEY_EXCHANGES);
        if (exchanges.isEmpty()) {
            exchanges = JobDefinitionConfig.VIETNAM_EXCHANGES;
        }
        return exchanges.stream()
                .map(SyncIntradayEodJobProducer::normalizeExchange)
                .filter(exchange -> !exchange.isBlank())
                .distinct()
                .toList();
    }

    private static String normalizeExchange(String exchange) {
        return exchange == null ? "" : exchange.trim().toUpperCase(Locale.ROOT);
    }

    private static LocalDate latestCompletedWeekday(LocalDate candidate) {
        while (candidate.getDayOfWeek() == DayOfWeek.SATURDAY
                || candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }
}
