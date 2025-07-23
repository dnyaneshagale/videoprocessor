package com.vidprocessor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class FFmpegService {

    // Video quality profile definitions
    private static final Map<String, QualityProfile> QUALITY_PROFILES = new HashMap<>();

    static {
        // Initialize quality profiles
        QUALITY_PROFILES.put("1440p", new QualityProfile(2560, 1440, 9000, 192));
        QUALITY_PROFILES.put("1080p", new QualityProfile(1920, 1080, 6000, 192));
        QUALITY_PROFILES.put("720p", new QualityProfile(1280, 720, 3500, 192));
        QUALITY_PROFILES.put("480p", new QualityProfile(854, 480, 1800, 128));
        QUALITY_PROFILES.put("360p", new QualityProfile(640, 360, 800, 96));
        QUALITY_PROFILES.put("240p", new QualityProfile(426, 240, 500, 64));
        QUALITY_PROFILES.put("144p", new QualityProfile(256, 144, 300, 48));
    }

    /**
     * Converts a video file to HLS format with appropriate quality levels
     * based on source video resolution. Supports multiple input formats.
     */
    public Path convertToHls(File inputFile, String outputName) throws IOException, InterruptedException {
        String fileExtension = getFileExtension(inputFile.getName()).toLowerCase();
        log.info("Converting video to HLS: {} (format: {})", inputFile.getName(), fileExtension);
        log.info("Current date/time: 2025-07-23 07:33:43 UTC, User: dnyaneshagale");

        // Verify FFmpeg is installed
        if (!isFFmpegAvailable()) {
            throw new IOException("FFmpeg is not available. Please install FFmpeg and make sure it's on the PATH.");
        }

        // Create a temporary directory for the output
        Path outputDir = Files.createTempDirectory("hls-output-");
        log.info("Output directory created: {}", outputDir);

        try {
            // Analyze the input video to determine appropriate quality levels
            VideoMetadata metadata = analyzeVideo(inputFile);
            log.info("Source video analysis: {}x{} pixels, duration: {} seconds, codec: {}",
                    metadata.width, metadata.height, metadata.durationSeconds, metadata.codec);

            // Determine which quality profiles to use based on source video
            List<String> profilesToProcess = determineQualityProfiles(metadata);
            log.info("Selected quality profiles for processing: {}", profilesToProcess);

            // Process each selected quality profile
            for (String profileName : profilesToProcess) {
                createVariant(inputFile, outputDir, profileName, QUALITY_PROFILES.get(profileName));
            }

            // Create a master playlist that references all processed quality variants
            createMasterPlaylist(outputDir, profilesToProcess);

            log.info("Adaptive HLS conversion completed for: {} (original format: {})",
                    outputName, fileExtension);
            log.info("Generated {} quality levels based on source resolution {}x{}",
                    profilesToProcess.size(), metadata.width, metadata.height);

            return outputDir;
        } catch (Exception e) {
            log.error("FFmpeg conversion failed for format: " + fileExtension, e);
            throw e;
        }
    }

    /**
     * Analyzes the input video to determine its resolution, duration, and other properties
     * This works with any format supported by ffprobe, using a safer approach
     */
    private VideoMetadata analyzeVideo(File inputFile) throws IOException, InterruptedException {
        log.info("Analyzing video file: {}", inputFile.getName());

        // Using ffprobe to get detailed JSON output which is more reliable to parse
        List<String> command = new ArrayList<>();
        command.add("ffprobe");
        command.add("-v");
        command.add("quiet");
        command.add("-print_format");
        command.add("json");
        command.add("-show_format");
        command.add("-show_streams");
        command.add(inputFile.getAbsolutePath());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Failed to analyze video: " + output);
        }

        String json = output.toString();
        log.debug("FFprobe JSON output: {}", json);

        // Fallback method if JSON parsing is too complex for this implementation
        // We'll use regex to extract the information we need

        int width = extractIntFromJson(json, "width\"\\s*:\\s*(\\d+)");
        int height = extractIntFromJson(json, "height\"\\s*:\\s*(\\d+)");
        String codec = extractStringFromJson(json, "codec_name\"\\s*:\\s*\"([^\"]+)");
        double duration = extractDoubleFromJson(json, "duration\"\\s*:\\s*\"?([\\d\\.]+)");

        if (width == 0 || height == 0) {
            throw new IOException("Could not determine video dimensions from ffprobe output");
        }

        // If codec wasn't found in the first attempt, try looking for video codec specifically
        if (codec == null || codec.isEmpty()) {
            codec = extractStringFromJson(json, "codec_type\"\\s*:\\s*\"video\"[^}]+codec_name\"\\s*:\\s*\"([^\"]+)");
        }

        if (codec == null || codec.isEmpty()) {
            codec = "unknown";
        }

        log.info("Extracted metadata: width={}, height={}, codec={}, duration={}", width, height, codec, duration);
        return new VideoMetadata(width, height, duration, codec);
    }

    private int extractIntFromJson(String json, String pattern) {
        Pattern regex = Pattern.compile(pattern);
        Matcher matcher = regex.matcher(json);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse int: {}", matcher.group(1));
            }
        }
        return 0;
    }

    private double extractDoubleFromJson(String json, String pattern) {
        Pattern regex = Pattern.compile(pattern);
        Matcher matcher = regex.matcher(json);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("Failed to parse double: {}", matcher.group(1));
            }
        }
        return 0.0;
    }

    private String extractStringFromJson(String json, String pattern) {
        Pattern regex = Pattern.compile(pattern);
        Matcher matcher = regex.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /**
     * Determines which quality profiles to process based on the source video metadata
     */
    private List<String> determineQualityProfiles(VideoMetadata metadata) {
        List<String> profiles = new ArrayList<>();

        // Logic to select appropriate profiles based on source resolution
        if (metadata.height >= 1440) {
            // Source is 1440p or higher - use all profiles
            profiles.add("1440p");
            profiles.add("1080p");
            profiles.add("720p");
            profiles.add("480p");
            profiles.add("360p");
            profiles.add("240p");
            profiles.add("144p");
        }
        else if (metadata.height >= 1080) {
            // Source is 1080p - don't upscale to 1440p
            profiles.add("1080p");
            profiles.add("720p");
            profiles.add("480p");
            profiles.add("360p");
            profiles.add("240p");
            profiles.add("144p");
        }
        else if (metadata.height >= 720) {
            // Source is 720p
            profiles.add("720p");
            profiles.add("480p");
            profiles.add("360p");
            profiles.add("240p");
            profiles.add("144p");
        }
        else if (metadata.height >= 480) {
            // Source is 480p
            profiles.add("480p");
            profiles.add("360p");
            profiles.add("240p");
            profiles.add("144p");
        }
        else if (metadata.height >= 360) {
            // Source is 360p
            profiles.add("360p");
            profiles.add("240p");
            profiles.add("144p");
        }
        else if (metadata.height >= 240) {
            // Source is 240p
            profiles.add("240p");
            profiles.add("144p");
        }
        else {
            // Source is very low resolution
            profiles.add("144p");
        }

        // For very short videos, reduce the number of profiles to save processing time
        if (metadata.durationSeconds < 60) { // Less than 1 minute
            // Keep only every other profile for short videos
            List<String> reducedProfiles = new ArrayList<>();
            for (int i = 0; i < profiles.size(); i++) {
                reducedProfiles.add(profiles.get(i));
            }
            // Always ensure we have at least the lowest quality
            if (!reducedProfiles.contains("144p")) {
                reducedProfiles.add("144p");
            }
            return reducedProfiles;
        }

        return profiles;
    }

    /**
     * Creates a specific quality variant - this works with any input format
     */
    private void createVariant(File inputFile, Path outputDir, String profileName, QualityProfile profile) throws IOException {
        log.info("Creating {} variant ({} Kbps)", profileName, profile.videoBitrate);

        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(inputFile.getAbsolutePath());

        // Add specific options for handling problematic formats if needed
        String fileExtension = getFileExtension(inputFile.getName()).toLowerCase();
        if (fileExtension.equals("mkv") || fileExtension.equals("avi") || fileExtension.equals("wmv")) {
            // Some formats might need special handling
            command.add("-map");
            command.add("0:v:0"); // First video stream
            command.add("-map");
            command.add("0:a:0?"); // First audio stream (if exists)
        }

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-vf");
        command.add(String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2",
                profile.width, profile.height, profile.width, profile.height));
        command.add("-b:v");
        command.add(profile.videoBitrate + "k");
        command.add("-maxrate");
        command.add((int)(profile.videoBitrate * 1.1) + "k");
        command.add("-bufsize");
        command.add((profile.videoBitrate * 2) + "k");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add(profile.audioBitrate + "k");
        command.add("-hls_time");
        command.add("10");
        command.add("-hls_list_size");
        command.add("0");
        command.add("-hls_segment_filename");
        command.add(outputDir.resolve(profileName + "_%03d.ts").toString());
        command.add(outputDir.resolve(profileName + ".m3u8").toString());

        executeCommand(command);
    }

    /**
     * Creates a master playlist that references all processed quality variants
     */
    private void createMasterPlaylist(Path outputDir, List<String> processedProfiles) throws IOException {
        log.info("Creating master playlist with {} quality levels", processedProfiles.size());
        StringBuilder masterPlaylist = new StringBuilder();
        masterPlaylist.append("#EXTM3U\n");
        masterPlaylist.append("#EXT-X-VERSION:3\n");

        // Add each processed quality level to the master playlist
        for (String profileName : processedProfiles) {
            QualityProfile profile = QUALITY_PROFILES.get(profileName);
            int totalBandwidth = profile.videoBitrate + profile.audioBitrate;

            masterPlaylist.append(String.format("#EXT-X-STREAM-INF:BANDWIDTH=%d000,RESOLUTION=%dx%d\n",
                    totalBandwidth, profile.width, profile.height));
            masterPlaylist.append(profileName + ".m3u8\n");
        }

        // Write master playlist file
        Files.writeString(outputDir.resolve("master.m3u8"), masterPlaylist.toString());
    }

    /**
     * Checks if FFmpeg is available on the system
     */
    private boolean isFFmpegAvailable() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("ffmpeg", "-version");
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.error("Error checking FFmpeg availability", e);
            return false;
        }
    }

    /**
     * Executes an FFmpeg command with better error handling
     */
    private void executeCommand(List<String> command) throws IOException {
        log.info("Executing FFmpeg command: {}", String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true); // Merge stdout and stderr

        Process process = processBuilder.start();

        // Capture and log the output
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                // Log FFmpeg progress
                if (line.contains("frame=") && line.contains("fps=")) {
                    log.debug(line.trim());
                }
            }
        }

        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("FFmpeg process failed with exit code {}", exitCode);
                log.error("FFmpeg output: {}", output);
                throw new IOException("FFmpeg process exited with code " + exitCode +
                        "\nCommand: " + String.join(" ", command) +
                        "\nOutput: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("FFmpeg process interrupted: {}", e.getMessage());
            throw new IOException("FFmpeg process interrupted", e);
        }
    }

    /**
     * Extract file extension from a filename
     */
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
            return filename.substring(lastDotIndex + 1);
        }
        return "";
    }

    /**
     * Value class to hold video metadata
     */
    private static class VideoMetadata {
        final int width;
        final int height;
        final double durationSeconds;
        final String codec;

        VideoMetadata(int width, int height, double durationSeconds) {
            this(width, height, durationSeconds, "unknown");
        }

        VideoMetadata(int width, int height, double durationSeconds, String codec) {
            this.width = width;
            this.height = height;
            this.durationSeconds = durationSeconds;
            this.codec = codec;
        }
    }

    /**
     * Value class to hold quality profile information
     */
    private static class QualityProfile {
        final int width;
        final int height;
        final int videoBitrate; // in kbps
        final int audioBitrate; // in kbps

        QualityProfile(int width, int height, int videoBitrate, int audioBitrate) {
            this.width = width;
            this.height = height;
            this.videoBitrate = videoBitrate;
            this.audioBitrate = audioBitrate;
        }
    }
}