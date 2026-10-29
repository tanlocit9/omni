package com.omni.platform.modules.scheduler.planning;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.omni.platform.modules.scheduler.repositories.SchedulerBacklogGroup;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.StaticJobTopology;
import com.omni.platform.modules.scheduler.topology.StaticJobTopologyProvider;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

@Service
public class BacklogSnapshotService {

    public static final int MAX_GROUPS = 10_000;

    private final SchedulerOutboxRepository repository;
    private final StaticJobTopology topology;

    public BacklogSnapshotService(
            SchedulerOutboxRepository repository, StaticJobTopologyProvider topologyProvider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.topology = Objects.requireNonNull(topologyProvider, "topologyProvider").topology();
    }

    @Transactional(readOnly = true)
    public BacklogSnapshot capture(Instant snapshotAt, int groupLimit) {
        Objects.requireNonNull(snapshotAt, "snapshotAt");
        if (groupLimit <= 0 || groupLimit > MAX_GROUPS) {
            throw new IllegalArgumentException("groupLimit must be between 1 and " + MAX_GROUPS);
        }
        List<SchedulerBacklogGroup> groups = repository.findBacklogGroups(snapshotAt, groupLimit);
        boolean complete = groups.size() <= groupLimit;
        Map<DispatchScope, BacklogCounts> entries = new LinkedHashMap<>();
        groups.stream().limit(groupLimit).forEach(group -> add(entries, group));
        return new BacklogSnapshot(snapshotAt, complete, groupLimit, entries);
    }

    private void add(Map<DispatchScope, BacklogCounts> entries, SchedulerBacklogGroup group) {
        JobDefinitionIdentity identity =
                new JobDefinitionIdentity(group.source(), group.jobType(), group.cronExpression());
        TopologyNodeKey node = topology.nodeFor(identity);
        DispatchScope scope = new DispatchScope(node, group.jobDefinitionId(), identity);
        BacklogCounts counts =
                new BacklogCounts(
                        group.pending(),
                        group.dispatchable(),
                        group.retryDelayed(),
                        group.inFlight());
        if (entries.putIfAbsent(scope, counts) != null) {
            throw new IllegalStateException("Duplicate backlog scope: " + scope);
        }
    }
}
