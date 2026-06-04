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

## 协议与传输适配（SSE/WS）

- 核心编排已与传输协议解耦：统一通过 `AgentExecutionEngine` 执行主流程。
- API 侧通过 `AgentTransportAdapter` + `AgentStreamGateway` 适配不同协议（当前内置 SSE 与 WebSocket）。
- 同一 `sessionId` 的消息现在先进入本地 dispatcher/mailbox，再由单 worker 串行消费；`FOLLOW/COLLECT/STEER` 在消息投递层决策，而不是入口直接并发执行。
- SSE 已收敛为“按会话维持单长连接 + 命令单独投递”：`GET /api/lumisight/agent/stream?sessionId=...` 负责持续消费事件，`POST /api/lumisight/agent/run` 负责提交问题、恢复或中断命令。
- 同一 `sessionId` 如果重复建立 SSE 订阅，后到的连接会替换旧连接；客户端断开订阅不会自动 cancel 正在运行的会话。
- SSE / WebSocket 事件都会渐进推送 `PLAN/TOOL/TOKEN/FINAL` 等统一 `AgentEvent`，不再要求“每次提问新开一条独立流”。
- 协议契约文档见：`AGENT_PROTOCOL.md`（中文）。
- 架构视图见：`agent-architecture.html`；上下文专项视图见：`context-management.html`。

## 上下文管理（结构化账本）

- Agent 上下文不再只是裸 `List<AgentContextItem>`；会话恢复时会同时持有 `AgentContextSession`，把 entry、artifact 引用与压缩状态一起带回主链路。
- 工具结果过大时会先按字节阈值写入 `.lumisight/context-artifacts`，prompt 中只保留 preview 与 artifact 引用。
- 调模型前会经过统一 `AgentContextManager`：
  - 写时压缩：大结果落盘、Snip、Micro-Compact
  - 读时压缩：Projection、必要时 Auto-Compact + 热文件恢复
- 这套机制的目标是：在保留执行证据的同时，按不同模型上下文窗口动态控制 prompt 体积。

## Sandbox 执行与回滚

- 新增统一命令执行治理配置（`lumisight-core.yml`）：
  - `lumisight.sandbox.enabled`
  - `lumisight.sandbox.mode=local|docker|mac-seatbelt`
  - `lumisight.sandbox.network-enabled`
  - `lumisight.sandbox.timeout-seconds`
  - `lumisight.sandbox.max-output-bytes`
  - `lumisight.sandbox.memory-mb` / `cpu-limit`
  - `lumisight.sandbox.snapshot-dir`
- 当前默认模式为 `mac-seatbelt`，优先复用 macOS 自带的进程隔离能力；Docker 仍可选但不再是默认。
- Git 工具（`gitStatus/gitDiff/gitBlame`）已统一走 sandbox 执行器。
- Hook 与 Tool 已统一到同一命令执行内核与同一份 sandbox 配置，不再存在 Hook 绕开 Tool 沙箱的独立路径。
- 命令执行不再只是“先拼命令、再套通用模板”：
  - 当前已经形成 `SandboxAccessSpec` -> `SandboxPolicyPlanner` -> `CommandExecutionPolicy` -> `SandboxCommandExecutor` 的受限执行链路。
  - Git 工具与 Hook 命令已接入这条链路，执行结果里会附带 `sandboxPlan`，方便调试本次实际放开的权限范围。
  - 目前 `mac-seatbelt` 侧仍以模板化临时 profile 为主；按 `tool/hook + args` 自动推导最小读写路径、网络和可执行边界的 planner 还会继续增强。
- `writeRepoFile` 现在会返回 `snapshotId`（写前快照），可通过 `rollbackRepoFile` 回滚。

## 启动路径建议

- 仅调试 Agent 主链路：保留默认 `lumisight.kg.enabled=false`、`lumisight.vector.enabled=false`，直接启动 `lumisight-api` 即可。
- 需要完整验证外挂知识库增强能力：先启动 NebulaGraph + Milvus，再开启 KG / Vector 开关。

## 第 1 步：本地启动外挂知识库（Podman）

> 说明：这一步只在你需要验证 KG / Vector 增强能力时才必需；如果只是调试 Agent 主链路，可跳过并保持默认关闭开关。
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

