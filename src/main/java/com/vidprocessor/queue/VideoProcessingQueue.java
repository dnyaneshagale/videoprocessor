package com.vidprocessor.queue;

import com.vidprocessor.model.VideoProcessingStatus;
import com.vidprocessor.service.VideoProcessingService;
import com.vidprocessor.service.CloudflareR2Service; // Add this import
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@Slf4j
public class VideoProcessingQueue {

    private final ConcurrentMap<String, VideoProcessingStatus> taskStatusMap = new ConcurrentHashMap<>();

    @Autowired
    private VideoProcessingService videoProcessingService;

    @Autowired
    private CloudflareR2Service cloudflareR2Service; // Add this field

    /**
     * Add a new video processing request to the queue
     */
    public VideoProcessingStatus enqueue(String r2ObjectKey) {
        String taskId = UUID.randomUUID().toString();

        VideoProcessingStatus status = new VideoProcessingStatus();
        status.setId(taskId);
        status.setR2ObjectKey(r2ObjectKey);
        status.setStatus("QUEUED");
        status.setMessage("Task queued for processing");
        status.setCreatedAt(LocalDateTime.now());
        status.setUpdatedAt(LocalDateTime.now());

        taskStatusMap.put(taskId, status);
        log.info("Added video to queue: {} with task ID: {}", r2ObjectKey, taskId);

        // Start async processing immediately
        processVideoAsync(taskId, r2ObjectKey);

        return status;
    }

    /**
     * Process a video asynchronously
     */
    @Async("videoProcessingExecutor")
    protected void processVideoAsync(String taskId, String r2ObjectKey) {
        try {
            log.info("Starting async processing of video: {} (Task ID: {})", r2ObjectKey, taskId);

            // Update status to PROCESSING
            updateTaskStatus(taskId, "PROCESSING", "Video conversion in progress");

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
                String userMessage = e.getMessage();
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
                // Don't fail the task if cleanup fails
            }

            log.info("Completed processing of video: {} (Task ID: {})", r2ObjectKey, taskId);

        } catch (Exception e) {
            log.error("Unexpected error processing video: {} (Task ID: {})", r2ObjectKey, taskId, e);
            updateTaskStatus(taskId, "FAILED", "Unexpected error: " + extractErrorMessage(e.getMessage()));
            log.info("Keeping original video file due to processing failure: {}", r2ObjectKey);
        }
    }

    /**
     * Extract meaningful error message from exception messages
     */
    private String extractErrorMessage(String fullMessage) {
        if (fullMessage == null || fullMessage.isEmpty()) {
            return "Unknown error occurred";
        }
        // Extract the main error message without stack trace details
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
}