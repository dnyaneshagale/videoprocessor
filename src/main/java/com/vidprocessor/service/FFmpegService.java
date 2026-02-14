package com.vidprocessor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${ffmpeg.path:}")
    private String ffmpegPath;

    @Value("${ffprobe.path:}")
    private String ffprobePath;

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

        // Validate input file
        if (inputFile == null || !inputFile.exists()) {
            throw new IllegalArgumentException("Input file does not exist: " + (inputFile != null ? inputFile.getAbsolutePath() : "null"));
        }

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
        } catch (IllegalArgumentException e) {
            log.error("Invalid input for FFmpeg conversion: {}", e.getMessage());
            cleanupOutputDir(outputDir);
            throw new IOException("Invalid input file: " + e.getMessage(), e);
        } catch (IOException e) {
            log.error("IO error during FFmpeg conversion for format: {}", fileExtension, e);
            cleanupOutputDir(outputDir);
            throw new IOException("HLS conversion failed (IO error): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.error("FFmpeg conversion interrupted for format: {}", fileExtension, e);
            cleanupOutputDir(outputDir);
            Thread.currentThread().interrupt();
            throw new InterruptedException("HLS conversion interrupted: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during FFmpeg conversion for format: {}", fileExtension, e);
            cleanupOutputDir(outputDir);
            throw new IOException("HLS conversion failed (unexpected error): " + e.getMessage(), e);
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

        try {
            if (inputFile == null || !inputFile.exists()) {
                throw new IllegalArgumentException("Input file does not exist for analysis");
            }

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
            } catch (IOException e) {
                log.error("Error reading ffprobe output", e);
                throw new IOException("Failed to read video analysis output: " + e.getMessage(), e);
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

            // Fallback method if JSON parsing is too complex for this implementation
            // We'll use regex to extract the information we need

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
        } catch (IllegalArgumentException e) {
            log.error("Invalid input file for analysis", e);
            throw new IOException("Video analysis failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            log.error("Video analysis interrupted", e);
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error during video analysis", e);
            throw new IOException("Video analysis failed: " + e.getMessage(), e);
        }
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

        // Optimized for speed: Only 3 quality levels (High/Medium/Low)
        if (metadata.height >= 1440) {
            profiles.add("1440p");
            profiles.add("720p");
            profiles.add("360p");
        }
        else if (metadata.height >= 1080) {
            profiles.add("1080p");
            profiles.add("480p");
            profiles.add("240p");
        }
        else if (metadata.height >= 720) {
            profiles.add("720p");
            profiles.add("360p");
            profiles.add("144p");
        }
        else if (metadata.height >= 480) {
            profiles.add("480p");
            profiles.add("240p");
            profiles.add("144p");
        }
        else if (metadata.height >= 360) {
            profiles.add("360p");
            profiles.add("144p");
        }
        else {
            profiles.add("240p");
        }

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
        command.add("ultrafast");
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
     * Build comprehensive error message when FFmpeg is not found
     */
    private String buildFFmpegNotFoundError() {
        String os = System.getProperty("os.name").toLowerCase();
        StringBuilder error = new StringBuilder();
        error.append("\n\n");
        error.append("═══════════════════════════════════════════════════════════════\n");
        error.append("  ❌ FFMPEG NOT FOUND\n");
        error.append("═══════════════════════════════════════════════════════════════\n\n");
        
        if (os.contains("win")) {
            error.append("🪟 WINDOWS INSTALLATION:\n\n");
            error.append("1. Download FFmpeg:\n");
            error.append("   https://www.gyan.dev/ffmpeg/builds/\n");
            error.append("   Get: ffmpeg-release-essentials.zip\n\n");
            error.append("2. Extract to: C:\\ffmpeg\\\n");
            error.append("   Verify: C:\\ffmpeg\\bin\\ffmpeg.exe exists\n\n");
            error.append("3. Add to System PATH:\n");
            error.append("   - Right-click 'This PC' → Properties\n");
            error.append("   - Advanced system settings → Environment Variables\n");
            error.append("   - Edit 'Path' → New → Add: C:\\ffmpeg\\bin\n");
            error.append("   - Restart terminal and IDE\n\n");
            error.append("4. Verify: Run 'ffmpeg -version' in CMD\n\n");
            error.append("📝 ALTERNATIVE (Production):\n");
            error.append("   Configure in application.properties:\n");
            error.append("   ffmpeg.path=C:/ffmpeg/bin/ffmpeg.exe\n");
            error.append("   ffprobe.path=C:/ffmpeg/bin/ffprobe.exe\n");
        } else if (os.contains("nix") || os.contains("nux") || os.contains("mac")) {
            error.append("🐧 LINUX/MAC INSTALLATION:\n\n");
            error.append("Ubuntu/Debian:\n");
            error.append("  sudo apt update\n");
            error.append("  sudo apt install ffmpeg\n\n");
            error.append("CentOS/RHEL:\n");
            error.append("  sudo yum install ffmpeg\n\n");
            error.append("MacOS:\n");
            error.append("  brew install ffmpeg\n\n");
            error.append("Verify: ffmpeg -version\n\n");
            error.append("📝 For Docker:\n");
            error.append("   Add to Dockerfile:\n");
            error.append("   RUN apt-get update && apt-get install -y ffmpeg\n");
        }
        
        error.append("\n═══════════════════════════════════════════════════════════════\n");
        error.append("Current FFmpeg config: ");
        error.append(ffmpegPath != null && !ffmpegPath.trim().isEmpty() ? ffmpegPath : "Using system PATH");
        error.append("\n═══════════════════════════════════════════════════════════════\n");
        
        return error.toString();
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