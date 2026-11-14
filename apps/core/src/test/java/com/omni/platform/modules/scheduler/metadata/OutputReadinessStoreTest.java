package com.omni.platform.modules.scheduler.metadata;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class OutputReadinessStoreTest {
    @Test
    void globalPromotionCannotBeEnabledWithoutEnrollment() {
        assertThatThrownBy(() -> new OutputReadinessStore(mock(JdbcTemplate.class), true, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("enrollment");
    }

    @Test
    void disabledGlobalPromotionOnlyChecksRegisteredBackfillMembership() {
        var jdbc = mock(JdbcTemplate.class);
        UUID execution = UUID.randomUUID();
        when(jdbc.queryForObject(contains("r.mode='BACKFILL'"), eq(Boolean.class), eq(execution))).thenReturn(false);
        new OutputReadinessStore(jdbc, false, false).promote(execution);
        verify(jdbc).queryForObject(contains("r.mode='BACKFILL'"), eq(Boolean.class), eq(execution));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void backfillMembershipAloneDoesNotPromoteWithoutCommittedCompletion() {
        var jdbc = mock(JdbcTemplate.class);
        UUID execution = UUID.randomUUID();
        when(jdbc.queryForObject(contains("r.mode='BACKFILL'"), eq(Boolean.class), eq(execution))).thenReturn(true);
        when(jdbc.queryForList(anyString(), eq(execution))).thenReturn(List.of());
        new OutputReadinessStore(jdbc, false, false).promote(execution);
        verify(jdbc).queryForObject(contains("r.mode='BACKFILL'"), eq(Boolean.class), eq(execution));
        verify(jdbc).queryForList(anyString(), eq(execution));
        verifyNoMoreInteractions(jdbc);
    }
}
