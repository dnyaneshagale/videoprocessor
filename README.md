# Video Processing Service Documentation

**Current Date/Time:** 2025-07-23 13:32:18 UTC  
**Author:** dnyaneshagale

## 1. System Architecture

The Video Processing Service is a Spring Boot application that converts videos to HTTP Live Streaming (HLS) format with adaptive bitrate streaming capabilities. The service provides a RESTful API for submitting processing requests and monitoring status.

### Architecture Diagram

```
┌─────────────┐     ┌───────────────────────┐     ┌───────────────────┐
│ Client Apps │────▶│ Video Processing API  │────▶│ Async Processing  │
└─────────────┘     │ (Spring Boot)         │     │ Queue             │
                    └───────────────────────┘     └─────────┬─────────┘
                      ▲        │                            │
                      │        ▼                            ▼
                    ┌─┴────────────────┐           ┌────────────────┐
                    │ JWT Auth Service │           │ FFmpeg Service │
                    └──────────────────┘           └────────┬───────┘
                                                            │
                                                            ▼
                    ┌──────────────────┐           ┌────────────────┐
                    │ Cloudflare R2    │◀──────────│ File Handler   │
                    │ Storage          │           │                │
                    └──────────────────┘           └────────────────┘
```

## 2. Project Setup for Developers

### Prerequisites

- Java 17 or higher
- Maven 3.8+
- FFmpeg installed on your system
- Git
- IDE (IntelliJ IDEA, Eclipse, or VS Code)
- Cloudflare R2 account (or another S3-compatible storage)

### Clone and Setup

1. **Clone the repository**
   ```bash
   git clone git@github.com:dnyaneshagale/videoprocessor.git
   cd videoprocessor
   ```

2. **Configure Environment Variables**

   Create a `.env` file in the project root or set these environment variables:
   ```properties
   # R2 Configuration
   CLOUDFLARE_R2_ACCESS_KEY=your_access_key
   CLOUDFLARE_R2_SECRET_KEY=your_secret_key
   CLOUDFLARE_R2_ENDPOINT=https://your-account-id.r2.cloudflarestorage.com
   CLOUDFLARE_R2_BUCKET=your_bucket_name
   
   # Security Configuration
   JWT_SECRET=your_secure_jwt_secret_at_least_32_chars_long
   JWT_EXPIRATION=86400000
   PROCESSOR_PASSWORD=strong_password_for_processor_role
   ADMIN_PASSWORD=strong_password_for_admin_role
   ```

3. **Build the application**
   ```bash
   mvn clean install
   ```

4. **Run the application locally**
   ```bash
   mvn spring-boot:run
   ```

   The application will start on port 8080.

5. **Verify the setup**

   Open your browser or use curl to access:
   ```bash
   curl http://localhost:8080/api/videos/formats
   ```

   You should see a response with supported video formats.

### IDE Setup

For IntelliJ IDEA:
1. Open the project: File → Open → Select the project folder
2. Import Maven project: Right-click on pom.xml → Add as Maven Project
3. Set up Run Configuration:
    - Main class: `com.vidprocessor.VidprocessorApplication`
    - Environment variables: Set the above variables
    - JVM options: `-Xmx1g` (recommended for video processing)

## 3. Deployment Instructions

### Prerequisites

- Java 17 or higher
- FFmpeg installed on the system
- Cloudflare R2 bucket and credentials
- Minimum 2GB RAM, 2 vCPUs recommended

### Environment Configuration

The application requires the following environment variables:

```properties
# R2 Configuration
CLOUDFLARE_R2_ACCESS_KEY=your_access_key
CLOUDFLARE_R2_SECRET_KEY=your_secret_key
CLOUDFLARE_R2_ENDPOINT=https://your-account-id.r2.cloudflarestorage.com
CLOUDFLARE_R2_BUCKET=your_bucket_name

# Security Configuration
JWT_SECRET=your_secure_jwt_secret_at_least_32_chars_long
JWT_EXPIRATION=86400000
PROCESSOR_PASSWORD=strong_password_for_processor_role
ADMIN_PASSWORD=strong_password_for_admin_role

# Optional Logging Configuration
LOGGING_LEVEL_COM_VIDPROCESSOR=INFO
```

### Deployment Steps

