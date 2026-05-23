#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

mkdir -p infra/data/nebula/meta infra/data/nebula/storage
mkdir -p infra/data/milvus/etcd infra/data/milvus/minio infra/data/milvus/milvus

podman compose -f infra/podman-compose.yml up -d

echo "Lumisight 基础设施启动完成。"
echo "- Nebula GraphD: 127.0.0.1:9669"
echo "- Milvus gRPC: 127.0.0.1:19530"
echo "- Milvus Health: 127.0.0.1:9091/healthz"
