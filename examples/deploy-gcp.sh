#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_DIR="$SCRIPT_DIR/full"

# Defaults
VM_NAME="smithy-ai"
ZONE="us-central1-a"
MACHINE_TYPE="e2-medium"
PROJECT=""

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Deploy Smithy-AI to a GCP VM using pre-built images.

Options:
  --name NAME           VM instance name (default: smithy-ai)
  --project PROJECT     GCP project ID (default: current gcloud project)
  --zone ZONE           GCP zone (default: us-central1-a)
  --machine-type TYPE   Machine type (default: e2-medium)
  -h, --help            Show this help

Prerequisites:
  1. gcloud CLI installed and authenticated
  2. examples/full/.env file configured (copy from .env.example)
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name)         VM_NAME="$2"; shift 2 ;;
    --project)      PROJECT="$2"; shift 2 ;;
    --zone)         ZONE="$2"; shift 2 ;;
    --machine-type) MACHINE_TYPE="$2"; shift 2 ;;
    -h|--help)      usage ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

# --- Validation ---------------------------------------------------------------

if ! command -v gcloud &>/dev/null; then
  echo "Error: gcloud CLI not found. Install it from https://cloud.google.com/sdk/docs/install" >&2
  exit 1
fi

if [[ -z "$PROJECT" ]]; then
  PROJECT="$(gcloud config get-value project 2>/dev/null || true)"
  if [[ -z "$PROJECT" ]]; then
    echo "Error: no GCP project set. Use --project or run: gcloud config set project PROJECT_ID" >&2
    exit 1
  fi
fi

if [[ ! -f "$COMPOSE_DIR/.env" ]]; then
  echo "Error: $COMPOSE_DIR/.env not found." >&2
  echo "Copy .env.example to .env and fill in your values:" >&2
  echo "  cp $COMPOSE_DIR/.env.example $COMPOSE_DIR/.env" >&2
  echo "  vi $COMPOSE_DIR/.env" >&2
  exit 1
fi

PROJECT_FLAG="--project=$PROJECT"
ZONE_FLAG="--zone=$ZONE"

echo "==> Deploying Smithy-AI"
echo "    Project:  $PROJECT"
echo "    Zone:     $ZONE"
echo "    VM:       $VM_NAME"
echo "    Machine:  $MACHINE_TYPE"
echo

# --- Firewall rule ------------------------------------------------------------

FIREWALL_RULE="smithy-webhook"
if gcloud compute firewall-rules describe "$FIREWALL_RULE" $PROJECT_FLAG &>/dev/null; then
  echo "==> Updating firewall rule '$FIREWALL_RULE'..."
  gcloud compute firewall-rules update "$FIREWALL_RULE" \
    $PROJECT_FLAG \
    --allow=tcp:80,tcp:443
else
  echo "==> Creating firewall rule '$FIREWALL_RULE' (allow tcp:80,443)..."
  gcloud compute firewall-rules create "$FIREWALL_RULE" \
    $PROJECT_FLAG \
    --allow=tcp:80,tcp:443 \
    --target-tags=smithy \
    --description="Allow HTTP/HTTPS traffic to Smithy-AI"
fi

# --- VM creation --------------------------------------------------------------

if gcloud compute instances describe "$VM_NAME" $ZONE_FLAG $PROJECT_FLAG &>/dev/null; then
  echo "==> VM '$VM_NAME' already exists"
else
  echo "==> Creating VM '$VM_NAME'..."
  gcloud compute instances create "$VM_NAME" \
    $PROJECT_FLAG \
    $ZONE_FLAG \
    --machine-type="$MACHINE_TYPE" \
    --image-family=ubuntu-2404-lts-amd64 \
    --image-project=ubuntu-os-cloud \
    --boot-disk-size=20GB \
    --tags=smithy \
    --hostname-mode=zonal \
    --metadata=startup-script='#!/bin/bash
set -e
if ! command -v docker &>/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi
'
fi

# --- Wait for VM readiness ----------------------------------------------------

echo "==> Waiting for VM to be ready..."
for i in $(seq 1 30); do
  if gcloud compute ssh "$VM_NAME" $ZONE_FLAG $PROJECT_FLAG \
    --command="docker compose version" &>/dev/null; then
    break
  fi
  if [[ $i -eq 30 ]]; then
    echo "Error: VM did not become ready within 5 minutes." >&2
    echo "SSH in manually to debug: gcloud compute ssh $VM_NAME --zone=$ZONE" >&2
    exit 1
  fi
  sleep 10
done
echo "    VM is ready"

# --- File transfer ------------------------------------------------------------

echo "==> Copying files to VM..."
gcloud compute ssh "$VM_NAME" $ZONE_FLAG $PROJECT_FLAG \
  --command="mkdir -p ~/smithy/config"

gcloud compute scp \
  "$COMPOSE_DIR/docker-compose.yml" \
  "$COMPOSE_DIR/.env" \
  "$VM_NAME:~/smithy/" \
  $ZONE_FLAG $PROJECT_FLAG

gcloud compute scp \
  "$COMPOSE_DIR/config/knowledgebase.yml" \
  "$VM_NAME:~/smithy/config/" \
  $ZONE_FLAG $PROJECT_FLAG

# --- Deploy -------------------------------------------------------------------

echo "==> Pulling images and starting services..."
gcloud compute ssh "$VM_NAME" $ZONE_FLAG $PROJECT_FLAG --command="
  cd ~/smithy &&
  sudo docker compose pull &&
  sudo docker compose up -d
"

# --- Done ---------------------------------------------------------------------

EXTERNAL_IP="$(gcloud compute instances describe "$VM_NAME" \
  $ZONE_FLAG $PROJECT_FLAG \
  --format='get(networkInterfaces[0].accessConfigs[0].natIP)')"

cat <<EOF

==> Smithy-AI deployed successfully!

    External IP:  $EXTERNAL_IP
    Webhook URLs:
      GitLab:  http://$EXTERNAL_IP:8080/webhooks/gitlab
      Forgejo: http://$EXTERNAL_IP:8080/webhooks/forgejo

    Configure your git provider webhooks to point to the appropriate URL.

    View logs:
      gcloud compute ssh $VM_NAME --zone=$ZONE --command="cd ~/smithy && sudo docker compose logs -f"

    Redeploy after config changes:
      $0 --name $VM_NAME --zone $ZONE --project $PROJECT
EOF
