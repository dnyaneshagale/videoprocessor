package com.vidprocessor.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.http.apache.ApacheHttpClient;

import java.time.Duration;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@Slf4j
public class CloudflareR2Service {

    private final S3Client s3Client;
    private final String bucketName;

    @PostConstruct
    void validateConfig() {
        if (bucketName == null || bucketName.isBlank() || bucketName.startsWith("${")) {
            throw new IllegalStateException(
                    "Cloudflare R2 environment variables are not set. "
                    + "Required: CLOUDFLARE_R2_ACCESS_KEY, CLOUDFLARE_R2_SECRET_KEY, CLOUDFLARE_R2_ENDPOINT, CLOUDFLARE_R2_BUCKET");
        }
    }

    /**
     * Constructor with explicit configuration for Cloudflare R2
     */
    public CloudflareR2Service(
            @Value("${cloudflare.r2.access-key}") String accessKey,
            @Value("${cloudflare.r2.secret-key}") String secretKey,
            @Value("${cloudflare.r2.bucket}") String bucketName,
            @Value("${cloudflare.r2.endpoint}") String endpoint) {

        this.bucketName = bucketName;

        // Configure S3Client for Cloudflare R2 with optimized Apache HTTP Client
        // Connection pooling for high concurrency (500 max connections)
        this.s3Client = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto")) // R2 requires a region, but uses "auto"
                .httpClientBuilder(ApacheHttpClient.builder()
                        .maxConnections(500)
                        .connectionTimeout(Duration.ofSeconds(30))
                        .socketTimeout(Duration.ofSeconds(60))
                        .connectionAcquisitionTimeout(Duration.ofSeconds(10))
                        .tcpKeepAlive(true))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true) // Required for R2
                        .checksumValidationEnabled(false) // Improves compatibility
                        .build())
                .build();

        log.info("Initialized Cloudflare R2 client for bucket: {} with connection pool (max: 500)", bucketName);
    }

    /**
     * Downloads a file from Cloudflare R2
     */
    public File downloadFile(String objectKey) throws IOException {
        log.info("Downloading file from R2: {}", objectKey);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();

        // Create a temp file with the same extension
        String extension = FilenameUtils.getExtension(objectKey);
        File tempFile = File.createTempFile("r2-download-", "." + extension);

        // Download the file
        try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getObjectRequest);
             FileOutputStream outputStream = new FileOutputStream(tempFile)) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = s3Object.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
        } catch (NoSuchKeyException e) {
            tempFile.delete();
            throw new IllegalArgumentException(
                "File '" + objectKey + "' not found in R2 bucket. Please verify the file path and ensure it's uploaded.",
                e
            );
        }

        log.info("File downloaded to: {}", tempFile.getAbsolutePath());
        return tempFile;
    }

    /**
     * Upload a file to Cloudflare R2
     */
    public void uploadFile(File file, String objectKey) {
        log.info("Uploading file to R2: {}", objectKey);

        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("File does not exist: " + (file != null ? file.getAbsolutePath() : "null"));
        }

        if (!file.canRead()) {
            throw new RuntimeException("Cannot read file: " + file.getAbsolutePath());
        }

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .build();

        s3Client.putObject(putObjectRequest, RequestBody.fromFile(file));
        log.info("File uploaded successfully: {}", objectKey);
    }

    /**
     * Upload a directory of files to Cloudflare R2
     */
    public void uploadDirectory(Path directory, String prefix) throws IOException {
        log.info("Uploading directory to R2: {} -> {}", directory, prefix);

        if (directory == null || !Files.exists(directory)) {
            throw new IllegalArgumentException("Directory does not exist: " + directory);
        }

        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Path is not a directory: " + directory);
        }

        Files.walk(directory)
                .filter(Files::isRegularFile)
                .forEach(filePath -> {
                    String relativePath = directory.relativize(filePath).toString();
                    String objectKey = prefix + "/" + relativePath.replace("\\", "/");

                    PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(objectKey)
                            .build();

                    s3Client.putObject(putObjectRequest, RequestBody.fromFile(filePath.toFile()));
                    log.info("Uploaded: {}", objectKey);
                });

        log.info("Directory uploaded successfully: {}", prefix);
    }

    /**
     * Delete a file from Cloudflare R2
     * @param objectKey The key of the file to delete
     */
    public void deleteFile(String objectKey) {
        log.info("Deleting original video file from R2 to save storage space: {}", objectKey);

        try {
            DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();

            s3Client.deleteObject(deleteObjectRequest);

            log.info("Original file deleted successfully: {}", objectKey);
        } catch (Exception e) {
            log.error("Failed to delete original video file: {}", objectKey, e);
            // We don't throw the exception as this is a cleanup operation
            // that shouldn't affect the success status of the video processing
        }
    }
}