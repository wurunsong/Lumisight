# Lumisight

Lumisight 是一个面向 Java 工程场景的 Agent 项目，核心目标是把“代码理解、代码修改、工具执行、知识增强、会话编排”收敛到一套可持续演进的本地 Agent 内核里。

当前主线能力包括：
- 会话式 Agent 编排：支持 `FOLLOW / COLLECT / STEER`
- 流式交互：SSE 与 WebSocket 会话事件流
- macOS 客户端壳：原生 SwiftUI 会话界面
- 本地代码工具：文件、Git、Java 编译、Lint、LSP、长期记忆
- 知识增强：向量检索、知识图谱、方法源码补全
- 安全执行：sandbox、快照、回滚、人工门控
- 上下文管理：结构化账本、artifact 落盘、投影与压缩
- 定时触发：cron job 调用 Agent 执行固定任务

## 环境要求

- JDK 21
- Maven 3.9+

## 启动前环境变量

启动 `lumisight-api` 前请先配置以下密钥，不要写入仓库文件：

- `CHAT_API_KEY`：聊天模型 Key
- `EMBEDDING_API_KEY`：Embedding Key

示例：

```bash
export CHAT_API_KEY="<your-chat-key>"
export EMBEDDING_API_KEY="<your-embedding-key>"
```

## 两种启动方式

### 1. 只调试 Agent 主链路

这是最推荐的本地启动方式。

- 保持 `lumisight.kg.enabled=false`
- 保持 `lumisight.vector.enabled=false`
- 直接启动 API：

```bash
mvn -pl lumisight-api -am spring-boot:run
```

这种模式下，不依赖 NebulaGraph 或 Milvus，也能调试会话编排、工具调用、上下文管理和流式协议。

### 2. 验证完整知识增强链路

如果你要测试向量检索或知识图谱，再启动外挂基础设施：

```bash
podman machine start
./scripts/start-infra.sh
./scripts/check-infra.sh
```

然后开启：

- `lumisight.kg.enabled=true`
- `lumisight.vector.enabled=true`

停止基础设施：

```bash
./scripts/stop-infra.sh
```

## 核心文档

- [AGENT_PROTOCOL.md](./AGENT_PROTOCOL.md)：Agent 协议说明
- [agent-architecture.html](./agent-architecture.html)：主架构视图
- [context-management.html](./context-management.html)：上下文管理专项视图
- [CODE_FLOW.md](./CODE_FLOW.md)：按日期记录的开发进展
- [AGENT_CAPABILITY_GAP.md](./AGENT_CAPABILITY_GAP.md)：当前能力与后续差距清单
- [lumisight-desktop-macos/README.md](./lumisight-desktop-macos/README.md)：macOS 原生客户端壳说明

## Agent 交互模型

Lumisight 现在采用“按会话订阅事件流，再向同一会话投递命令”的模型：

- `GET /api/lumisight/agent/stream?sessionId=...`
  作用：建立该会话唯一的 SSE 订阅
- `POST /api/lumisight/agent/run`
  作用：向指定 `sessionId` 提交问题、恢复或中断命令
- `ws://<host>/ws/lumisight/agent`
  作用：WebSocket 方式收发 Agent 事件与命令

同一 `sessionId` 下：
- `FOLLOW`：新问题顺序排队
- `COLLECT`：等待中的问题合并处理
- `STEER`：新问题抢占当前执行

调试页：

- `http://localhost:8080/agent-console.html`

它适合本地直接观察会话事件流、最终输出和命令投递效果。

可选桌面壳：

- `lumisight-desktop-macos`
- 适合在 macOS 上以原生客户端方式连接同一套 Agent WebSocket

## 主要接口

### Agent

- `GET /api/lumisight/agent/stream`
- `POST /api/lumisight/agent/run`
- `ws://<host>/ws/lumisight/agent`

### Agent Cron

- `GET /api/lumisight/agent/cron-jobs`
- `GET /api/lumisight/agent/cron-jobs/{jobId}`
- `POST /api/lumisight/agent/cron-jobs`
- `PUT /api/lumisight/agent/cron-jobs/{jobId}`
- `POST /api/lumisight/agent/cron-jobs/{jobId}/trigger`
- `DELETE /api/lumisight/agent/cron-jobs/{jobId}`

