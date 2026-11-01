package com.omni.platform.modules.scheduler.work;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class DefinitionConsolidationStartupTest {
    @Test
    void defaultDisabledDoesNotChangeAnySchedules() {
        var service = mock(DefinitionConsolidationService.class);
        new DefinitionConsolidationStartup(service, false).apply();
        verifyNoInteractions(service);
    }

    @Test
    void explicitStartupOptInUsesTheSameGuardedConsolidationService() {
        var service = mock(DefinitionConsolidationService.class);
        new DefinitionConsolidationStartup(service, true).apply();
        verify(service).consolidate(any(Instant.class));
    }

    @Test
    void startupConflictPropagatesRatherThanPretendingConsolidationSucceeded() {
        var service = mock(DefinitionConsolidationService.class);
        doThrow(new IllegalStateException("active lease"))
                .when(service)
                .consolidate(any(Instant.class));
        assertThatThrownBy(() -> new DefinitionConsolidationStartup(service, true).apply())
                .hasMessageContaining("active lease");
    }
}
