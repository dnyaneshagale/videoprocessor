package com.vidprocessor.service;

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
import software.amazon.awssdk.services.s3.S3Configuration;

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

    /**
     * Constructor with explicit configuration for Cloudflare R2
     */
    public CloudflareR2Service(
            @Value("${cloudflare.r2.access-key}") String accessKey,
            @Value("${cloudflare.r2.secret-key}") String secretKey,
            @Value("${cloudflare.r2.bucket}") String bucketName,
            @Value("${cloudflare.r2.endpoint}") String endpoint) {

        this.bucketName = bucketName;

        // Configure S3Client for Cloudflare R2
        this.s3Client = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto")) // R2 requires a region, but uses "auto"
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true) // Required for R2
                        .checksumValidationEnabled(false) // Improves compatibility
                        .build())
                .build();

        log.info("Initialized Cloudflare R2 client for bucket: {}", bucketName);
    }

    /**
     * Download a file from Cloudflare R2
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
        }

        log.info("File downloaded to: {}", tempFile.getAbsolutePath());
        return tempFile;
    }

    /**
     * Upload a file to Cloudflare R2
     */
    public void uploadFile(File file, String objectKey) {
        log.info("Uploading file to R2: {}", objectKey);

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
                    log.debug("Uploaded: {}", objectKey);
                });

        log.info("Directory uploaded successfully");
    }
}