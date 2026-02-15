package com.vidprocessor.config;

import com.vidprocessor.security.ApiKeyAuthenticationFilter;
import com.vidprocessor.security.RateLimitFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(ApiKeyAuthenticationFilter apiKeyAuthenticationFilter, RateLimitFilter rateLimitFilter) {
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentTypeOptions(opts -> {})                  // X-Content-Type-Options: nosniff
                        .frameOptions(frame -> frame.deny())             // X-Frame-Options: DENY
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true))                // Strict-Transport-Security
                        .cacheControl(cache -> {})                       // Cache-Control: no-cache
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health", "/api/ping").permitAll()
                        .requestMatchers("/api/videos/**").authenticated()
                        .anyRequest().denyAll()
                )
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiKeyAuthenticationFilter, RateLimitFilter.class)
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .anonymous(anon -> anon.disable());

        return http.build();
    }
}