package com.omni.platform.modules.scheduler.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig;
import com.omni.platform.modules.scheduler.constants.JobDefinitionConfig.JobDefinitionSeed;
import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.repositories.SchedulerBacklogGroup;
import com.omni.platform.modules.scheduler.repositories.SchedulerOutboxRepository;
import com.omni.platform.modules.scheduler.topology.StaticJobTopologyProvider;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

class BacklogSnapshotServiceTest {

    private static final Instant SNAPSHOT_AT = Instant.parse("2026-10-11T00:00:00Z");

    private final SchedulerOutboxRepository repository = mock(SchedulerOutboxRepository.class);
    private final BacklogSnapshotService service = new BacklogSnapshotService(
            repository, new StaticJobTopologyProvider());

    @Test
    void mapsBoundedRepositoryGroupsToExplicitTopologyScopes() {
        JobDefinitionSeed seed = seed(JobType.SYNC_SYMBOLS);
        UUID definitionId = UUID.randomUUID();
        when(repository.findBacklogGroups(SNAPSHOT_AT, 10))
                .thenReturn(List.of(group(definitionId, seed, 6, 3, 2, 1)));

        BacklogSnapshot snapshot = service.capture(SNAPSHOT_AT, 10);

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.entries().entrySet()).singleElement().satisfies(entry -> {
            assertThat(entry.getKey().jobDefinitionId()).isEqualTo(definitionId);
            assertThat(entry.getKey().node())
                    .isEqualTo(TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS));
            assertThat(entry.getValue()).isEqualTo(new BacklogCounts(6, 3, 2, 1));
        });
    }

    @Test
    void marksSnapshotIncompleteAndRetainsOnlyTheConfiguredGroupLimit() {
        when(repository.findBacklogGroups(SNAPSHOT_AT, 1)).thenReturn(List.of(
                group(UUID.randomUUID(), seed(JobType.SYNC_SYMBOLS), 1, 1, 0, 0),
                group(UUID.randomUUID(), seed(JobType.SYNC_METADATA), 1, 1, 0, 0)));

        BacklogSnapshot snapshot = service.capture(SNAPSHOT_AT, 1);

        assertThat(snapshot.complete()).isFalse();
        assertThat(snapshot.entries()).hasSize(1);
    }

    @Test
    void rejectsUnknownPersistedDefinitionsAndInvalidCountPartitions() {
        SchedulerBacklogGroup unknown = new SchedulerBacklogGroup(
                UUID.randomUUID(), DataSource.ANALYZER, JobType.SYNC_SYMBOLS,
                "0 59 23 * * *", 1, 1, 0, 0);
        when(repository.findBacklogGroups(SNAPSHOT_AT, 10)).thenReturn(List.of(unknown));

        assertThatThrownBy(() -> service.capture(SNAPSHOT_AT, 10))
                .hasMessageContaining("Unknown job definition mapping");

        JobDefinitionSeed seed = seed(JobType.SYNC_SYMBOLS);
        when(repository.findBacklogGroups(SNAPSHOT_AT, 10)).thenReturn(List.of(
                group(UUID.randomUUID(), seed, 2, 2, 1, 0)));
        assertThatThrownBy(() -> service.capture(SNAPSHOT_AT, 10))
                .hasMessageContaining("Pending count must equal");
        assertThatThrownBy(() -> service.capture(
                SNAPSHOT_AT, BacklogSnapshotService.MAX_GROUPS + 1))
                .hasMessageContaining("groupLimit");
        assertThatThrownBy(() -> service.capture(null, 10))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsDuplicatePersistedScopes() {
        JobDefinitionSeed seed = seed(JobType.SYNC_SYMBOLS);
        UUID definitionId = UUID.randomUUID();
        SchedulerBacklogGroup duplicate = group(definitionId, seed, 1, 1, 0, 0);
        when(repository.findBacklogGroups(SNAPSHOT_AT, 10))
                .thenReturn(List.of(duplicate, duplicate));

        assertThatThrownBy(() -> service.capture(SNAPSHOT_AT, 10))
                .hasMessageContaining("Duplicate backlog scope");
    }

    private JobDefinitionSeed seed(JobType type) {
        return JobDefinitionConfig.JOB_DEFINITION_SEEDS.stream()
                .filter(seed -> seed.jobType() == type)
                .findFirst()
                .orElseThrow();
    }

    private SchedulerBacklogGroup group(
            UUID definitionId,
            JobDefinitionSeed seed,
            long pending,
            long dispatchable,
            long delayed,
            long inFlight) {
        return new SchedulerBacklogGroup(
                definitionId,
                seed.source(),
                seed.jobType(),
                seed.cronExpr(),
                pending,
                dispatchable,
                delayed,
                inFlight);
    }
}
