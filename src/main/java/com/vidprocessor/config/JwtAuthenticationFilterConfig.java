package com.vidprocessor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.UserDetailsService;

import com.vidprocessor.security.JwtAuthenticationFilter;
import com.vidprocessor.security.JwtConfig;
import com.vidprocessor.security.JwtTokenService;

@Configuration
public class JwtAuthenticationFilterConfig {

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenService jwtTokenService,
            UserDetailsService userDetailsService,
            JwtConfig jwtConfig) {

        return new JwtAuthenticationFilter(
                jwtTokenService,
                userDetailsService,
                jwtConfig
        );
    }
}