package com.vidprocessor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import org.apache.commons.io.FilenameUtils;

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

    @Value("${ffmpeg.path:}")
    private String ffmpegPath;

    @Value("${ffprobe.path:}")
    private String ffprobePath;

    // Video quality profile definitions
    private static final Map<String, QualityProfile> QUALITY_PROFILES = new HashMap<>();

    static {
        // Initialize quality profiles (resolution, videoBitrate kbps, audioBitrate kbps, CRF)
        QUALITY_PROFILES.put("1440p", new QualityProfile(2560, 1440, 14000, 192, 20));
        QUALITY_PROFILES.put("1080p", new QualityProfile(1920, 1080, 8000, 192, 21));
        QUALITY_PROFILES.put("720p", new QualityProfile(1280, 720, 5000, 192, 23));
        QUALITY_PROFILES.put("480p", new QualityProfile(854, 480, 2500, 128, 24));
        QUALITY_PROFILES.put("360p", new QualityProfile(640, 360, 1200, 128, 25));
        QUALITY_PROFILES.put("240p", new QualityProfile(426, 240, 700, 96, 26));
    }

    /**
     * Converts a video file to HLS format with appropriate quality levels
     * based on source video resolution. Supports multiple input formats.
     */
    public Path convertToHls(File inputFile, String outputName) throws IOException, InterruptedException {
        // Validate input file
        if (inputFile == null || !inputFile.exists()) {
            throw new IllegalArgumentException("Input file does not exist: " + (inputFile != null ? inputFile.getAbsolutePath() : "null"));
        }

        String fileExtension = FilenameUtils.getExtension(inputFile.getName()).toLowerCase();
        log.info("Converting video to HLS: {} (format: {})", inputFile.getName(), fileExtension);

        if (!inputFile.canRead()) {
            throw new IOException("Cannot read input file: " + inputFile.getAbsolutePath());
        }

        if (inputFile.length() == 0) {
            throw new IOException("Input file is empty: " + inputFile.getAbsolutePath());
        }

        // Verify FFmpeg is installed
        if (!isFFmpegAvailable()) {
            throw new IOException(buildFFmpegNotFoundError());
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
            log.error("Error during FFmpeg conversion for format: {}", fileExtension, e);
            cleanupOutputDir(outputDir);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw e;
        }
    }

    /**
     * Clean up output directory on error
     */
    private void cleanupOutputDir(Path outputDir) {
        if (outputDir != null && Files.exists(outputDir)) {
            try {
                Files.walk(outputDir)
                        .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException ex) {
                                log.warn("Failed to delete: {}", path, ex);
                            }
                        });
            } catch (IOException cleanupEx) {
                log.warn("Failed to clean up output directory: {}", outputDir, cleanupEx);
            }
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
        command.add(getFFprobeExecutable());
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
            throw new IOException("FFprobe failed to analyze video (exit code " + exitCode + "): " + output);
        }

        String json = output.toString();
        log.debug("FFprobe JSON output: {}", json);

        if (json.isEmpty()) {
            throw new IOException("FFprobe returned empty output");
        }

        int width = extractIntFromJson(json, "width\"\\s*:\\s*(\\d+)");
        int height = extractIntFromJson(json, "height\"\\s*:\\s*(\\d+)");
        String codec = extractStringFromJson(json, "codec_name\"\\s*:\\s*\"([^\"]+)");
        double duration = extractDoubleFromJson(json, "duration\"\\s*:\\s*\"?([\\d\\.]+)");

        if (width == 0 || height == 0) {
            throw new IOException("Could not determine video dimensions from ffprobe output. The file may be corrupted or not a valid video.");
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
     * Determines which quality profiles to process based on the source video metadata.
     * Generates a comprehensive adaptive bitrate ladder for smooth quality switching.
     */
    private List<String> determineQualityProfiles(VideoMetadata metadata) {
        List<String> profiles = new ArrayList<>();

        // Full adaptive bitrate ladder — include every tier at or below source resolution
        if (metadata.height >= 1440) {
            profiles.add("1440p");
        }
        if (metadata.height >= 1080) {
            profiles.add("1080p");
        }
        if (metadata.height >= 720) {
            profiles.add("720p");
        }
        if (metadata.height >= 480) {
            profiles.add("480p");
        }
        if (metadata.height >= 360) {
            profiles.add("360p");
        }
        // Always include a low-bandwidth fallback
        profiles.add("240p");

        return profiles;
    }

    /**
     * Creates a specific quality variant - this works with any input format
     */
    private void createVariant(File inputFile, Path outputDir, String profileName, QualityProfile profile) throws IOException {
        log.info("Creating {} variant ({} Kbps)", profileName, profile.videoBitrate);

        List<String> command = new ArrayList<>();
        command.add(getFFmpegExecutable());
        command.add("-i");
        command.add(inputFile.getAbsolutePath());

        // Add stream-mapping for container formats that may have multiple streams
        String fileExtension = FilenameUtils.getExtension(inputFile.getName()).toLowerCase();
        if (fileExtension.equals("mkv") || fileExtension.equals("avi") || fileExtension.equals("wmv")) {
            command.add("-map");
            command.add("0:v:0"); // First video stream
            command.add("-map");
            command.add("0:a:0?"); // First audio stream (if exists)
        }

        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-profile:v");
        command.add("high");
        command.add("-level");
        command.add("4.1");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-vf");
        command.add(String.format("scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2",
                profile.width, profile.height, profile.width, profile.height));

        // CRF-constrained encoding: quality-aware with a bitrate ceiling
        command.add("-crf");
        command.add(String.valueOf(profile.crf));
        command.add("-b:v");
        command.add(profile.videoBitrate + "k");
        command.add("-maxrate");
        command.add((int)(profile.videoBitrate * 1.5) + "k");
        command.add("-bufsize");
        command.add((profile.videoBitrate * 2) + "k");

        // GOP = 2× segment duration (keyframe every 2 sec at ~30 fps) for clean segment cuts
        command.add("-g");
        command.add("48");
        command.add("-keyint_min");
        command.add("48");
        command.add("-sc_threshold");
        command.add("0");

        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add(profile.audioBitrate + "k");
        command.add("-ar");
        command.add("48000");
        command.add("-hls_time");
        command.add("4");
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
            ProcessBuilder processBuilder = new ProcessBuilder(getFFmpegExecutable(), "-version");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException e) {
            log.error("FFmpeg not found: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Error checking FFmpeg availability", e);
            return false;
        }
    }

    /**
     * Get the FFmpeg executable path (configured or from PATH)
     */
    private String getFFmpegExecutable() {
        if (ffmpegPath != null && !ffmpegPath.trim().isEmpty()) {
            return ffmpegPath.trim();
        }
        return "ffmpeg"; // Fall back to system PATH
    }

    /**
     * Get the FFprobe executable path (configured or from PATH)
     */
    private String getFFprobeExecutable() {
        if (ffprobePath != null && !ffprobePath.trim().isEmpty()) {
            return ffprobePath.trim();
        }
        return "ffprobe"; // Fall back to system PATH
    }

    /**
     * Build error message when FFmpeg is not found
     */
    private String buildFFmpegNotFoundError() {
        String configured = (ffmpegPath != null && !ffmpegPath.trim().isEmpty()) ? ffmpegPath : "system PATH";
        return "FFmpeg not found. Install FFmpeg and configure ffmpeg.path / ffprobe.path " +
                "in application.properties, or add it to the system PATH. " +
                "Current config: " + configured;
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
     * Value class to hold video metadata
     */
    private static class VideoMetadata {
        final int width;
        final int height;
        final double durationSeconds;
        final String codec;

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
        final int crf;          // constant rate factor (lower = better quality)

        QualityProfile(int width, int height, int videoBitrate, int audioBitrate, int crf) {
            this.width = width;
            this.height = height;
            this.videoBitrate = videoBitrate;
            this.audioBitrate = audioBitrate;
            this.crf = crf;
        }
    }
}