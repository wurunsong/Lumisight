# Lumisight

Lumisight 是一个面向 Java 工程场景的 Agent 项目，核心能力包括代码问答、风险分析、影响面分析。

## 环境要求

- JDK 21
- Maven 3.9+

## 第一步：本地启动外挂知识库（Podman）

> 说明：正式开发 Agent 之前，需要先构建并启动 NebulaGraph + Milvus。
> 当前 `infra/podman-compose.yml` 已使用国内镜像代理前缀（`m.daocloud.io`），用于提升拉取成功率。

### 1. 启动 Podman machine（如果尚未启动）

```bash
podman machine init
podman machine start
```

如果你已经初始化过，只执行：

```bash
podman machine start
```

### 2. 启动基础设施

```bash
./scripts/start-infra.sh
```

### 3. 检查状态

```bash
./scripts/check-infra.sh
```

### 4. 停止基础设施

```bash
./scripts/stop-infra.sh
```

## 端口说明

- Nebula GraphD: `9669`
- Nebula MetaD: `9559`
- Nebula StorageD: `9779`
- Milvus gRPC: `19530`
- Milvus health: `9091`
- MinIO API: `9000`
- MinIO Console: `9001`
- Etcd: `2379`

## 目录说明

- `infra/podman-compose.yml`: Nebula + Milvus 编排文件
- `scripts/start-infra.sh`: 一键启动
- `scripts/check-infra.sh`: 状态检查
- `scripts/stop-infra.sh`: 一键停止

## 知识图谱离线构建（JavaParser + 增量）

入口类：`com.lumisight.tools.kg.cli.KnowledgeGraphBuildMain`

示例：

```bash
mvn -pl lumisight-tools -am compile
mvn -pl lumisight-tools -am exec:java \
  -Dexec.mainClass="com.lumisight.tools.kg.cli.KnowledgeGraphBuildMain" \
  -Dexec.args="/path/to/your/java-repo /path/to/output/snapshot.json"
```

说明：
- 首次运行会全量解析所有 `.java` 文件。
- 后续运行会根据文件哈希仅解析新增/变更文件，并清理删除文件对应的节点和边。
- 当前图谱节点：`module/package/class/method`
- 当前图谱边：`module->package`、`package->class`、`class->method`、`method->method(calls)`
