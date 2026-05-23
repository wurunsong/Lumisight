#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

podman ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

echo ""
echo "Milvus 健康检查:"
curl -fsS http://127.0.0.1:9091/healthz || true

echo ""
echo "Nebula 端口检查 (9669):"
if command -v nc >/dev/null 2>&1; then
  nc -z 127.0.0.1 9669 && echo "Nebula 9669 端口可达" || echo "Nebula 9669 端口不可达"
else
  echo "未安装 nc，跳过端口探测"
fi