1. **Build the Application**
   ```bash
   mvn clean package -DskipTests
   ```

2. **Deploy the JAR File**
   ```bash
   # Copy JAR to server
   scp target/video-processor-0.0.1-SNAPSHOT.jar user@server:/opt/vidprocessor/vidprocessor.jar
   
   # Set up environment file
   sudo nano /etc/vidprocessor/environment
   
   # Create systemd service
   sudo nano /etc/systemd/system/vidprocessor.service
   ```

3. **Configure Systemd Service**
   ```
   [Unit]
   Description=Video Processing Service
   After=network.target
   
   [Service]
   User=root
   WorkingDirectory=/opt/vidprocessor
   ExecStart=/usr/bin/java -jar /opt/vidprocessor/vidprocessor.jar
   SuccessExitStatus=143
   TimeoutStopSec=10
   Restart=on-failure
   RestartSec=5
   EnvironmentFile=/etc/vidprocessor/environment
   
   [Install]
   WantedBy=multi-user.target
   ```

4. **Start the Service**
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl enable vidprocessor
   sudo systemctl start vidprocessor
   ```

## 4. API Reference

### Authentication API

#### Login
- **Endpoint**: `POST /api/auth/login`
- **Description**: Authenticates user and returns JWT token
- **Request Body**:
  ```json
  {
    "username": "processor",
    "password": "StrongPassword123!"
  }
  ```
- **Response**:
  ```json
  {
    "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "username": "processor",
    "roles": ["ROLE_VIDEO_PROCESSOR"]
  }
  ```

### Video Processing API

#### Submit Video for Processing
- **Endpoint**: `POST /api/videos/process`
- **Auth**: JWT Bearer token with `ROLE_VIDEO_PROCESSOR` or `ROLE_ADMIN`
- **Description**: Submits a video from R2 for HLS conversion
- **Request Body**:
  ```json
  {
    "r2ObjectKey": "path/to/video.mp4"
  }
  ```
- **Response**:
  ```json
  {
    "id": "a1b2c3d4-5678-90ef",
    "r2ObjectKey": "path/to/video.mp4",
    "status": "QUEUED",
    "message": "Task queued for processing",
    "createdAt": "2025-07-23T13:14:15",
    "updatedAt": "2025-07-23T13:14:15"
  }
  ```

#### Check Processing Status
- **Endpoint**: `GET /api/videos/status/{taskId}`
- **Auth**: JWT Bearer token with `ROLE_VIDEO_PROCESSOR` or `ROLE_ADMIN`
- **Description**: Checks status of processing task
- **Response**:
  ```json
  {
    "id": "a1b2c3d4-5678-90ef",
    "r2ObjectKey": "path/to/video.mp4",
    "status": "COMPLETED",
    "message": "Video conversion completed successfully",
    "hlsManifestKey": "path/to/video_hls/master.m3u8",
    "createdAt": "2025-07-23T13:14:15",
    "updatedAt": "2025-07-23T13:20:25"
  }
  ```

#### View Queue Status (Admin Only)
- **Endpoint**: `GET /api/videos/queue`
- **Auth**: JWT Bearer token with `ROLE_ADMIN`
- **Description**: Returns all tasks in the processing queue
- **Response**: Array of task status objects

#### Get Supported Formats
- **Endpoint**: `GET /api/videos/formats`
- **Auth**: JWT Bearer token
- **Description**: Returns supported video formats
- **Response**:
  ```
  Supported video formats: MP4, MKV, AVI, MOV, WMV, FLV, WebM, M4V, 3GP, TS, MTS, M2TS, MPG, MPEG, VOB, OGV, MXF, F4V, ASF, DIVX
  ```

## 5. Monitoring and Management

### Service Control

```bash
# Check service status
sudo systemctl status vidprocessor

# View logs
sudo journalctl -u vidprocessor -f

# Restart service
sudo systemctl restart vidprocessor

