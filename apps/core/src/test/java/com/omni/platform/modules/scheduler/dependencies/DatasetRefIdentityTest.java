package com.omni.platform.modules.scheduler.dependencies;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DatasetRefIdentityTest {
    private final String work = "work:" + "a".repeat(64);
    private final LocalDate day = LocalDate.of(2026, 11, 3);
    private final UUID attempt = UUID.randomUUID();

    @Test
    void malformedCoordinatesOrIncompleteWorkCannotBecomeAnApproval() {
        assertThatThrownBy(() -> DatasetRef.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatasetRef.of(" ")).isInstanceOf(IllegalArgumentException.class);
        var ref = DatasetRef.of("eod");
        assertThatThrownBy(() -> ref.bindWork(null, day, attempt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ref.bindWork("work:bad", day, attempt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ref.bindWork(work, null, attempt))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ref.bindWork(work, day, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsKeepDistinctAttemptsDatesAndWorkIdentitiesSharingOnePartition() {
        var ref = DatasetRef.of("eod", Map.of("code", "hpg"));
        var bound = ref.bindWork(work, day, attempt);
        var same = ref.bindWork(work, day, attempt);
        assertThat(bound).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(bound.equals(bound)).isTrue();
        assertThat(bound.equals(null)).isFalse();
        assertThat(bound.equals("eod")).isFalse();
        var otherDay = ref.bindWork(work, day.plusDays(1), attempt);
        var otherAttempt = ref.bindWork(work, day, UUID.randomUUID());
        var otherWork = ref.bindWork("work:" + "b".repeat(64), day, attempt);
        assertThat(Set.of(ref, bound, otherDay, otherAttempt, otherWork)).hasSize(5);
        assertThat(bound)
                .isNotEqualTo(
                        DatasetRef.of("signals", ref.getPartition()).bindWork(work, day, attempt))
                .isNotEqualTo(
                        DatasetRef.of("eod", Map.of("code", "fpt")).bindWork(work, day, attempt));
        var map = new HashMap<DatasetRef, String>();
        map.put(bound, "original");
        map.put(otherDay, "next-day");
        assertThat(map.get(same)).isEqualTo("original");
    }

    @Test
    void retainedPartitionRefsAndApprovalWireStayCompatible() {
        assertThat(DatasetRef.of("eod", null)).isEqualTo(DatasetRef.of("eod"));
        assertThat(DatasetRef.of("eod").toString()).isEqualTo("eod");
        var ref = DatasetRef.of("eod", Map.of("code", "hpg"));
        assertThat(ref.toString()).contains("eod", "hpg");
        assertThat(ref.approvedInput("v1")).containsOnlyKeys("dataset", "partition", "dataVersion");
        assertThat(ref.bindWork(work, day, attempt).approvedInput("v1"))
                .containsEntry("atomicWorkId", work)
                .containsEntry("businessDate", day.toString())
                .containsEntry("atomicAttemptId", attempt.toString());
    }

    @Test
    void acceptedCoordinatesCannotBeChangedThroughTheCallersMap() {
        var coordinates = new HashMap<String, String>();
        coordinates.put("code", "hpg");
        var ref = DatasetRef.of("eod", coordinates).bindWork(work, day, attempt);
        coordinates.put("code", "fpt");
        assertThat(ref.getPartition()).containsEntry("code", "hpg");
        assertThatThrownBy(() -> ref.getPartition().put("code", "fpt"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
