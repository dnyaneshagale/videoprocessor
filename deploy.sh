#!/bin/bash
# ═══════════════════════════════════════════════════════════════
#  Deploy Video Processor to Google Cloud Run
# ═══════════════════════════════════════════════════════════════
#
# Prerequisites:
#   1. gcloud CLI installed: https://cloud.google.com/sdk/docs/install
#   2. Authenticated: gcloud auth login
#   3. Project set: gcloud config set project YOUR_PROJECT_ID
#
# Usage:
#   chmod +x deploy.sh
#   ./deploy.sh
#
# ═══════════════════════════════════════════════════════════════

set -euo pipefail

# ── Configuration ─────────────────────────────────────────────
# Change these to match your setup
PROJECT_ID=$(gcloud config get-value project)
REGION="asia-south1"                    # Mumbai (closest to India)
SERVICE_NAME="video-processor"
IMAGE_NAME="gcr.io/${PROJECT_ID}/${SERVICE_NAME}"

# Cloud Run instance sizing
CPU="4"                                 # 4 vCPUs for FFmpeg encoding
MEMORY="8Gi"                            # 8 GB RAM
MAX_INSTANCES="10"                      # Max auto-scale instances
MIN_INSTANCES="0"                       # Scale to zero when idle
CONCURRENCY="4"                         # Requests per instance (= CPU count)
TIMEOUT="3600"                          # 60 min max request timeout

echo "═══════════════════════════════════════════════════════════════"
echo "  Deploying ${SERVICE_NAME} to Cloud Run"
echo "  Project : ${PROJECT_ID}"
echo "  Region  : ${REGION}"
echo "  Image   : ${IMAGE_NAME}"
echo "═══════════════════════════════════════════════════════════════"

# ── Step 1: Enable required APIs ──────────────────────────────
echo ""
echo "▸ Enabling required GCP APIs..."
gcloud services enable \
    run.googleapis.com \
    cloudbuild.googleapis.com \
    containerregistry.googleapis.com \
    --quiet

# ── Step 2: Build & push Docker image via Cloud Build ─────────
echo ""
echo "▸ Building container image via Cloud Build..."
gcloud builds submit \
    --tag "${IMAGE_NAME}" \
    --timeout=1800 \
    --quiet

# ── Step 3: Deploy to Cloud Run ──────────────────────────────
echo ""
echo "▸ Deploying to Cloud Run..."
echo "  ⚠  You must set environment variables after first deploy!"
echo ""

gcloud run deploy "${SERVICE_NAME}" \
    --image "${IMAGE_NAME}" \
    --region "${REGION}" \
    --platform managed \
    --cpu "${CPU}" \
    --memory "${MEMORY}" \
    --timeout "${TIMEOUT}" \
    --concurrency "${CONCURRENCY}" \
    --min-instances "${MIN_INSTANCES}" \
    --max-instances "${MAX_INSTANCES}" \
    --no-cpu-throttling \
    --cpu-boost \
    --no-allow-unauthenticated \
    --set-env-vars "JAVA_TOOL_OPTIONS=-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0" \
    --quiet

# ── Step 4: Print service URL ─────────────────────────────────
SERVICE_URL=$(gcloud run services describe "${SERVICE_NAME}" \
    --region "${REGION}" \
    --format "value(status.url)")

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "  ✅ Deployment complete!"
echo "  URL: ${SERVICE_URL}"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "  ⚠  REQUIRED: Set your secrets as environment variables:"
echo ""
echo "  gcloud run services update ${SERVICE_NAME} \\"
echo "      --region ${REGION} \\"
echo "      --set-env-vars \"\\"
echo "  CLOUDFLARE_R2_ACCESS_KEY=your-access-key,\\"
echo "  CLOUDFLARE_R2_SECRET_KEY=your-secret-key,\\"
echo "  CLOUDFLARE_R2_ENDPOINT=https://your-account-id.r2.cloudflarestorage.com,\\"
echo "  CLOUDFLARE_R2_BUCKET=your-bucket-name,\\"
echo "  API_KEY=your-api-key-min-32-chars\""
echo ""
echo "  Or use Secret Manager (recommended for production):"
echo ""
echo "  gcloud run services update ${SERVICE_NAME} \\"
echo "      --region ${REGION} \\"
echo "      --set-secrets \"\\"
echo "  API_KEY=api-key:latest,\\"
echo "  CLOUDFLARE_R2_ACCESS_KEY=r2-access-key:latest,\\"
echo "  CLOUDFLARE_R2_SECRET_KEY=r2-secret-key:latest\""
echo ""
echo "  Test health: curl ${SERVICE_URL}/api/health"
echo "═══════════════════════════════════════════════════════════════"
