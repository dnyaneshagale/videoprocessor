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

            // Process the video
            String hlsManifestKey = videoProcessingService.processVideo(r2ObjectKey);

            // Update status to COMPLETED
            updateTaskStatus(taskId, "COMPLETED", "Video conversion completed successfully", hlsManifestKey);

            // After successful conversion, delete the original video file to save storage space
            // This runs only when the HLS conversion was successful
            cloudflareR2Service.deleteFile(r2ObjectKey);
            log.info("Storage cleanup: Original video deleted after successful conversion to HLS: {}", r2ObjectKey);

            log.info("Completed processing of video: {} (Task ID: {})", r2ObjectKey, taskId);

        } catch (Exception e) {
            log.error("Error processing video: {} (Task ID: {})", r2ObjectKey, taskId, e);
            updateTaskStatus(taskId, "FAILED", "Error: " + e.getMessage());

            // Do NOT delete the original file if processing failed
            log.info("Keeping original video file due to processing failure: {}", r2ObjectKey);
        }
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