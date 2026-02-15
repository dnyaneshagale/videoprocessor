package com.vidprocessor.model;

import lombok.Data;

@Data
public class VideoProcessingRequest {
    private String r2ObjectKey; // The key of the video file in Cloudflare R2
}