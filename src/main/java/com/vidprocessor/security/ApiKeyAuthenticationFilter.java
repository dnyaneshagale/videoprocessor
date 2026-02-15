package com.vidprocessor.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;

@Component
@Slf4j
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    @Value("${api.key}")
    private String validApiKey;

    @PostConstruct
    void validateConfig() {
        if (validApiKey == null || validApiKey.isBlank()
                || validApiKey.equals("${API_KEY}")
                || validApiKey.contains("change-this")) {
            throw new IllegalStateException(
                    "API_KEY environment variable is not set. "
                    + "Set it before starting the application: export API_KEY=<your-secret-key>");
        }
        if (validApiKey.length() < 32) {
            log.warn("API key is shorter than 32 characters — consider using a stronger key for production");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestPath = request.getRequestURI();

        // Skip authentication for health check endpoints
        if (requestPath.equals("/api/health") || requestPath.equals("/api/ping")) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader("X-API-Key");

        if (apiKey != null && constantTimeEquals(apiKey, validApiKey)) {
            ApiKeyAuthenticationToken authentication = new ApiKeyAuthenticationToken();
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Constant-time comparison to prevent timing attacks on the API key.
     */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(), b.getBytes());
    }

    /**
     * Simple authentication token for API key based authentication.
     * Does not store the API key itself to avoid accidental exposure in logs/serialization.
     */
    private static class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

        public ApiKeyAuthenticationToken() {
            super(List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
            setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "[PROTECTED]";
        }

        @Override
        public Object getPrincipal() {
            return "api-client";
        }
    }
}