- `GET /api/lumisight/agent/stream?sessionId=<sessionId>`：建立该会话唯一的 SSE 订阅，持续返回 Agent 事件流。
- `POST /api/lumisight/agent/run`：向指定 `sessionId` 投递命令，请求开始执行、恢复或中断。
- 说明：最终回答阶段已经支持真实 `TOKEN` 流式输出，同时主循环事件也会渐进推送；但 `PLAN/DECIDE/VERIFY` 等中间模型推理目前仍以内部流式采集为主，前端看到的重点仍是阶段事件与最终回答 token。
- 命令字段（`AgentRunRequest`）：
  - `taskType`：`CODE_EXPLAIN` / `BUG_FIX` / `CHAT`（可为空，空时按服务默认策略处理）
  - `repoRoot`：仓库根路径
  - `question`：用户问题
  - `includeRagContext`：是否启用向量工具
  - `contextLimit`：上下文上限
  - `runMode`：`NORMAL` / `PLAN`（`PLAN` 先输出执行计划）
  - `dialogueMode`：`FOLLOW` / `COLLECT` / `STEER`
    - `FOLLOW`：同会话新问题按顺序排队处理
    - `COLLECT`：同会话等待期间的新问题合并后统一处理
    - `STEER`：新问题抢占当前执行并中断旧流输出
  - `skillPath`：可选；字段名保留为 `skillPath`，但实际传入的是“已注册技能引用”（如 id/name/path），仅允许命中 `lumisight.skills.allowed-paths` 扫描得到的 Markdown skill
  - 若不传 `skillPath`，Agent 会基于用户问题通过小模型在“已注册 skill 列表”中自动匹配一个技能；未命中或低置信时回退内置工作流。
- 常见调用顺序：
  - 先建立 SSE：`GET /api/lumisight/agent/stream?sessionId=s-1`
  - 再投递命令：`POST /api/lumisight/agent/run`
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
  - `INTERRUPTED` / `RESUMED`：会话被中断或恢复执行

### Agent Cron 接口

- `GET /api/lumisight/agent/cron-jobs`：列出所有定时任务。
- `GET /api/lumisight/agent/cron-jobs/{jobId}`：查看单个定时任务详情与最近运行状态。
- `POST /api/lumisight/agent/cron-jobs`：创建定时任务。
- `PUT /api/lumisight/agent/cron-jobs/{jobId}`：更新 cron 表达式、开关和绑定的 Agent 请求。
- `POST /api/lumisight/agent/cron-jobs/{jobId}/trigger`：立即手动触发一次。
- `DELETE /api/lumisight/agent/cron-jobs/{jobId}`：删除定时任务。
- 说明：
  - 当前 job 定义和最近运行历史为内存态，服务重启后不会自动恢复。
  - 定时执行最终仍走同一套 `AgentExecutionEngine` 主链路，不是独立的旁路实现。

### Agent 接口（WebSocket）

- 地址：`ws://<host>/ws/lumisight/agent`（HTTPS 场景使用 `wss://`）
- 入站命令：`WsAgentCommand`
  - `type`：`START` / `RESUME` / `INTERRUPT` / `PING`
  - `requestId`：前端请求追踪 id
  - `request`：`AgentRunRequest`（与 `POST /api/lumisight/agent/run` 的请求体字段一致）
- 出站消息：`WsAgentMessage`
  - `ACK`：命令受理
  - `EVENT`：封装 `AgentEvent`
  - `PONG`：心跳响应
  - `ERROR`：协议/运行时错误

### 浏览器调试页（推荐替代 Postman）

- 地址：`http://localhost:8080/agent-console.html`
- 用途：先按 `sessionId` 建立 SSE 长连接，再可视化投递 Agent 命令、实时查看会话事件流与最终输出文本。
- 适用：本地无 KG/Vector 环境时，仅调试 Agent 编排链路。

示例：

