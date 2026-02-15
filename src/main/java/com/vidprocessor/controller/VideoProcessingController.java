package com.vidprocessor.controller;

import com.vidprocessor.model.VideoProcessingRequest;
import com.vidprocessor.model.VideoProcessingStatus;
import com.vidprocessor.queue.VideoProcessingQueue;
import com.vidprocessor.service.ValidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/videos")
@Slf4j
public class VideoProcessingController {

    @Autowired
    private VideoProcessingQueue processingQueue;

    @Autowired
    private ValidationService validationService;

    /**
     * Endpoint to submit a video for processing
     * Supports multiple video formats including MP4, MKV, AVI, MOV, WMV, FLV, etc.
     */
    @PostMapping("/process")
    public ResponseEntity<?> processVideo(@RequestBody VideoProcessingRequest request) {
        log.info("Received request to process video: {}", request.getR2ObjectKey());

        // Validate input
        try {
            validationService.validateR2ObjectKey(request.getR2ObjectKey());
        } catch (IllegalArgumentException e) {
            log.warn("Validation failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Validation failed",
                "message", e.getMessage()
            ));
        }

        VideoProcessingStatus status = processingQueue.enqueue(request.getR2ObjectKey());
        log.info("Video {} submitted for processing, assigned task ID: {}",
                request.getR2ObjectKey(), status.getId());

        return ResponseEntity.accepted().body(status);
    }

    /**
     * Endpoint to check the status of a specific task
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<?> getTaskStatus(@PathVariable String taskId) {
        // Validate taskId format
        if (taskId == null || taskId.trim().isEmpty() || !taskId.matches("[a-zA-Z0-9\\-]+")) {
            log.warn("Invalid taskId format: {}", taskId);
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid taskId",
                "message", "TaskId must contain only alphanumeric characters and hyphens"
            ));
        }

        VideoProcessingStatus status = processingQueue.getTaskStatus(taskId);
        if (status != null) {
            return ResponseEntity.ok(status);
        } else {
            return ResponseEntity.status(404).body(Map.of(
                "error", "Task not found",
                "message", "No task found with ID: " + taskId
            ));
        }
    }

    /**
     * Endpoint to check the status of a task by its R2 object key
     */
    @GetMapping("/status")
    public ResponseEntity<?> getTaskStatusByR2Key(@RequestParam("r2ObjectKey") String r2ObjectKey) {
        if (r2ObjectKey == null || r2ObjectKey.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid r2ObjectKey",
                "message", "r2ObjectKey query parameter is required"
            ));
        }

        VideoProcessingStatus status = processingQueue.getTaskStatusByR2Key(r2ObjectKey.trim());
        if (status != null) {
            return ResponseEntity.ok(status);
        } else {
            return ResponseEntity.status(404).body(Map.of(
                "error", "Task not found",
                "message", "No task found for r2ObjectKey: " + r2ObjectKey
            ));
        }
    }

    /**
     * Endpoint to check all tasks in the queue
     */
    @GetMapping("/queue")
    public ResponseEntity<List<VideoProcessingStatus>> getQueue() {
        return ResponseEntity.ok(processingQueue.getQueueStatus());
    }

    /**
     * Endpoint to get information about supported video formats
     */
    @GetMapping("/formats")
    public ResponseEntity<String> getSupportedFormats() {
        return ResponseEntity.ok("Supported video formats: MP4, MKV, AVI, MOV, WMV, FLV, WebM, M4V, 3GP, TS, MTS, M2TS, MPG, MPEG, VOB, OGV, MXF, F4V, ASF, DIVX");
    }
}