package com.omni.platform.modules.scheduler.topology;

import org.springframework.stereotype.Component;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;

/** Owns the single active static job-topology declaration boundary. */
@Component
public final class StaticJobTopologyProvider {

    private final StaticDefinitionDependencies declaration;

    public StaticJobTopologyProvider() {
        declaration =
                StaticDefinitionDependencies.canonical(JobDefinitionConfig.JOB_DEFINITION_SEEDS);
    }

    public StaticJobTopology topology() {
        return declaration.topology();
    }

    public StaticDefinitionDependencies definitionDependencies() {
        return declaration;
    }
}
