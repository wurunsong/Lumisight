#!/usr/bin/env bash
set -euo pipefail

# 统一切到项目根目录，避免相对路径受当前终端目录影响。
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

# 输出容器运行状态和端口映射，便于快速确认基础设施是否拉起。
podman ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

echo ""
echo "Milvus 健康检查:"
# 健康检查失败时不终止脚本，方便继续检查 Nebula 状态。
curl -fsS http://127.0.0.1:9091/healthz || true

echo ""
echo "Nebula 端口检查 (9669):"
if command -v nc >/dev/null 2>&1; then
  # 使用 TCP 探测 GraphD 端口可达性，快速判断服务是否监听。
  nc -z 127.0.0.1 9669 && echo "Nebula 9669 端口可达" || echo "Nebula 9669 端口不可达"
else
  echo "未安装 nc，跳过端口探测"
fi
