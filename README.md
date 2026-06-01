# Lumisight

Lumisight 是一个面向 Java 工程场景的 Agent 项目，核心能力包括代码问答、风险分析、影响面分析。

## 环境要求

- JDK 21
- Maven 3.9+

## 启动前环境变量

启动 `lumisight-api` 前请先配置以下两个 Key（不要写入仓库文件）：

- `CHAT_API_KEY`：聊天模型 Key（DeepSeek）
- `EMBEDDING_API_KEY`：向量 Embedding Key（阿里云 DashScope）

示例：

```bash
export CHAT_API_KEY="<your-chat-key>"
export EMBEDDING_API_KEY="<your-embedding-key>"
```

## Agent 与图谱/向量隔离开关（本地调试推荐）

- 默认已隔离：`lumisight-core.yml` 中 `lumisight.kg.enabled=false`、`lumisight.vector.enabled=false`。
- 无 Nebula/Milvus 环境时可直接启动 Agent 主链路进行调试。
- 如需启用图谱/向量能力，再将对应开关改为 `true`：
  - `lumisight.kg.enabled=true`
  - `lumisight.vector.enabled=true`
- Milvus 自动配置已在离线模式下排除，避免无向量库环境启动超时。

## 第 1 步：本地启动外挂知识库（Podman）

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
- `lumisight-core/src/main/java/com/lumisight/core/agent`: 核心 Agent 主流程与 multiagent 编排骨架
- `lumisight-core/src/main/java/com/lumisight/core/tool`: 统一工具层（RAG/LOCAL/LSP/BUILD/GIT/MCP）
- `lumisight-core/src/main/java/com/lumisight/core/model`: Agent 共享模型
- `lumisight-core/src/main/java/com/lumisight/core/support`: 提示词、校验、会话、策略支撑

## 第 2 步：知识图谱离线构建（JavaParser + 增量）

入口类：`com.lumisight.tools.kg.cli.KnowledgeGraphBuildMain`

示例：

```bash
mvn -pl lumisight-tools -am compile
mvn -pl lumisight-tools -am exec:java \
  -Dexec.mainClass="com.lumisight.tools.kg.cli.KnowledgeGraphBuildMain" \
  -Dexec.args="/path/to/your/java-repo"
```

说明：
- 首次运行会全量解析所有 `.java` 文件。
- 后续运行会根据文件哈希仅解析新增/变更文件，并清理删除文件对应的节点和边。
- 当前图谱节点：`module/package/class/method`
- 当前图谱边：`module->package`、`package->class`、`class->method`、`method->method(calls)`
- 解析结果直接写入 Nebula（不再输出本地快照文件）。

## Nebula Space 约定

- Space 命名规则：`kg_工程名`（示例：工程目录名 `rpc` -> Space `kg_rpc`）。
- 程序会按该规则自动派生并初始化 Space。
- 首次排障时也可手工创建：

```bash
podman run --rm --network infra_lumisight-net m.daocloud.io/docker.io/vesoft/nebula-console:v3.8.0 \
  -addr nebula-graphd -port 9669 -u root -p nebula \
  -e 'CREATE SPACE IF NOT EXISTS kg_rpc(partition_num=10, replica_factor=1, vid_type=FIXED_STRING(256)); SHOW SPACES;'
```

## 第 3 步：启动 API 并调用能力接口

先启动服务（示例）：

```bash
mvn -pl lumisight-api -am spring-boot:run
```

接口概览（`/api/lumisight`）：

### KG 接口

- 注意：仅当 `lumisight.kg.enabled=true` 时开放。
- `POST /api/lumisight/kg/build`：触发图谱构建（全量/增量）。
- `POST /api/lumisight/kg/view`：图谱展示查询。
- `POST /api/lumisight/kg/query`：图谱关系查询。
- 单节点查询接口：用于按节点维度快速检索图谱信息。

### Vector 接口

- 注意：仅当 `lumisight.vector.enabled=true` 时开放。
- `POST /api/lumisight/vector/code-chunk/ingest`：代码切片向量入库。
- `POST /api/lumisight/vector/symbol-doc/ingest`：符号文档向量入库。
- 已支持自动切片、按仓库方法解析入库、基于 `git diff` 的增量入库。

### Agent 接口（SSE 流式）

- `POST /api/lumisight/agent/stream`：以 `text/event-stream` 持续返回 Agent 事件流。
- 请求字段：
  - `taskType`：`CODE_EXPLAIN` / `BUG_FIX`
  - `repoRoot`：仓库根路径
  - `question`：用户问题
  - `includeRagContext`：是否启用向量工具
  - `contextLimit`：上下文上限
  - `runMode`：`NORMAL` / `PLAN`（`PLAN` 先输出执行计划）
- 事件类型：
  - `PLAN`：计划输出
  - `SKILL_SELECTED`：技能路由结果
  - `LOOP_STATE`：状态机阶段事件
  - `TOOL_CALL`：工具调用
  - `TOOL_RESULT`：工具结果
  - `VERIFY_RESULT`：最终答案复核结果
  - `ASK_USER`：信息不足反问
  - `HUMAN_GATE`：高风险操作人工确认
  - `TOKEN`：流式文本片段
  - `FINAL`：直接最终回答
  - `ERROR`：错误事件

### 浏览器调试页（推荐替代 Postman）

- 地址：`http://localhost:8080/agent-console.html`
- 用途：可视化构造 Agent 请求、实时查看 SSE 事件流、查看最终输出文本。
- 适用：本地无 KG/Vector 环境时，仅调试 Agent 编排链路。

示例：

```bash
curl -N -X POST http://localhost:8080/api/lumisight/agent/stream \
  -H "Content-Type: application/json" \
  -d '{
    "taskType":"BUG_FIX",
    "repoRoot":"/path/to/repo",
    "question":"分析空指针根因",
    "includeRagContext":true,
    "contextLimit":5,
    "runMode":"PLAN"
  }'
```

## 向量与配置说明（最新）

- 向量链路使用 Milvus 双集合：`code_chunk` 与 `symbol_doc`。
- `symbol_doc` 生成能力通过 `SymbolDocGenerator` 接口预留，可替换为真实模型实现。
- AI 与向量相关配置已收敛到 `lumisight-core`，`lumisight-api` 显式导入 core 配置。
- 启动时建议继续使用环境变量提供密钥，避免明文写入仓库。

## 本地工具能力（Agent Tool）

- LOCAL：`grep/cat/ls/pwd/writeRepoFile`
- BUILD：`compileJava`
- GIT：`gitStatus/gitDiff/gitBlame`
- LSP：`javaGoToDefinition/javaFindReferences/lintJavaByJdtls`

## 文档同步约定

- `CODE_FLOW.md`：按日期追加记录当日提交事实与验证结果。
- `agent-architecture.html`：保持“先整体框架，后分点细化”，并体现当日架构增量。
- 当天收尾执行文档同步后，需要提交并 push 到远程分支。
