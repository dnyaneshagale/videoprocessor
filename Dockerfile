# ── Stage 1: Build ─────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies first (only re-download when pom.xml changes)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy

# Install FFmpeg
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

# Verify FFmpeg is available
RUN ffmpeg -version && ffprobe -version

# Create non-root user for security
RUN groupadd -r appuser && useradd -r -g appuser -d /app appuser

WORKDIR /app

# Copy the built JAR from build stage
COPY --from=build /app/target/video-processor-*.jar app.jar

# Create temp directory for video processing
RUN mkdir -p /tmp/video-processing && chown -R appuser:appuser /app /tmp/video-processing

# Switch to non-root user
USER appuser

# Cloud Run sets PORT env var (default 8080)
ENV PORT=8080
EXPOSE 8080

# JVM tuning for Cloud Run containers
# - UseContainerSupport: respect container memory/CPU limits
# - MaxRAMPercentage: use 75% of container memory for heap
# - +UseG1GC: optimal for concurrent video processing
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseG1GC", \
    "-Djava.io.tmpdir=/tmp/video-processing", \
    "-Dserver.port=${PORT}", \
    "-jar", "app.jar"]
