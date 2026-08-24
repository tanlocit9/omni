package com.omni.platform.modules.scheduler.config;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.omni.platform.modules.scheduler.entities.JobDefinition;

/**
 * Secure-by-default allow-list for operator initiated jobs.
 *
 * <p>Entries are either a definition UUID or the stable
 * {@code JOB_TYPE:SOURCE} identity. An empty list disables manual triggering.
 */
@ConfigurationProperties(prefix = "app.scheduler.manual-trigger")
public record ManualTriggerProperties(List<String> allowList) {

    public ManualTriggerProperties {
        allowList = allowList == null
                ? List.of()
                : allowList.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(value -> value.trim().toUpperCase(Locale.ROOT))
                        .distinct()
                        .toList();
    }

    public boolean allows(JobDefinition definition) {
        UUID id = definition.getId();
        String stableKey = definition.getJobType().name() + ":" + definition.getSource().name();
        return allowList.contains(stableKey)
                || (id != null && allowList.contains(id.toString().toUpperCase(Locale.ROOT)));
    }
}
