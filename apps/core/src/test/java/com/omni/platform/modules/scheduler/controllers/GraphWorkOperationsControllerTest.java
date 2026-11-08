package com.omni.platform.modules.scheduler.controllers;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import com.omni.platform.modules.scheduler.services.JobOperationException;
import com.omni.platform.modules.scheduler.work.GraphWorkRequestQueryService;
import com.omni.platform.shared.infrastructure.securities.TrustedOperatorHeaderFilter;
import tools.jackson.databind.json.JsonMapper;

class GraphWorkOperationsControllerTest {
    @Test
    void forwardsBoundedPollingParametersWithoutLoadingFullGraph() {
        var query = mock(GraphWorkRequestQueryService.class);
        var api = new GraphWorkOperationsController(null, null, null, null, query);
        Instant observed = Instant.parse("2026-11-01T00:00:00Z");
        UUID before = UUID.randomUUID();
        var page = new GraphWorkRequestQueryService.Page(observed, List.of(), false, null);
        when(query.list("all", 20, observed, observed, before)).thenReturn(page);
        assertThat(api.requests("all", 20, observed, observed, before)).isSameAs(page);
        verify(query).list("all", 20, observed, observed, before);
        verifyNoMoreInteractions(query);
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "zero", "large", "future", "idOnly", "dateOnly", "cursorFuture"})
    void rejectsInvalidPollingQueriesBeforeDatabaseAccess(String invalid) {
        var source = mock(DataSource.class);
        var service = new GraphWorkRequestQueryService(source, JsonMapper.builder().build());
        Instant past = Instant.parse("2026-11-01T00:00:00Z");
        assertThatThrownBy(() -> service.list(invalid.equals("status") ? "garbage" : "active",
                invalid.equals("zero") ? 0 : invalid.equals("large") ? 101 : 20,
                invalid.equals("future") ? Instant.now().plusSeconds(60) : past,
                invalid.equals("dateOnly") ? past : invalid.equals("cursorFuture") ? past.plusSeconds(1) : null,
                invalid.equals("idOnly") || invalid.equals("cursorFuture") ? UUID.randomUUID() : null))
                .isInstanceOfSatisfying(JobOperationException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(400));
        verifyNoInteractions(source);
    }

    @Test
    void unavailableDatabaseIsReportedAsUnavailableRatherThanEmpty() {
        var source = mock(DataSource.class);
        try {
            when(source.getConnection()).thenThrow(new java.sql.SQLException("unavailable"));
        } catch (java.sql.SQLException impossible) {
            throw new AssertionError(impossible);
        }
        var service = new GraphWorkRequestQueryService(source, JsonMapper.builder().build());
        assertThatThrownBy(() -> service.list(null, 100, null, null, null))
                .isInstanceOfSatisfying(JobOperationException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(503);
                    assertThat(error.getCode()).isEqualTo("ATOMIC_REQUESTS_UNAVAILABLE");
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pollingUsesExistingTrustedOperatorBoundary(boolean authenticated) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/jobs/atomic-work/requests");
        if (authenticated) request.addHeader("X-Omni-User", "operator");
        var response = new MockHttpServletResponse();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        new TrustedOperatorHeaderFilter().doFilter(request, response, (req, res) -> calls.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(authenticated ? 200 : 401);
        assertThat(calls.get()).isEqualTo(authenticated ? 1 : 0);
    }
}
