# Video Processing Service Documentation

**Author:** dnyaneshagale

## 1. System Architecture

The Video Processing Service is a Spring Boot application that converts videos to HTTP Live Streaming (HLS) format with adaptive bitrate streaming capabilities. After conversion, it updates the video entry in Firebase Realtime Database with HLS metadata.

### Architecture Diagram

```
┌─────────────┐     ┌───────────────────────┐     ┌───────────────────┐
│ Client Apps │────▶│ Video Processing API  │────▶│ Async Processing  │
└─────────────┘     │ (Spring Boot)         │     │ Queue             │
                    └───────────────────────┘     └─────────┬─────────┘
                      ▲        │                            │
                      │        ▼                            ▼
                    ┌─┴────────────────┐           ┌────────────────┐
                    │ API Key Auth     │           │ FFmpeg Service │
                    └──────────────────┘           └────────┬───────┘
                                                            │
                                                            ▼
                    ┌──────────────────┐           ┌────────────────┐
                    │ Cloudflare R2    │◀──────────│ File Handler   │
                    │ Storage          │           │                │
                    └──────────────────┘           └────────┬───────┘
                                                            │
                                                            ▼
                                                   ┌────────────────┐
                                                   │ Firebase RTDB  │
                                                   │ (HLS status)   │
                                                   └────────────────┘
```

## 2. Project Setup for Developers

### Prerequisites

- Java 17 or higher
- Maven 3.8+
- FFmpeg installed on your system
- Git
- IDE (IntelliJ IDEA, Eclipse, or VS Code)
- Cloudflare R2 account (or another S3-compatible storage)
- Firebase Realtime Database

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
   API_KEY=your_api_key_at_least_32_chars

   # Firebase Configuration
   FIREBASE_DATABASE_URL=https://your-project.firebaseio.com
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
   ```bash
   curl http://localhost:8080/api/videos/formats \
     -H "X-API-Key: your_api_key"
   ```

### IDE Setup

For IntelliJ IDEA:
1. Open the project: File → Open → Select the project folder
2. Import Maven project: Right-click on pom.xml → Add as Maven Project
3. Set up Run Configuration:
    - Main class: `com.vidprocessor.VidprocessorApplication`
    - Environment variables: Set the above variables
    - JVM options: `-Xmx1g` (recommended for video processing)

## 3. Deployment Instructions

### Docker (Recommended)

```bash
# Build and deploy to Google Cloud Run
chmod +x deploy.sh
./deploy.sh
```

Then set environment variables on Cloud Run:
```bash
gcloud run services update video-processor \
    --region asia-south1 \
    --set-env-vars "\
CLOUDFLARE_R2_ACCESS_KEY=your-access-key,\
CLOUDFLARE_R2_SECRET_KEY=your-secret-key,\
CLOUDFLARE_R2_ENDPOINT=https://your-account-id.r2.cloudflarestorage.com,\
CLOUDFLARE_R2_BUCKET=your-bucket-name,\
API_KEY=your-api-key-min-32-chars,\
FIREBASE_DATABASE_URL=https://your-project.firebaseio.com"
```

### Manual Deployment

1. **Build the Application**
   ```bash
   mvn clean package -DskipTests
   ```

2. **Run the JAR**
   ```bash
   java -XX:+UseG1GC -Xmx1g -jar target/video-processor-0.0.1-SNAPSHOT.jar
   ```

### Environment Variables Reference

| Variable | Required | Description |
|----------|----------|-------------|
| `CLOUDFLARE_R2_ACCESS_KEY` | Yes | R2 access key |
| `CLOUDFLARE_R2_SECRET_KEY` | Yes | R2 secret key |
| `CLOUDFLARE_R2_ENDPOINT` | Yes | R2 endpoint URL |
| `CLOUDFLARE_R2_BUCKET` | Yes | R2 bucket name |
| `API_KEY` | Yes | API key for authentication (min 32 chars) |
| `FIREBASE_DATABASE_URL` | Yes | Firebase RTDB URL |
| `PORT` | No | Server port (default: 8080) |
| `FFMPEG_PATH` | No | Custom FFmpeg binary path |
| `FFPROBE_PATH` | No | Custom FFprobe binary path |

## 4. API Reference

### Authentication

All `/api/videos/**` endpoints require the `X-API-Key` header:
```
X-API-Key: your_api_key
```

Health endpoints (`/api/health`, `/api/ping`) are public.

Rate limiting: 30 requests/minute per IP (configurable).

### Endpoints

#### Health Check
- `GET /api/health` — Returns service status (public)
- `GET /api/ping` — Returns `pong` (public)

#### Submit Video for Processing
- **Endpoint**: `POST /api/videos/process`
- **Request Body**:
  ```json
  {
    "r2ObjectKey": "video/video_abc-123.mp4"
  }
  ```
- **Response** (202 Accepted):
  ```json
  {
    "id": "a1b2c3d4-5678-90ef",
    "r2ObjectKey": "video/video_abc-123.mp4",
    "status": "QUEUED",
    "message": "Task queued — processing slot available, starting immediately",
    "queuePosition": 0,
    "createdAt": "2026-02-16T12:00:00",
    "updatedAt": "2026-02-16T12:00:00"
  }
  ```

