package com.omni.platform.modules.scheduler.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ManualTriggerRequest;
import com.omni.platform.modules.scheduler.dtos.JobOperationsDtos.ManualTriggerResponse;
import com.omni.platform.modules.scheduler.services.JobOperationsCatalogService;
import com.omni.platform.modules.scheduler.services.ManualJobTriggerService;

@ExtendWith(MockitoExtension.class)
class JobOperationsControllerTest {
    @Mock private JobOperationsCatalogService catalog;
    @Mock private ManualJobTriggerService triggers;

    @Test
    void triggerPropagatesPrincipalAndMapsAcceptedAndBlockedTo202() {
        UUID definitionId = UUID.randomUUID();
        ManualTriggerRequest request = new ManualTriggerRequest("request-1", "reason", Map.of());
        ManualTriggerResponse accepted = response(definitionId, "ACCEPTED");
        when(triggers.trigger(definitionId, "alice", request)).thenReturn(accepted);
        var controller = new JobOperationsController(catalog, triggers);

        var result = controller.trigger(definitionId, request, principal("alice"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(result.getBody()).isSameAs(accepted);
        verify(triggers).trigger(definitionId, "alice", request);
    }

    @Test
    void triggerMapsConcurrencyOrPolicyConflictTo409() {
        UUID definitionId = UUID.randomUUID();
        ManualTriggerRequest request = new ManualTriggerRequest("request-2", "reason", Map.of());
        when(triggers.trigger(definitionId, "alice", request)).thenReturn(response(definitionId, "CONFLICT"));
        var controller = new JobOperationsController(catalog, triggers);

        var result = controller.trigger(definitionId, request, principal("alice"));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void statusLookupIsScopedToPrincipal() {
        UUID requestId = UUID.randomUUID();
        var controller = new JobOperationsController(catalog, triggers);

        controller.triggerStatus(requestId, principal("bob"));

        verify(triggers).triggerStatus(requestId, "bob");
    }

    private static Principal principal(String name) {
        return () -> name;
    }

    private static ManualTriggerResponse response(UUID definitionId, String state) {
        return new ManualTriggerResponse(UUID.randomUUID(), definitionId, UUID.randomUUID(), state, false,
                null, null, Instant.now(), Instant.now());
    }
}
