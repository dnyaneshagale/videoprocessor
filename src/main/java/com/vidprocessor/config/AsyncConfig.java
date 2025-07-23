package com.vidprocessor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "videoProcessingExecutor")
    public Executor videoProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);  // Number of concurrent videos to process
        executor.setMaxPoolSize(5);   // Maximum threads during high load
        executor.setQueueCapacity(10); // Queue size for pending requests
        executor.setThreadNamePrefix("video-proc-");
        executor.initialize();
        return executor;
    }
}