package com.vidprocessor.queue;

import com.vidprocessor.model.VideoProcessingStatus;
import com.vidprocessor.service.VideoProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class VideoProcessingQueue {

    private final ConcurrentLinkedQueue<VideoProcessingStatus> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean processing = new AtomicBoolean(false);

    @Autowired
    private VideoProcessingService videoProcessingService;

    /**
     * Add a new video processing request to the queue
     */
    public VideoProcessingStatus enqueue(String r2ObjectKey) {
        VideoProcessingStatus status = new VideoProcessingStatus();
        status.setId(UUID.randomUUID().toString());
        status.setR2ObjectKey(r2ObjectKey);
        status.setStatus("QUEUED");
        status.setMessage("Task added to the queue");
        status.setCreatedAt(LocalDateTime.now());
        status.setUpdatedAt(LocalDateTime.now());

        queue.offer(status);
        log.info("Added video to queue: {}", r2ObjectKey);

        // Start processing if not already in progress
        processNextInQueue();

        return status;
    }

    /**
     * Get a list of all tasks in the queue
     */
    public List<VideoProcessingStatus> getQueueStatus() {
        return new ArrayList<>(queue);
    }

    /**
     * Get status of a specific task by ID
     */
    public VideoProcessingStatus getTaskStatus(String taskId) {
        return queue.stream()
                .filter(task -> task.getId().equals(taskId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Process the next video in the queue
     */
    @Async
    public void processNextInQueue() {
        // Only one thread should process videos at a time
        if (processing.compareAndSet(false, true)) {
            try {
                VideoProcessingStatus task = queue.peek();
                if (task != null && "QUEUED".equals(task.getStatus())) {
                    log.info("Processing next video in queue: {}", task.getR2ObjectKey());

                    // Update status to PROCESSING
                    task.setStatus("PROCESSING");
                    task.setMessage("Video conversion in progress");
                    task.setUpdatedAt(LocalDateTime.now());

                    try {
                        // Process the video
                        String hlsManifestKey = videoProcessingService.processVideo(task.getR2ObjectKey());

                        // Update status to COMPLETED
                        task.setStatus("COMPLETED");
                        task.setMessage("Video conversion completed successfully");
                        task.setHlsManifestKey(hlsManifestKey);
                        task.setUpdatedAt(LocalDateTime.now());

                        log.info("Video processing completed: {}", task.getR2ObjectKey());
                    } catch (Exception e) {
                        log.error("Error processing video: " + task.getR2ObjectKey(), e);
                        task.setStatus("FAILED");
                        task.setMessage("Error: " + e.getMessage());
                        task.setUpdatedAt(LocalDateTime.now());
                    }

                    // Remove processed task from queue after some time to allow status checking
                    // In a production environment, you might want to store this in a database
                }
            } finally {
                processing.set(false);

                // If there are more items in the queue, process the next one
                if (!queue.isEmpty() && queue.peek().getStatus().equals("QUEUED")) {
                    processNextInQueue();
                }
            }
        }
    }
}