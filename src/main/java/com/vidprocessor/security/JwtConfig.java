package com.vidprocessor.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtConfig {
    private String secret;
    private long expirationMs;
    private String tokenPrefix = "Bearer ";
    private String headerName = "Authorization";
}