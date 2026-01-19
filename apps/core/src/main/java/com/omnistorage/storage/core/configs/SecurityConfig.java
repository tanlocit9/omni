package com.omnistorage.storage.core.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Quan trọng: Disable CSRF để test upload qua Postman
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/storage/**").permitAll() // Cho phép truy cập storage
                        .anyRequest().authenticated()
                );
        return http.build();
    }
}