#### Check Processing Status by Task ID
- **Endpoint**: `GET /api/videos/status/{taskId}`
- **Response**:
  ```json
  {
    "id": "a1b2c3d4-5678-90ef",
    "r2ObjectKey": "video/video_abc-123.mp4",
    "status": "COMPLETED",
    "message": "Video conversion completed successfully",
    "hlsManifestKey": "video/video_abc-123_hls/master.m3u8",
    "queuePosition": 0,
    "createdAt": "2026-02-16T12:00:00",
    "updatedAt": "2026-02-16T12:05:30"
  }
  ```

#### Check Processing Status by R2 Key
- **Endpoint**: `GET /api/videos/status?r2ObjectKey=video/video_abc-123.mp4`

#### View Queue
- **Endpoint**: `GET /api/videos/queue`
- **Response**: Array of all task status objects

#### Get Supported Formats
- **Endpoint**: `GET /api/videos/formats`
- **Response**:
  ```
  Supported video formats: MP4, MKV, AVI, MOV, WMV, FLV, WebM, M4V, 3GP, TS, MTS, M2TS, MPG, MPEG, VOB, OGV, MXF, F4V, ASF, DIVX
  ```

## 5. Technical Details

### Video Processing Workflow

1. **Submit Request**: Client sends R2 object key via POST
2. **Async Processing**:
    - Video is downloaded from R2 to temporary storage
    - FFmpeg analyzes video to determine appropriate quality levels
    - Multiple HLS variants are created based on source resolution
    - Master playlist is generated
    - All HLS files are uploaded to R2 with prefix `{original_name}_hls/`
    - Temporary files are cleaned up
    - Original file is deleted after successful processing
3. **Firebase Update**: On completion, the existing video entry in Firebase RTDB is patched with:
    - `isHLSConverted: true` (or `false` on failure)
    - `hlsConversionTimeSec: <elapsed seconds>`
4. **Status Checking**: Client polls status endpoint until processing completes

### Adaptive Streaming Quality Profiles

| Profile | Resolution | Video Bitrate | Audio Bitrate | CRF |
|---------|------------|---------------|---------------|-----|
| 1440p   | 2560×1440  | 14,000 Kbps   | 192 Kbps      | 20  |
| 1080p   | 1920×1080  | 8,000 Kbps    | 192 Kbps      | 21  |
| 720p    | 1280×720   | 5,000 Kbps    | 192 Kbps      | 23  |
| 480p    | 854×480    | 2,500 Kbps    | 128 Kbps      | 24  |
| 360p    | 640×360    | 1,200 Kbps    | 128 Kbps      | 25  |
| 240p    | 426×240    | 700 Kbps      | 96 Kbps       | 26  |

Profiles are selected automatically based on source video resolution (no upscaling).

### Firebase RTDB Integration

Videos already exist at `/videos/{video_id}` in Firebase. After HLS conversion, two fields are patched onto the existing entry:

```json
{
  "isHLSConverted": true,
  "hlsConversionTimeSec": 145
}
```

The `video_id` is extracted from the R2 object key (e.g. `video/video_abc-123.mp4` → `video_abc-123`).

### R2 Storage Structure

```
bucket/
├── video/
│   └── video_abc-123.mp4          (deleted after successful conversion)
└── video/video_abc-123_hls/
    ├── master.m3u8
    ├── 1080p.m3u8
    ├── 1080p_000.ts
    ├── 1080p_001.ts
    ├── 720p.m3u8
    ├── 720p_000.ts
    └── ...
```

### Thread Pool & Concurrency

- Auto-detects CPU cores at startup
- Core pool = CPU cores (1 FFmpeg process per core)
- Max pool = cores × 2 (I/O overlap)
- Queue capacity = max × 10 (burst traffic)
- Override via `video.processing.max-concurrent-tasks` property

## 6. Security

- **API Key Authentication**: All `/api/videos/**` endpoints require `X-API-Key` header
- **Rate Limiting**: 30 requests/minute per IP (sliding window)
- **Input Validation**: R2 keys validated against path traversal, supported formats checked
- **Stateless**: No sessions, CSRF disabled
- **Security Headers**: HSTS, X-Frame-Options: DENY, X-Content-Type-Options: nosniff

## 7. Troubleshooting

| Issue | Solution |
|-------|----------|
| FFmpeg not found | Install FFmpeg or set `FFMPEG_PATH` / `FFPROBE_PATH` |
| PATCH fails to Firebase | Ensure `httpclient5` dependency is present (required for HTTP PATCH) |
| API returns 401 | Check `X-API-Key` header matches the configured `API_KEY` |
| API returns 429 | Rate limit exceeded — wait and retry |
| Processing fails | Check FFmpeg is installed, disk space is available, R2 credentials are valid |
| Firebase update fails | Verify `FIREBASE_DATABASE_URL` and that the video entry exists at `/videos/{video_id}` |

---

**Author:** dnyaneshagale