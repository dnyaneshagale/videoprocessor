package com.vidprocessor.queue;

import com.vidprocessor.model.VideoProcessingStatus;
import com.vidprocessor.service.VideoProcessingService;
import com.vidprocessor.service.CloudflareR2Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class VideoProcessingQueue {

    private final ConcurrentMap<String, VideoProcessingStatus> taskStatusMap = new ConcurrentHashMap<>();

    /** Tracks how many tasks are actively being processed right now. */
    private final AtomicInteger activeProcessingCount = new AtomicInteger(0);

    /** Max concurrent processing slots (auto-detected from CPU cores, overridable). */
    private final int maxConcurrentSlots;

    @Autowired
    private VideoProcessingService videoProcessingService;

    @Autowired
    private CloudflareR2Service cloudflareR2Service;

    @Autowired
    @Lazy
    private VideoProcessingQueue self;

    public VideoProcessingQueue(
            @Value("${video.processing.max-concurrent-tasks:0}") int configuredMax) {
        int cores = Runtime.getRuntime().availableProcessors();
        this.maxConcurrentSlots = configuredMax > 0 ? configuredMax : cores;
        log.info("VideoProcessingQueue initialised: maxConcurrentSlots={} (CPU cores={})", maxConcurrentSlots, cores);
    }

    /**
     * Add a new video processing request to the queue.
     * If processing slots are available, position = 0 (starts immediately).
     * Otherwise, position = how many tasks are waiting ahead + 1.
     */
    public synchronized VideoProcessingStatus enqueue(String r2ObjectKey) {
        String taskId = UUID.randomUUID().toString();

        VideoProcessingStatus status = new VideoProcessingStatus();
        status.setId(taskId);
        status.setR2ObjectKey(r2ObjectKey);
        status.setStatus("QUEUED");
        status.setCreatedAt(LocalDateTime.now());
        status.setUpdatedAt(LocalDateTime.now());

        // Calculate real queue position
        int currentActive = activeProcessingCount.get();
        if (currentActive < maxConcurrentSlots) {
            // A processing slot is available — this task will start immediately
            status.setQueuePosition(0);
            status.setMessage("Task queued — processing slot available, starting immediately");
        } else {
            // All slots occupied — calculate waiting position
            long waitingAhead = taskStatusMap.values().stream()
                    .filter(s -> "QUEUED".equals(s.getStatus()) && s.getQueuePosition() > 0)
                    .count();
            int position = (int) waitingAhead + 1;
            status.setQueuePosition(position);
            status.setMessage("Task queued — position " + position + " in waiting queue (" + maxConcurrentSlots + " slots busy)");
        }

        taskStatusMap.put(taskId, status);
        log.info("Enqueued video: {} | taskId={} | queuePosition={} | activeSlots={}/{}",
                r2ObjectKey, taskId, status.getQueuePosition(), currentActive, maxConcurrentSlots);

        // Start async processing (call via proxy so @Async is honoured)
        self.processVideoAsync(taskId, r2ObjectKey);

        return status;
    }

    /**
     * Process a video asynchronously
     */
    @Async("videoProcessingExecutor")
    public void processVideoAsync(String taskId, String r2ObjectKey) {
        int currentActive = activeProcessingCount.incrementAndGet();
        log.info("Processing slot acquired for task {} — active: {}/{}", taskId, currentActive, maxConcurrentSlots);

        try {
            log.info("Starting async processing of video: {} (Task ID: {})", r2ObjectKey, taskId);

            // Update status to PROCESSING and recalculate waiting positions
            updateTaskStatus(taskId, "PROCESSING", "Video conversion in progress");
            recalculateQueuePositions();

            // Process the video with specific error handling
            String hlsManifestKey;
            try {
                hlsManifestKey = videoProcessingService.processVideo(r2ObjectKey);
            } catch (IllegalArgumentException e) {
                log.error("Invalid input for task {}: {}", taskId, e.getMessage());
                updateTaskStatus(taskId, "FAILED", "Invalid file: " + e.getMessage());
                return;
            } catch (RuntimeException e) {
                log.error("Processing error for task {}: {}", taskId, e.getMessage());
                String userMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
                if (userMessage.contains("download")) {
                    updateTaskStatus(taskId, "FAILED", "Download failed: " + extractErrorMessage(userMessage));
                } else if (userMessage.contains("conversion") || userMessage.contains("FFmpeg")) {
                    updateTaskStatus(taskId, "FAILED", "Video conversion failed: " + extractErrorMessage(userMessage));
                } else if (userMessage.contains("upload")) {
                    updateTaskStatus(taskId, "FAILED", "Upload failed: " + extractErrorMessage(userMessage));
                } else {
                    updateTaskStatus(taskId, "FAILED", "Processing failed: " + extractErrorMessage(userMessage));
                }
                log.info("Keeping original video file due to processing failure: {}", r2ObjectKey);
                return;
            }

            // Update status to COMPLETED
            updateTaskStatus(taskId, "COMPLETED", "Video conversion completed successfully", hlsManifestKey);

            // After successful conversion, delete the original video file to save storage space
            try {
                cloudflareR2Service.deleteFile(r2ObjectKey);
                log.info("Storage cleanup: Original video deleted after successful conversion to HLS: {}", r2ObjectKey);
            } catch (Exception deleteEx) {
                log.warn("Failed to delete original file after successful conversion: {}", r2ObjectKey, deleteEx);
            }

            log.info("Completed processing of video: {} (Task ID: {})", r2ObjectKey, taskId);

        } catch (Exception e) {
            log.error("Unexpected error processing video: {} (Task ID: {})", r2ObjectKey, taskId, e);
            updateTaskStatus(taskId, "FAILED", "Unexpected error: " + extractErrorMessage(e.getMessage()));
            log.info("Keeping original video file due to processing failure: {}", r2ObjectKey);
        } finally {
            int remaining = activeProcessingCount.decrementAndGet();
            log.info("Processing slot released for task {} — active: {}/{}", taskId, remaining, maxConcurrentSlots);
            recalculateQueuePositions();
        }
    }

    /**
     * Extract meaningful error message from exception messages
     */
    private String extractErrorMessage(String fullMessage) {
        if (fullMessage == null || fullMessage.isEmpty()) {
            return "Unknown error occurred";
        }
        int colonIndex = fullMessage.indexOf(':');
        if (colonIndex > 0 && colonIndex < fullMessage.length() - 1) {
            return fullMessage.substring(colonIndex + 1).trim();
        }
        return fullMessage;
    }

    /**
     * Update the status of a task
     */
    private void updateTaskStatus(String taskId, String status, String message) {
        updateTaskStatus(taskId, status, message, null);
    }

    /**
     * Update the status of a task with HLS manifest key
     */
    private void updateTaskStatus(String taskId, String status, String message, String hlsManifestKey) {
        VideoProcessingStatus currentStatus = taskStatusMap.get(taskId);
        if (currentStatus != null) {
            currentStatus.setStatus(status);
            currentStatus.setMessage(message);
            currentStatus.setUpdatedAt(LocalDateTime.now());
            if (hlsManifestKey != null) {
                currentStatus.setHlsManifestKey(hlsManifestKey);
            }
        } else {
            log.warn("Attempted to update non-existent task: {}", taskId);
        }
    }

    /**
     * Recalculate queue positions for all QUEUED tasks.
     * - PROCESSING / COMPLETED / FAILED → position 0
     * - QUEUED tasks are ordered by createdAt and numbered starting from 1
     *   only if all processing slots are occupied; otherwise they get position 0
     *   (meaning they'll be picked up as soon as a thread is available).
     */
    private synchronized void recalculateQueuePositions() {
        int currentActive = activeProcessingCount.get();

        List<VideoProcessingStatus> queuedTasks = taskStatusMap.values().stream()
                .filter(s -> "QUEUED".equals(s.getStatus()))
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();

        int pos = 1;
        for (VideoProcessingStatus task : queuedTasks) {
            if (currentActive < maxConcurrentSlots) {
                // Slots still available — these will start soon
                task.setQueuePosition(0);
            } else {
                task.setQueuePosition(pos++);
            }
        }

        // Zero out position for non-queued tasks
        taskStatusMap.values().stream()
                .filter(s -> !"QUEUED".equals(s.getStatus()))
                .forEach(s -> s.setQueuePosition(0));
    }

    /**
     * Get a list of all tasks in the queue
     */
    public List<VideoProcessingStatus> getQueueStatus() {
        return new ArrayList<>(taskStatusMap.values());
    }

    /**
     * Get status of a specific task by ID
     */
    public VideoProcessingStatus getTaskStatus(String taskId) {
        return taskStatusMap.get(taskId);
    }

    /**
     * Get status of a task by its R2 object key
     */
    public VideoProcessingStatus getTaskStatusByR2Key(String r2ObjectKey) {
        return taskStatusMap.values().stream()
                .filter(s -> r2ObjectKey.equals(s.getR2ObjectKey()))
                .findFirst()
                .orElse(null);
    }
}