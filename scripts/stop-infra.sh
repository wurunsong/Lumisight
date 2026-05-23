#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

podman compose -f infra/podman-compose.yml down

echo "Lumisight 基础设施已停止。"
