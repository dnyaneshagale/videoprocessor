package com.vidprocessor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * High-capacity async configuration for processing 1000+ concurrent video requests
 * 
 * Performance Specifications:
 * - Core threads: 50 (always active for immediate processing)
 * - Max threads: 200 (scales up during peak load)
 * - Queue capacity: 1500 (handles burst traffic)
 * - Expected throughput: 1000+ concurrent video processing tasks
 * 
 * System Requirements for optimal performance:
 * - CPU: 16+ cores recommended
 * - RAM: 8GB+ (16GB recommended for heavy video processing)
 * - JVM Heap: -Xms2g -Xmx8g minimum
 * - Network: High-bandwidth connection to R2 storage
 * 
 * Performance tuning:
 * - Use G1GC for better concurrent performance
 * - Enable HTTP/2 and compression in application.properties
 * - Cloudflare R2 client uses 500 max connections
 * - Tomcat configured for 10,000 concurrent connections
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "videoProcessingExecutor")
    public Executor videoProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Optimized for 1000+ concurrent video processing tasks
        executor.setCorePoolSize(50);      // Core threads always alive for processing
        executor.setMaxPoolSize(200);      // Maximum threads during peak load
        executor.setQueueCapacity(1500);   // Large queue to handle burst requests
        
        // Thread configuration
        executor.setThreadNamePrefix("video-proc-");
        executor.setKeepAliveSeconds(60);  // Keep idle threads for 60 seconds
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300); // Wait up to 5 minutes for tasks to complete on shutdown
        
        // Rejection policy: CallerRunsPolicy - caller thread executes if queue is full
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        
        executor.initialize();
        return executor;
    }
}