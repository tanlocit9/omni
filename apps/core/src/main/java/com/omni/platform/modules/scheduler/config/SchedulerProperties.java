package com.omni.platform.modules.scheduler.config;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "app.scheduler")
public record SchedulerProperties(
        @NotBlank String instanceId,
        @Valid @NotNull Claim claim) {

    public static final int MAX_CLAIM_BATCH_SIZE = 100;

    public SchedulerProperties {
        if (instanceId == null || instanceId.isBlank()) {
            instanceId = defaultInstanceId();
        }
        if (claim == null) {
            claim = new Claim(Duration.ofMinutes(2), 10);
        }
    }

    public record Claim(
            @NotNull Duration leaseDuration,
            @Min(1) @Max(MAX_CLAIM_BATCH_SIZE) int batchSize) {

        @AssertTrue(message = "leaseDuration must be positive and longer than the expected claim-to-prepare transaction")
        public boolean isLeaseDurationValid() {
            return leaseDuration != null && leaseDuration.compareTo(Duration.ofSeconds(1)) > 0;
        }
    }

    private static String defaultInstanceId() {
        return "platform-scheduler-" + localHostName() + "-" + ManagementFactory.getRuntimeMXBean().getName() + "-"
                + UUID.randomUUID();
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            return "unknown-host";
        }
    }
}
