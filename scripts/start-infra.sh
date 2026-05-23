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

# Nebula 首次启动需要注册 storaged host，否则可能出现 Host not enough。
NEBULA_CONSOLE_IMAGE="m.daocloud.io/docker.io/vesoft/nebula-console:v3.8.0"
NETWORK_NAME="infra_lumisight-net"

# 等待 graphd 可连接后再执行初始化。
for i in {1..20}; do
  if podman run --rm --network "$NETWORK_NAME" "$NEBULA_CONSOLE_IMAGE" \
    -addr nebula-graphd -port 9669 -u root -p nebula -e 'SHOW HOSTS;' >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

podman run --rm --network "$NETWORK_NAME" "$NEBULA_CONSOLE_IMAGE" \
  -addr nebula-graphd -port 9669 -u root -p nebula \
  -e 'ADD HOSTS "nebula-storaged":9779; CREATE SPACE IF NOT EXISTS lumisight_kg(partition_num=10, replica_factor=1, vid_type=FIXED_STRING(256));' >/dev/null

echo "Lumisight 基础设施启动完成。"
# Nebula Graph 服务端口，用于后续图数据库连接。
echo "- Nebula GraphD: 127.0.0.1:9669"
# Milvus 向量检索 gRPC 端口。
echo "- Milvus gRPC: 127.0.0.1:19530"
# Milvus 健康检查接口。
echo "- Milvus Health: 127.0.0.1:9091/healthz"
