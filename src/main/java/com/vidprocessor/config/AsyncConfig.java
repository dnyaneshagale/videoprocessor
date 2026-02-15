package com.vidprocessor.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Auto-tuned async configuration that sizes the thread pool to the VM's resources.
 *
 * How it works:
 * - Detects available CPU cores at startup.
 * - Sets core pool = cores (each FFmpeg process saturates ~1 core).
 * - Sets max pool  = cores × 2 (allows I/O-bound phases to overlap).
 * - Queue capacity = max pool × 10 for burst traffic.
 *
 * Override via application.properties:
 *   video.processing.max-concurrent-tasks=0   (0 = auto-detect)
 */
@Configuration
@EnableAsync
@Slf4j
public class AsyncConfig {

    @Value("${video.processing.max-concurrent-tasks:0}")
    private int maxConcurrentTasks;

    @Bean(name = "videoProcessingExecutor")
    public Executor videoProcessingExecutor() {
        int availableCores = Runtime.getRuntime().availableProcessors();
        long totalMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        // Auto-detect: use all cores; each FFmpeg encode is CPU-bound
        int coreSize = maxConcurrentTasks > 0 ? maxConcurrentTasks : availableCores;
        int maxSize = coreSize * 2;
        int queueCapacity = maxSize * 10;

        log.info("═══════════════════════════════════════════════════════════════");
        log.info("  VM Resource Detection");
        log.info("  CPU cores available : {}", availableCores);
        log.info("  JVM max memory      : {} MB", totalMemoryMb);
        log.info("  ─────────────────────────────────────────────────────────────");
        log.info("  Thread pool config  : core={}, max={}, queue={}", coreSize, maxSize, queueCapacity);
        log.info("  Concurrent encodes  : {} (1 FFmpeg process per core thread)", coreSize);
        log.info("═══════════════════════════════════════════════════════════════");

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("video-proc-");
        executor.setKeepAliveSeconds(60);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(300);

        // CallerRunsPolicy: if queue is full, the submitting thread processes the task
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        return executor;
    }
}