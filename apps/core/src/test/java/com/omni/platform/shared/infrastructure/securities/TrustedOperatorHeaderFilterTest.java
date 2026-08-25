package com.omni.platform.shared.infrastructure.securities;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

class TrustedOperatorHeaderFilterTest {
    private final TrustedOperatorHeaderFilter filter = new TrustedOperatorHeaderFilter();

    @Test
    void rejectsMissingOperatorForJobOperations() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/jobs/definitions");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            throw new AssertionError("filter chain must not run");
        });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("Operator identity required");
    }

    @Test
    void propagatesTrimmedTrustedOperatorAsPrincipalAndClearsContext() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/jobs/definitions/id/triggers");
        request.addHeader(TrustedOperatorHeaderFilter.OPERATOR_HEADER, " alice@example.com ");
        var response = new MockHttpServletResponse();
        AtomicReference<Authentication> observed = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                observed.set(SecurityContextHolder.getContext().getAuthentication()));

        assertThat(observed.get().getName()).isEqualTo("alice@example.com");
        assertThat(observed.get().getAuthorities()).extracting("authority").containsExactly("ROLE_OPERATOR");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void leavesNonJobEndpointsUntouched() throws Exception {
        var request = new MockHttpServletRequest("GET", "/actuator/health");
        var response = new MockHttpServletResponse();
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> invoked.set(true));

        assertThat(invoked.get()).isTrue();
    }
}
