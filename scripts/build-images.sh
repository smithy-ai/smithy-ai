#!/usr/bin/env bash
# Build the task container images.
#
# Usage:
#   ./scripts/build-images.sh
#
# Builds the base image first, then the project-specific layer on top.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "==> Building claude-task-base image..."
docker build -t claude-task-base:latest "$PROJECT_ROOT/images/claude-task-base"

echo "==> Building claude-task-default image..."
docker build -t claude-task-default:latest "$PROJECT_ROOT/images/claude-task-default"

echo "==> Done. Images built:"
docker images --filter "reference=claude-task*" --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}"