说明：
- 当前 cron job 定义和最近运行历史为内存态
- 服务重启后不会自动恢复
- 定时执行仍然走同一套 Agent 主链路

### Memory

- `GET /api/lumisight/memory/entries`
- `POST /api/lumisight/memory/entries`
- `DELETE /api/lumisight/memory/entries/{filename}`
- `GET /api/lumisight/memory/index`
- `GET /api/lumisight/memory/relevant`

说明：
- 长期记忆只支持四类：`user / feedback / project / reference`
- 每条记忆单独保存为 Markdown 文件，`MEMORY.md` 作为轻量索引
- `GET /api/lumisight/memory/relevant` 会按当前问题挑选最相关的记忆，并附带陈旧度提醒

### KG / Vector

仅在开启对应开关后可用：

- `POST /api/lumisight/kg/build`
- `POST /api/lumisight/kg/view`
- `POST /api/lumisight/kg/query`
- `POST /api/lumisight/vector/code-chunk/ingest`
- `POST /api/lumisight/vector/symbol-doc/ingest`

## 上下文管理

Lumisight 当前不是简单把历史消息堆成一个字符串列表，而是走结构化上下文账本：

- 会话恢复时保留 `AgentContextSession`
- 大结果可以落盘到 `.lumisight/context-artifacts`
- 调模型前先做读时投影，而不是每轮都塞入全量历史
- 必要时会触发 Snip、Micro-Compact 和 Auto-Compact

这套机制的目的，是让 Agent 在保留执行证据的同时，尽量适配不同模型的上下文窗口。

## 安全执行

Lumisight 的工具与 Hook 已统一到同一套受限执行链路：

- 支持 sandbox 执行
- 写文件前保留快照
- 支持 `rollbackRepoFile` 回滚
- 高风险写操作可进入 `HUMAN_GATE`

当前默认更偏向本地安全调试，而不是“无限制 shell”。

## 本地工具能力

当前主工具族包括：

- LOCAL：`grep / cat / ls / pwd / writeRepoFile / rollbackRepoFile`
- BUILD：`compileJava`
- GIT：`gitStatus / gitDiff / gitBlame`
- LSP：`javaGoToDefinition / javaFindReferences / lintJavaByJdtls`
- RAG：`searchHybridVector`
- GRAPH：`fetchOneHopByKgNodeId`
- SOURCE：`fetchMethodSourceByLocation`
- MCP：`callMcpCapability`
- MEMORY：`memory_list / memory_write`
- PLANNING：`todo_write`

## 线程与会话治理

- 同一会话由 mailbox/worker 串行消费
- 多线程执行统一走命名线程池
- `AgentToolRuntimeContext`、`AgentToolInvocationContext` 和 `SLF4J MDC` 支持跨线程传播
- 会话状态通过 `AgentSessionContextStore` 收口，避免调度、todo 和清理逻辑分散耦合

## 配置与 Hook

- 核心运行配置位于 `lumisight-core/src/main/resources/lumisight-core.yml`
- Hook 示例位于 `.lumisight/hooks.json.example`
- 默认 Hook 配置路径为 `.lumisight/hooks.json`

如果你只想先跑通主链路，优先关注：

- `lumisight.kg.enabled`
- `lumisight.vector.enabled`
- `lumisight.sandbox.*`
- `lumisight.agent.context.*`
- `lumisight.agent.conversation.*`
- `lumisight.agent.cron.*`

## 目录速览

- `lumisight-api`：HTTP / SSE / WebSocket 接入层
- `lumisight-core`：Agent 编排、提示词、策略、上下文、工具编排
- `lumisight-common`：通用执行与并发基础设施
- `lumisight-desktop-macos`：macOS 原生客户端壳
- `lumisight-tools`：知识图谱与向量构建工具
- `lumisight-hooks`：Hook 能力相关代码
- `lumisight-memory`：记忆相关模块
- `lumisight-skills`：Skill 能力相关模块
- `scripts`：本地基础设施启动脚本
- `infra`：Nebula / Milvus 编排

## 补充说明

- README 只保留“如何理解和启动项目”的高层信息。
- 字段级协议、详细架构链路、上下文压缩细节，统一放到专项文档中维护。
