package com.omni.platform.shared.entities;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorProvider")
public class JpaAuditConfig {
    private static final String DEFAULT_SYSTEM_OPERATOR_UUID =
            "b252fe62-80f3-4df9-9734-5dc549705a25";

    @Bean
    public AuditorAware<UUID> auditorProvider(
            @Value("${SYSTEM_OPERATOR_UUID:" + DEFAULT_SYSTEM_OPERATOR_UUID + "}")
                    String systemOperatorUuid) {
        UUID systemOperator = UUID.fromString(systemOperatorUuid);
        return () -> Optional.ofNullable(SecurityContextHolder.getContext())
                .map(SecurityContext::getAuthentication)
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getPrincipal)
                .map(Object::toString)
                .map(UUID::fromString)
                .or(() -> Optional.of(systemOperator));
    }
}
