#!/usr/bin/env bash
set -euo pipefail

# 计算项目根目录，确保在任意路径执行脚本都能正确定位 compose 文件。
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

# 预创建持久化目录，避免容器首次启动时因挂载路径不存在而失败。
mkdir -p infra/data/nebula/meta infra/data/nebula/storage
mkdir -p infra/data/milvus/etcd infra/data/milvus/minio infra/data/milvus/milvus

# 后台启动 Nebula + Milvus 基础设施。
podman compose -f infra/podman-compose.yml up -d

echo "Lumisight 基础设施启动完成。"
# Nebula Graph 服务端口，用于后续图数据库连接。
echo "- Nebula GraphD: 127.0.0.1:9669"
# Milvus 向量检索 gRPC 端口。
echo "- Milvus gRPC: 127.0.0.1:19530"
# Milvus 健康检查接口。
echo "- Milvus Health: 127.0.0.1:9091/healthz"
