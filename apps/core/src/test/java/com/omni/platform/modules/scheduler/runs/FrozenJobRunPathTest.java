package com.omni.platform.modules.scheduler.runs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.omni.platform.modules.scheduler.entities.JobDefinition.DataSource;
import com.omni.platform.modules.scheduler.entities.JobDefinition.JobType;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.State;
import com.omni.platform.modules.scheduler.topology.JobDefinitionIdentity;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.RequiredPath;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Scope;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.ScopeType;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.Step;
import com.omni.platform.modules.scheduler.topology.RequiredPathResolver.StepKey;
import com.omni.platform.modules.scheduler.topology.TopologyNodeKey;

class FrozenJobRunPathTest {
    static final LocalDate DATE = LocalDate.of(2026, 10, 18);

    @Test
    void freezesJoinMembershipAndCanonicalFingerprintWithoutSharingMutableLists() {
        var a = key("a");
        var b = key("b");
        var target = key("target");
        var first = path(step(a), step(b), step(target, b, a));
        var frozen = FrozenJobRunPath.freeze(first);
        assertThat(frozen.predecessors()).containsExactly(List.of(), List.of(), List.of(0, 1));
        assertThat(frozen.fingerprint())
                .hasSize(64)
                .isEqualTo(
                        FrozenJobRunPath.freeze(path(step(a), step(b), step(target, a, b)))
                                .fingerprint());
        assertThatThrownBy(() -> frozen.predecessors().getLast().add(3))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new FrozenJobRunPath(first, frozen.predecessors(), "wrong"))
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> new FrozenJobRunPath(first, List.of(), frozen.fingerprint()))
                .hasMessageContaining("does not match");
        assertThat(frozen.fingerprint())
                .isNotEqualTo(FrozenJobRunPath.freeze(path(step(key("other")))).fingerprint());
    }

    @Test
    void rejectsMalformedOrDisconnectedResolvedPathMembership() {
        var a = key("a");
        var b = key("b");
        for (RequiredPath malformed :
                List.of(
                        new RequiredPath(1, a, List.of(step(a), step(b, a))),
                        path(step(a), step(a)),
                        path(step(b, a), step(a)),
                        path(step(a), step(b, a, a)),
                        path(step(a), step(b)),
                        path(new Step(a, new TopologyNodeKey("wrong-node"), List.of())),
                        path(
                                step(
                                        new StepKey(
                                                a.definition(),
                                                new Scope(
                                                        ScopeType.GLOBAL,
                                                        "VN",
                                                        DATE.minusDays(1)))),
                                step(b, a)),
                        path(step(a, a)))) {
            assertThatThrownBy(() -> FrozenJobRunPath.freeze(malformed))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void enforcesAnExplicitEdgeBound() {
        List<StepKey> keys = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < 130; i++) {
            var current = key("step-" + i);
            steps.add(
                    new Step(
                            current,
                            TopologyNodeKey.forJobType(JobType.SYNC_SYMBOLS),
                            List.copyOf(keys)));
            keys.add(current);
        }
        assertThatThrownBy(
                        () -> FrozenJobRunPath.freeze(new RequiredPath(1, keys.getLast(), steps)))
                .hasMessageContaining("edge bound");
    }

    @Test
    void requestFieldsAndPersistedDefinitionMembershipAreBounded() {
        var request = new Request(Mode.MANUAL, "operator", "request:1", "research", null, "{}");
        assertThat(request.businessGroup()).isNull();
        assertThat(
                        new Request(
                                        Mode.SCHEDULED,
                                        "scheduler",
                                        "request-2",
                                        "scheduled",
                                        "BANKS",
                                        "{}")
                                .businessGroup())
                .isEqualTo("BANKS");
        for (String invalid : new String[] {null, "", " padded", "x".repeat(201)}) {
            assertThatThrownBy(() -> new Request(Mode.MANUAL, invalid, "key", "reason", null, "{}"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(
                        () -> new Request(Mode.MANUAL, "operator", "bad/key", "reason", null, "{}"))
                .hasMessageContaining("idempotency");
        var frozen = FrozenJobRunPath.freeze(path(step(key("root"))));
        var snapshot =
                new JobRunSnapshot(
                        UUID.randomUUID(),
                        request,
                        State.PREPARED,
                        null,
                        Instant.now(),
                        frozen,
                        List.of(UUID.randomUUID()));
        assertThat(snapshot.definitionIds()).hasSize(1);
        assertThatThrownBy(() -> snapshot.definitionIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(
                        () ->
                                new JobRunSnapshot(
                                        UUID.randomUUID(),
                                        request,
                                        State.PREPARED,
                                        null,
                                        Instant.now(),
                                        frozen,
                                        List.of()))
                .hasMessageContaining("persisted definition");
    }

    static StepKey key(String cron) {
        return new StepKey(
                new JobDefinitionIdentity(DataSource.VND, JobType.SYNC_SYMBOLS, cron),
                new Scope(ScopeType.GLOBAL, "VN", DATE));
    }

    static Step step(StepKey key, StepKey... upstream) {
        return new Step(
                key, TopologyNodeKey.forJobType(key.definition().jobType()), List.of(upstream));
    }

    static RequiredPath path(Step... steps) {
        return new RequiredPath(1, steps[steps.length - 1].key(), List.of(steps));
    }
}
