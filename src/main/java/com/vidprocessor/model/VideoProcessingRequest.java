package com.vidprocessor.model;

import lombok.Data;

@Data
public class VideoProcessingRequest {
    private String r2ObjectKey; // The key of the MP4 file in Cloudflare R2
}