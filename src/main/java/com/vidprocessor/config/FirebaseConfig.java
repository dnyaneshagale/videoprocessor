package com.vidprocessor.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Firebase Realtime Database configuration using the REST API.
 * No service account key needed — uses the public REST endpoint.
 */
@Configuration
@Slf4j
public class FirebaseConfig {

    @Value("${firebase.database.url}")
    private String databaseUrl;

    @PostConstruct
    void validateConfig() {
        if (databaseUrl == null || databaseUrl.isBlank() || databaseUrl.startsWith("${")) {
            throw new IllegalStateException(
                    "FIREBASE_DATABASE_URL environment variable is not set. "
                    + "Set it before starting the application: export FIREBASE_DATABASE_URL=https://your-project.firebaseio.com");
        }
        // Strip trailing slash for consistent URL building
        databaseUrl = databaseUrl.replaceAll("/+$", "");
        log.info("Firebase Realtime Database configured — URL: {}", databaseUrl);
    }

    @Bean
    public RestTemplate firebaseRestTemplate() {
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory());
    }

    public String getDatabaseUrl() {
        return databaseUrl;
    }
}
