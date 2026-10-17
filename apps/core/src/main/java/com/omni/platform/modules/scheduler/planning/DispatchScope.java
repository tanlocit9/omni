package com.omni.platform.modules.scheduler.planning;

import java.util.Objects;
import java.util.UUID;

import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

/** Explicit scheduler scope for one persisted job definition. */
public record DispatchScope(
        TopologyNodeKey node,
        UUID jobDefinitionId,
        JobDefinitionIdentity definitionIdentity) implements Comparable<DispatchScope> {

    public DispatchScope {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(jobDefinitionId, "jobDefinitionId");
        Objects.requireNonNull(definitionIdentity, "definitionIdentity");
    }

    @Override
    public int compareTo(DispatchScope other) {
        int nodeOrder = node.compareTo(other.node);
        if (nodeOrder != 0) {
            return nodeOrder;
        }
        int identityOrder = definitionIdentity.compareTo(other.definitionIdentity);
        return identityOrder != 0
                ? identityOrder
                : jobDefinitionId.compareTo(other.jobDefinitionId);
    }
}
