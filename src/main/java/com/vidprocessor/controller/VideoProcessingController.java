package com.vidprocessor.controller;

import com.vidprocessor.model.VideoProcessingRequest;
import com.vidprocessor.model.VideoProcessingStatus;
import com.vidprocessor.queue.VideoProcessingQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/videos")
@Slf4j
public class VideoProcessingController {

    @Autowired
    private VideoProcessingQueue processingQueue;

    /**
     * Endpoint to submit a video for processing
     */
    @PostMapping("/process")
    public ResponseEntity<VideoProcessingStatus> processVideo(@RequestBody VideoProcessingRequest request) {
        log.info("Received request to process video: {}", request.getR2ObjectKey());

        VideoProcessingStatus status = processingQueue.enqueue(request.getR2ObjectKey());
        return ResponseEntity.accepted().body(status);
    }

    /**
     * Endpoint to check the status of a specific task
     */
    @GetMapping("/status/{taskId}")
    public ResponseEntity<VideoProcessingStatus> getTaskStatus(@PathVariable String taskId) {
        VideoProcessingStatus status = processingQueue.getTaskStatus(taskId);
        if (status != null) {
            return ResponseEntity.ok(status);
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Endpoint to check all tasks in the queue
     */
    @GetMapping("/queue")
    public ResponseEntity<List<VideoProcessingStatus>> getQueue() {
        return ResponseEntity.ok(processingQueue.getQueueStatus());
    }
}