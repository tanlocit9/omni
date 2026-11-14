package com.omni.platform.modules.scheduler.metadata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.omni.platform.modules.scheduler.messaging.ExpandedJobMessage;

/** Server-owned declarations for the snapshot-capable price/indicator/signal chain. */
@Service
public class OutputDeclarations {
    private final boolean enabled;

    @Value("${app.output-registration.versioned-storage-enabled:false}")
    private boolean versionedStorage;

    public String storagePolicy() {
        return versionedStorage ? "VERSIONED" : "CANONICAL";
    }

    public OutputDeclarations(
            @Value("${app.output-registration.enrollment-enabled:false}") boolean enabled,
            @Value("${app.output-registration.shadow-enabled:false}") boolean consumer) {
        if (enabled && !consumer)
            throw new IllegalArgumentException("Enrollment requires registration consumer");
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<OutputRegistrationEvent.LogicalOutput> required(ExpandedJobMessage work) {
        if (!enabled) return List.of();
        return requiredForEnrolledWork(work);
    }

    public List<OutputRegistrationEvent.LogicalOutput> requiredForEnrolledWork(
            ExpandedJobMessage work) {
        String[] symbol = work.workKey().toLowerCase(Locale.ROOT).split("-", 2);
        boolean evaluation = work.jobType().equals("EVALUATE_SIGNALS");
        if (!List.of("SYNC_STOCK_PRICE", "SYNC_INDICATORS", "SYNC_SIGNALS", "EVALUATE_SIGNALS")
                .contains(work.jobType()))
            return List.of(); // Other protocols retain manifest authority until separately adapted.
        if (!evaluation && symbol.length != 2)
            throw new IllegalArgumentException("Invalid symbol work key");
        Map<String, String> partition = new LinkedHashMap<>();
        partition.put("exchange", symbol[0]);
        String dataset;
        if (work.jobType().equals("SYNC_SIGNALS") || evaluation) {
            dataset = "signals";
            partition.put("strategy", parameter(work, "strategy"));
            partition.put("timeframe", parameter(work, "timeframe"));
        } else {
            partition.put("code", symbol[1]);
            dataset = work.jobType().equals("SYNC_STOCK_PRICE") ? "eod" : "indicators";
            if (dataset.equals("indicators")) {
                partition.put("source", parameter(work, "indicatorSource"));
                partition.put("timeframe", parameter(work, "timeframe"));
            }
        }
        return List.of(new OutputRegistrationEvent.LogicalOutput(dataset, Map.copyOf(partition)));
    }

    public Map<String, Object> metadata(
            UUID attempt, List<OutputRegistrationEvent.LogicalOutput> required) {
        if (required.isEmpty()) return Map.of();
        Map<String, Object> contract =
                new LinkedHashMap<>(
                        Map.of(
                                "atomicAttemptId",
                                attempt.toString(),
                                "eventId",
                                UUID.randomUUID().toString(),
                                "registrationId",
                                UUID.randomUUID().toString(),
                                "requiredOutputs",
                                required));
        if (versionedStorage) contract.put("storagePolicy", "VERSIONED");
        contract.put("protocol", "WORKER_COMPLETION_V1");
        return Map.of("outputCompletion", contract);
    }

    private static String parameter(ExpandedJobMessage work, String key) {
        Object value = work.parameters().get(key);
        if (!(value instanceof String text) || text.isBlank())
            throw new IllegalArgumentException("Missing output parameter: " + key);
        return text.toLowerCase(Locale.ROOT);
    }
}