# Stop service
sudo systemctl stop vidprocessor
```

### Application Logs

The application logs are written to standard output and captured by systemd. Key events that are logged include:

- Authentication attempts (success/failure)
- Video processing requests
- Processing status updates
- Error conditions and exceptions
- R2 storage operations

### Maintenance Tasks

1. **Cleaning Temporary Files**:
   The application automatically cleans up temporary files, but you can verify disk usage:
   ```bash
   df -h
   du -sh /tmp
   ```

2. **Updating the Application**:
   ```bash
   # Stop service
   sudo systemctl stop vidprocessor
   
   # Back up current JAR
   cp /opt/vidprocessor/vidprocessor.jar /opt/vidprocessor/vidprocessor.jar.bak
   
   # Deploy new JAR
   cp new-video-processor.jar /opt/vidprocessor/vidprocessor.jar
   
   # Start service
   sudo systemctl start vidprocessor
   ```

## 6. Technical Details

### Video Processing Workflow

1. **Authentication**: Client authenticates and receives JWT token
2. **Submit Request**: Client submits video for processing with R2 object key
3. **Async Processing**:
    - Video is downloaded from R2 to temporary storage
    - FFmpeg analyzes video to determine appropriate quality levels
    - Multiple HLS variants are created (resolution/bitrate pairs)
    - Master playlist is generated
    - All files are uploaded to R2 with prefix `{original_name}_hls/`
    - Temporary files are deleted
    - Original file is deleted after successful processing
4. **Status Checking**: Client polls status endpoint until processing completes

### Adaptive Streaming Implementation

The service generates multiple quality variants based on the source video resolution:

| Profile | Resolution | Video Bitrate | Audio Bitrate |
|---------|------------|--------------|---------------|
| 1440p   | 2560×1440  | 9,000 Kbps   | 192 Kbps      |
| 1080p   | 1920×1080  | 6,000 Kbps   | 192 Kbps      |
| 720p    | 1280×720   | 3,500 Kbps   | 192 Kbps      |
| 480p    | 854×480    | 1,800 Kbps   | 128 Kbps      |
| 360p    | 640×360    | 800 Kbps     | 96 Kbps       |
| 240p    | 426×240    | 500 Kbps     | 64 Kbps       |
| 144p    | 256×144    | 300 Kbps     | 48 Kbps       |

The service intelligently selects which profiles to generate based on the source video resolution (it won't upscale).

### R2 Storage Structure

```
bucket/
├── original_videos/
│   └── video.mp4
└── video_hls/
    ├── master.m3u8
    ├── 1080p.m3u8
    ├── 1080p_000.ts
    ├── 1080p_001.ts
    ├── 720p.m3u8
    ├── 720p_000.ts
    ├── 720p_001.ts
    └── ...
```

## 7. Security Considerations

1. **Authentication**:
    - JWT tokens with configurable expiration
    - Role-based authorization
    - CSRF protection disabled (stateless API)

2. **Input Validation**:
    - R2 object keys are validated against patterns
    - File extensions are checked against supported formats
    - Path traversal attacks are prevented

3. **Resource Protection**:
    - Processing queue has limits on concurrent tasks
    - Thread pool prevents resource exhaustion

4. **Storage Security**:
    - R2 credentials should be protected
    - JWT secret should be secure and regularly rotated

## 8. Performance Optimization

The application is configured with the following performance settings:

1. **Thread Pool Configuration**:
    - Core threads: 3 (concurrent video processing tasks)
    - Max threads: 5 (peak capacity)
    - Queue capacity: 10 (pending requests)

2. **FFmpeg Settings**:
    - Preset: "medium" (balance between speed and quality)
    - HLS segment time: 10 seconds
    - Adaptive quality levels

3. **Resource Allocation**:
    - Recommended: 2+ vCPUs, 2+ GB RAM
    - Additional memory may be required for processing large videos

## 9. Troubleshooting

### Common Issues

1. **Authentication Failures**:
    - Check username/password configuration
    - Verify JWT secret is correctly set
    - Check token expiration time

2. **Processing Failures**:
    - Verify FFmpeg is installed: `ffmpeg -version`
    - Check disk space for temporary files: `df -h`
    - Verify R2 credentials and bucket permissions

3. **Performance Issues**:
    - Check CPU usage during processing: `top`
    - Monitor memory consumption: `free -m`
    - Adjust thread pool settings if needed

4. **API Connection Issues**:
    - Verify firewall allows port 8080
    - Check service is running: `systemctl status vidprocessor`

---

**Documentation Author:** dnyaneshagale  
**Last Updated:** 2025-07-23 13:32:18 UTC