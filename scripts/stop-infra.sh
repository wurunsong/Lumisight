#!/usr/bin/env bash
set -euo pipefail

# 统一切到项目根目录，确保 compose 文件路径稳定。
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

# 停止并移除本项目 compose 编排创建的容器与网络。
podman compose -f infra/podman-compose.yml down

echo "Lumisight 基础设施已停止。"
