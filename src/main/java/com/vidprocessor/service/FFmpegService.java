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
import java.util.List;

@Service
@Slf4j
public class FFmpegService {

    /**
     * Converts a video file to HLS format with multiple quality levels
     * - 720p: ~3-4 Mbps bitrate
     * - 480p: ~1.5-2 Mbps bitrate
     * - 360p: ~800 Kbps bitrate
     */
    public Path convertToHls(File inputFile, String outputName) throws IOException {
        log.info("Converting video to HLS: {}", inputFile.getName());

        // Verify FFmpeg is installed
        if (!isFFmpegAvailable()) {
            throw new IOException("FFmpeg is not available. Please install FFmpeg and make sure it's on the PATH.");
        }

        // Create a temporary directory for the output
        Path outputDir = Files.createTempDirectory("hls-output-");
        log.info("Output directory created: {}", outputDir);

        try {
            // Create each quality variant separately for better error isolation
            create720pVariant(inputFile, outputDir);
            create480pVariant(inputFile, outputDir);
            create360pVariant(inputFile, outputDir);

            // Create a master playlist that references all quality variants
            createMasterPlaylist(outputDir);

            log.info("Multi-quality HLS conversion completed for: {}", outputName);
            return outputDir;
        } catch (Exception e) {
            log.error("FFmpeg conversion failed", e);
            throw e;
        }
    }

    /**
     * Creates the high quality (720p) variant
     */
    private void create720pVariant(File inputFile, Path outputDir) throws IOException {
        log.info("Creating 720p variant (3.5 Mbps)");
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-vf");
        command.add("scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2");
        command.add("-b:v");
        command.add("3500k");
        command.add("-maxrate");
        command.add("3850k");
        command.add("-bufsize");
        command.add("7000k");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("192k");
        command.add("-hls_time");
        command.add("10");
        command.add("-hls_list_size");
        command.add("0");
        command.add("-hls_segment_filename");
        command.add(outputDir.resolve("720p_%03d.ts").toString());
        command.add(outputDir.resolve("720p.m3u8").toString());

        executeCommand(command);
    }

    /**
     * Creates the medium quality (480p) variant
     */
    private void create480pVariant(File inputFile, Path outputDir) throws IOException {
        log.info("Creating 480p variant (1.8 Mbps)");
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-vf");
        command.add("scale=854:480:force_original_aspect_ratio=decrease,pad=854:480:(ow-iw)/2:(oh-ih)/2");
        command.add("-b:v");
        command.add("1800k");
        command.add("-maxrate");
        command.add("1980k");
        command.add("-bufsize");
        command.add("3600k");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("128k");
        command.add("-hls_time");
        command.add("10");
        command.add("-hls_list_size");
        command.add("0");
        command.add("-hls_segment_filename");
        command.add(outputDir.resolve("480p_%03d.ts").toString());
        command.add(outputDir.resolve("480p.m3u8").toString());

        executeCommand(command);
    }

    /**
     * Creates the low quality (360p) variant
     */
    private void create360pVariant(File inputFile, Path outputDir) throws IOException {
        log.info("Creating 360p variant (800 Kbps)");
        List<String> command = new ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(inputFile.getAbsolutePath());
        command.add("-c:v");
        command.add("libx264");
        command.add("-preset");
        command.add("medium");
        command.add("-vf");
        command.add("scale=640:360:force_original_aspect_ratio=decrease,pad=640:360:(ow-iw)/2:(oh-ih)/2");
        command.add("-b:v");
        command.add("800k");
        command.add("-maxrate");
        command.add("880k");
        command.add("-bufsize");
        command.add("1600k");
        command.add("-c:a");
        command.add("aac");
        command.add("-b:a");
        command.add("96k");
        command.add("-hls_time");
        command.add("10");
        command.add("-hls_list_size");
        command.add("0");
        command.add("-hls_segment_filename");
        command.add(outputDir.resolve("360p_%03d.ts").toString());
        command.add(outputDir.resolve("360p.m3u8").toString());

        executeCommand(command);
    }

    /**
     * Creates a master playlist that references all quality variants
     */
    private void createMasterPlaylist(Path outputDir) throws IOException {
        log.info("Creating master playlist with 3 quality levels");
        StringBuilder masterPlaylist = new StringBuilder();
        masterPlaylist.append("#EXTM3U\n");
        masterPlaylist.append("#EXT-X-VERSION:3\n");

        // Add 720p variant
        masterPlaylist.append("#EXT-X-STREAM-INF:BANDWIDTH=3692000,RESOLUTION=1280x720\n");
        masterPlaylist.append("720p.m3u8\n");

        // Add 480p variant
        masterPlaylist.append("#EXT-X-STREAM-INF:BANDWIDTH=1928000,RESOLUTION=854x480\n");
        masterPlaylist.append("480p.m3u8\n");

        // Add 360p variant
        masterPlaylist.append("#EXT-X-STREAM-INF:BANDWIDTH=896000,RESOLUTION=640x360\n");
        masterPlaylist.append("360p.m3u8\n");

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
}