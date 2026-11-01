package com.omni.platform.modules.scheduler.work;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.omni.platform.modules.scheduler.repositories.SchedulerClaim;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Mode;
import com.omni.platform.modules.scheduler.runs.JobRunSnapshot.Request;
import org.junit.jupiter.api.Test;

class GraphWorkRequestPolicyTest {
    private GraphWorkRequestService service(
            boolean enabled, boolean preparation, boolean planning) {
        return new GraphWorkRequestService(
                null, null, null, null, null, null, null, enabled, preparation, planning);
    }

    @Test
    void startupRejectsGraphWithoutBothPreparationAndGuardedPlanning() {
        assertThatThrownBy(() -> service(true, false, true))
                .hasMessageContaining("requires preparation");
        assertThatThrownBy(() -> service(true, true, false))
                .hasMessageContaining("requires preparation");
        assertThatNoException().isThrownBy(() -> service(false, false, false));
    }

    @Test
    void disabledGraphDoesNotPrepareOrReconcileDurableWork() {
        var disabled = service(false, false, false);
        assertThat(disabled.isEnabled()).isFalse();
        assertThatNoException().isThrownBy(disabled::reconcile);
        assertThatThrownBy(() -> disabled.prepare(null, null, null, Instant.EPOCH, Map.of()))
                .hasMessageContaining("GRAPH_EXPANSION_DISABLED");
    }

    @Test
    void wrongTargetAndBackfillAreRejectedBeforeAnyDatabaseWork() {
        var enabled = service(true, true, true);
        var target = UUID.randomUUID();
        var lease =
                new SchedulerClaim(
                        target, UUID.randomUUID(), "owner", Instant.EPOCH, Instant.MAX, null);
        var manual = new Request(Mode.MANUAL, "owner", "key", "test", null, "{}");
        var backfill = new Request(Mode.BACKFILL, "owner", "key", "test", null, "{}");
        assertThatThrownBy(
                        () ->
                                enabled.prepare(
                                        manual, UUID.randomUUID(), lease, Instant.EPOCH, Map.of()))
                .hasMessageContaining("claim/mode");
        assertThatThrownBy(() -> enabled.prepare(backfill, target, lease, Instant.EPOCH, Map.of()))
                .hasMessageContaining("claim/mode");
    }
}
