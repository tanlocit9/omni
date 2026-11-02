package com.omni.platform.shared.infrastructure.securities;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.test.util.ReflectionTestUtils;

class OutputReadinessTokenTest {
    @ParameterizedTest
    @CsvSource({
        "GET,/api/v1/jobs/output-readiness,read,200",
        "POST,/api/v1/jobs/output-readiness/backfill,read,401",
        "POST,/api/v1/jobs/output-readiness/backfill,reconcile,200",
        "POST,/api/v1/jobs/output-readiness/backfill/fence,reconcile,200",
        "GET,/api/v1/jobs/output-readiness,reconcile,401",
        "GET,/api/v1/jobs/other,read,401"
    })
    void machineTokensHaveNarrowMethodAndRouteScopes(String method,String route,String token,int status) throws Exception {
        var filter=new TrustedOperatorHeaderFilter();
        ReflectionTestUtils.setField(filter,"readerToken","read");
        ReflectionTestUtils.setField(filter,"reconcileToken","reconcile");
        var request=new MockHttpServletRequest(method,route);
        request.addHeader("Authorization","Bearer "+token);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
