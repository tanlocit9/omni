package com.omni.platform.modules.scheduler.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import com.omni.platform.modules.scheduler.services.JobOperationException;
import com.omni.platform.modules.scheduler.services.JobOperationsCatalogService;

@ExtendWith(MockitoExtension.class)
class JobOperationsControllerTest {
    @Mock private JobOperationsCatalogService catalog;

    @Test
    void retiredTriggerAndAuditRoutesFailExplicitlyWithoutStorage() {
        var controller = new JobOperationsController(catalog);
        for (Runnable call :
                new Runnable[] {
                    () -> controller.trigger(UUID.randomUUID()),
                    () -> controller.triggerStatus(UUID.randomUUID())
                }) {
            assertThatThrownBy(call::run)
                    .isInstanceOfSatisfying(
                            JobOperationException.class,
                            error -> {
                                assertThat(error.getStatus()).isEqualTo(HttpStatus.GONE);
                                assertThat(error.getCode()).isEqualTo("manual_trigger_deferred");
                            });
        }
        verifyNoInteractions(catalog);
    }

    @Test
    void readOnlyCatalogDetailAndExecutionRoutesRemainAvailable() {
        var controller = new JobOperationsController(catalog);
        UUID id = UUID.randomUUID();
        controller.definitions("vci", "SYNC_SYMBOLS", true, 0, 25);
        controller.definition(id);
        controller.execution(id);
        verify(catalog).list("vci", "SYNC_SYMBOLS", true, 0, 25);
        verify(catalog).detail(id);
        verify(catalog).executionStatus(id);
    }
}
