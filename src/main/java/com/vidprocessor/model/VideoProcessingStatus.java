package com.vidprocessor.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VideoProcessingStatus {
    private String id;
    private String r2ObjectKey;
    private String status; // QUEUED, PROCESSING, COMPLETED, FAILED
    private String message;
    private String hlsManifestKey; // The key of the master HLS manifest file
    private int queuePosition; // Position in the processing queue (0 = not queued / completed / failed)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}