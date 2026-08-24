package com.omni.platform.shared.infrastructure.securities;

import java.io.IOException;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Establishes the private Console operator identity supplied by the trusted
 * reverse proxy. Deployments must remove client-provided X-Omni-User values and
 * inject the authenticated operator value before forwarding to Platform.
 */
@Component
public class TrustedOperatorHeaderFilter extends OncePerRequestFilter {
    public static final String OPERATOR_HEADER = "X-Omni-User";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/jobs");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String actor = request.getHeader(OPERATOR_HEADER);
        if (actor == null || actor.isBlank() || actor.trim().length() > 200) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(
                    "{\"title\":\"Operator identity required\",\"status\":401,"
                            + "\"detail\":\"A trusted X-Omni-User header is required\"}");
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(
                actor.trim(), null, List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