```bash
curl -N "http://localhost:8080/api/lumisight/agent/stream?sessionId=s-1"

curl -X POST http://localhost:8080/api/lumisight/agent/run \
  -H "Content-Type: application/json" \
  -d '{
    "taskType":"BUG_FIX",
    "sessionId":"s-1",
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
- 聊天模型 HTTP 超时已可单独配置：
  - `lumisight.ai.chat-connect-timeout-seconds`
  - `lumisight.ai.chat-read-timeout-seconds`
- Spring AI 默认重试次数已显式收敛为 `1`，避免上游超时把一次决策拖成多轮等待。

## 会话与连接治理配置（2026-06-01）

- 会话等待态 TTL：
  - `lumisight.agent.conversation.waiting-user-ttl-seconds`
  - `lumisight.agent.conversation.waiting-gate-ttl-seconds`
  - `lumisight.agent.conversation.cleanup-interval-ms`（定时清理周期）
- WS 治理：
  - `lumisight.agent.websocket.allowed-origins`
  - `lumisight.agent.websocket.max-connections`
  - `lumisight.agent.websocket.max-text-message-size`
  - `lumisight.agent.websocket.message-rate-limit-per-minute`

## 本地工具能力（Agent Tool）

- LOCAL：`grep/cat/ls/pwd/writeRepoFile/rollbackRepoFile`
- BUILD：`compileJava`
- GIT：`gitStatus/gitDiff/gitBlame`
- LSP：`javaGoToDefinition/javaFindReferences/lintJavaByJdtls`
- RAG：`searchHybridVector`
- GRAPH：`fetchOneHopByKgNodeId`
- SOURCE：`fetchMethodSourceByLocation`
- MCP：`callMcpCapability`
- PLANNING：`todo_write`

## 工具参数与执行模型

- 工具现在统一使用 typed record 入参，不再在工具内部手拆 `Map<String,Object>`。
- 同一份参数定义会同时用于：
  - prompt 中的 `argsSchema`
  - `exampleArgs`
  - 运行时 JSON -> typed args 绑定
  - 参数校验与工具执行
- 多工具调用支持“连续并发安全批次”：
  - 读工具可按连续块并发执行
  - 写工具、编译、回滚与有顺序依赖的调用仍保持串行
- 新增 `todo_write` 任务清单工具：
  - 作用是给 Agent 增加规划能力，而不是新增外部执行能力。
  - 参数为 `todos[{content,status}]`，状态支持 `pending / in_progress / completed`。
  - 若复杂任务连续 3 轮没有刷新 todo，系统会在下一次决策前自动注入 `<reminder>Update your todos.</reminder>`。

## 线程池与上下文传播

- 所有多线程执行统一来自 `NamedExecutors`，不允许业务代码直接裸用 `Executors.*` 或 `new Thread(...)`。
- `NamedExecutors` 返回的是带上下文传播能力的 `ContextAwareExecutorService`：
  - 提交任务时捕获父线程上下文
  - 子线程执行前恢复
  - 执行后恢复原线程上下文，避免线程池复用造成串线
- 当前已注册并自动传播的上下文包括：
  - `AgentToolRuntimeContext`
  - `AgentToolInvocationContext`
  - `SLF4J MDC`
- 会话状态存取也已经通过 `AgentSessionContextStore` 抽象收口，避免调度、todo、清理任务直接绑死具体实现。
- 这套机制已覆盖会话 worker、并发工具执行、混合向量并行检索和 JDTLS 后台 drain 线程。

## 文档同步约定

- `CODE_FLOW.md`：按日期追加记录当日提交事实与验证结果。
- `agent-architecture.html`：保持“先整体框架，后分点细化”，并体现当日架构增量。
- 当天收尾执行文档同步后，需要提交并 push 到远程分支。

## 配置化 Hook（用户侧，无需改代码）

- 示例文件：`.lumisight/hooks.json.example`（复制为 `.lumisight/hooks.json` 后生效）
- 默认读取路径：`.lumisight/hooks.json`（兼容回退：若新路径不存在，会尝试 `.codeflicker/config.json`）
- 可通过 JVM 参数覆盖：`-Dlumisight.hooks.config=/abs/path/config.json`
- 支持全部点位（键名）：
  - `BeforePlan`、`AfterPlan`
  - `BeforeDecision`、`AfterDecision`
  - `PreToolUse`、`PostToolUse`
  - `OnAskUser`、`BeforeFinal`、`OnError`
- 每个点位下按 `matcher` + `hooks` 规则执行 `type=command` 钩子（非工具阶段可省略 `matcher`）：
  - 钩子通过 stdin 接收 JSON payload（`tool_name`、`tool_input`、`question` 等）
  - 退出码非 0 或输出 `{ "continue": false }` 将阻断请求
  - 示例钩子：`.lumisight/hooks/db-guard-hook.js`
