package com.vidprocessor.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;

@Service
@Slf4j
public class VideoProcessingService {

    @Autowired
    private CloudflareR2Service r2Service;

    @Autowired
    private FFmpegService ffmpegService;

    /**
     * Process a video: download from R2, convert to HLS with multiple quality levels, and upload back to R2
     * @param r2ObjectKey The key of the MP4 file in R2
     * @return The key of the master HLS manifest file
     */
    public String processVideo(String r2ObjectKey) {
        File downloadedFile = null;
        Path hlsOutputDir = null;

        try {
            log.info("Starting video processing for: {}", r2ObjectKey);

            // Step 1: Download the MP4 file from Cloudflare R2
            downloadedFile = r2Service.downloadFile(r2ObjectKey);

            // Step 2: Convert the MP4 to HLS format using FFmpeg with multiple quality levels
            // Extract base name to use as output name
            String baseNameWithoutExt = FilenameUtils.getBaseName(r2ObjectKey);
            hlsOutputDir = ffmpegService.convertToHls(downloadedFile, baseNameWithoutExt);

            log.info("HLS conversion completed with multiple quality levels");

            // Step 3: Upload the HLS files back to Cloudflare R2
            String hlsPrefix = FilenameUtils.getPath(r2ObjectKey) + baseNameWithoutExt + "_hls";

            log.info("Uploading HLS files to R2 with prefix: {}", hlsPrefix);
            r2Service.uploadDirectory(hlsOutputDir, hlsPrefix);

            // Return the master playlist key
            String masterManifestKey = hlsPrefix + "/master.m3u8";
            log.info("Video processing completed. Master HLS manifest: {}", masterManifestKey);
            log.info("Multi-quality HLS streams available");

            return masterManifestKey;

        } catch (Exception e) {
            log.error("Error processing video: " + r2ObjectKey, e);
            throw new RuntimeException("Failed to process video: " + e.getMessage(), e);
        } finally {
            // Clean up temporary files
            try {
                if (downloadedFile != null && downloadedFile.exists()) {
                    log.debug("Deleting temporary downloaded file: {}", downloadedFile.getAbsolutePath());
                    downloadedFile.delete();
                }
                if (hlsOutputDir != null) {
                    log.debug("Deleting temporary HLS output directory: {}", hlsOutputDir);
                    FileUtils.deleteDirectory(hlsOutputDir.toFile());
                }
            } catch (Exception e) {
                log.warn("Error cleaning up temporary files", e);
            }
        }
    }
}