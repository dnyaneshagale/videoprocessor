package com.vidprocessor.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.stereotype.Component;

@Component
public class UserDetailsServiceProvider {

    @Value("${vidprocessor.security.processor-password:changeme}")
    private String processorPassword;

    @Value("${vidprocessor.security.admin-password:adminchangeme}")
    private String adminPassword;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        UserDetails processorUser = User.builder()
                .username("processor")
                .password(passwordEncoder.encode(processorPassword))
                .roles("VIDEO_PROCESSOR")
                .build();

        UserDetails adminUser = User.builder()
                .username("admin")
                .password(passwordEncoder.encode(adminPassword))
                .roles("VIDEO_PROCESSOR", "ADMIN")
                .build();

        return new InMemoryUserDetailsManager(processorUser, adminUser);
    }
}