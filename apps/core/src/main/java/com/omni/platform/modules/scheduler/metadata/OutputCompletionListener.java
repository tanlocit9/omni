package com.omni.platform.modules.scheduler.metadata;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import com.omni.platform.modules.scheduler.services.JobService;

@Component
public class OutputCompletionListener {
    private final OutputCompletionGate gate;
    private final JobService jobs;

    public OutputCompletionListener(OutputCompletionGate gate, JobService jobs) {
        this.gate = gate;
        this.jobs = jobs;
    }

    @EventListener
    public void registered(OutputRegistrationAccepted event) {
        var status = gate.pending(event.executionId());
        if (status != null) jobs.applyStatus(status);
    }
}
