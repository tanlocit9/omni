package com.omni.platform.modules.scheduler.work;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Explicit opt-in operation after normal seeding; disabled in existing deployments. */
@Component
public class DefinitionConsolidationStartup {
    private final DefinitionConsolidationService consolidation;
    private final boolean enabled;
    public DefinitionConsolidationStartup(DefinitionConsolidationService consolidation,
            @Value("${app.scheduler.job-run.definition-consolidation-enabled:false}") boolean enabled) {
        this.consolidation=consolidation; this.enabled=enabled;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void apply() {
        if (enabled) consolidation.consolidate(Instant.now());
    }
}
