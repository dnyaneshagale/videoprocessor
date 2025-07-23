package com.vidprocessor.controller;

import com.vidprocessor.model.VideoProcessingRequest;
import com.vidprocessor.model.VideoProcessingStatus;
import com.vidprocessor.queue.VideoProcessingQueue;
import com.vidprocessor.service.ValidationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    @PreAuthorize("hasRole('VIDEO_PROCESSOR')")
    public ResponseEntity<VideoProcessingStatus> processVideo(@RequestBody VideoProcessingRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth.getName();

        log.info("Received request to process video: {} from user: {}", request.getR2ObjectKey(), username);

        // Validate input
        try {
            validationService.validateR2ObjectKey(request.getR2ObjectKey());
        } catch (IllegalArgumentException e) {
            log.warn("Validation failed for request from {}: {}", username, e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // Audit logging
        VideoProcessingStatus status = processingQueue.enqueue(request.getR2ObjectKey());
        log.info("User {} submitted video {} for processing, assigned task ID: {}",
                username, request.getR2ObjectKey(), status.getId());

        return ResponseEntity.accepted().body(status);
    }

    /**
     * Endpoint to check the status of a specific task
     */
    @GetMapping("/status/{taskId}")
    @PreAuthorize("hasRole('VIDEO_PROCESSOR')")
    public ResponseEntity<VideoProcessingStatus> getTaskStatus(@PathVariable String taskId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // Validate taskId format
        if (taskId == null || taskId.isEmpty() || !taskId.matches("[a-zA-Z0-9\\-]+")) {
            log.warn("Invalid taskId format requested by user {}: {}", auth.getName(), taskId);
            return ResponseEntity.badRequest().build();
        }

        VideoProcessingStatus status = processingQueue.getTaskStatus(taskId);
        if (status != null) {
            log.info("User {} requested status for task {}: {}",
                    auth.getName(), taskId, status.getStatus());
            return ResponseEntity.ok(status);
        } else {
            log.info("User {} requested status for non-existent task {}",
                    auth.getName(), taskId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Endpoint to check all tasks in the queue
     */
    @GetMapping("/queue")
    @PreAuthorize("hasRole('ADMIN')")  // Restrict this to admins only
    public ResponseEntity<List<VideoProcessingStatus>> getQueue() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        log.info("User {} requested full queue status", auth.getName());

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