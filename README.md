# Lumisight

Lumisight 是一个面向 Java 工程场景的 Agent 项目，核心目标是把“代码理解、代码修改、工具执行、知识增强、会话编排”收敛到一套可持续演进的本地 Agent 内核里。

当前主线能力包括：
- 会话式 Agent 编排：支持 `FOLLOW / COLLECT / STEER`
- 流式交互：SSE 与 WebSocket 会话事件流
- macOS 客户端壳：原生 SwiftUI 会话界面
- 本地代码工具：文件、Git、Java 编译、Lint、LSP、长期记忆
- 知识增强：向量检索、知识图谱、方法源码补全
- 浏览器自动化：网页打开、DOM 摘要、点击输入、截图
- 安全执行：sandbox、快照、回滚、人工门控
- 上下文管理：结构化账本、artifact 落盘、投影与压缩
- 自修复闭环：BUG_FIX 模式下写代码后自动编译 / lint，失败就继续迭代修复
- 多 Agent 演进：单 Agent 默认路径、`task_subagent` 干净子任务、Lead + team workers 基础版请求内编排
- 定时触发：cron job 调用 Agent 执行固定任务
- 持久化任务系统：`.tasks/` 下的跨会话任务图、依赖与认领状态

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

如果要使用浏览器 / DOM 工具，还需要预先安装 Playwright 浏览器：

```bash
mvn -pl lumisight-core -am exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
```

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
- [lumisight.html](./lumisight.html)：完整架构总览
- [agent-architecture.html](./agent-architecture.html)：主架构视图
- [memory-context-management.html](./memory-context-management.html)：上下文管理专项视图
- [CODE_FLOW.md](./CODE_FLOW.md)：按日期记录的开发进展
- [AGENT_CAPABILITY_GAP.md](./AGENT_CAPABILITY_GAP.md)：当前能力与后续差距清单
- [MULTI_AGENT_PLAN.md](./MULTI_AGENT_PLAN.md)：多 Agent 统一蓝图与分阶段落地说明
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
- 当前界面已收敛为深色主题，作为原生壳默认视觉基线

## 主要接口

### Agent

- `GET /api/lumisight/agent/stream?sessionId=...`
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

## 任务系统

Lumisight 现在同时有两套“任务”能力：

- `todo_write`：当前会话内的轻量 checklist，只负责本轮执行步骤
- `task_*`：跨会话持久化任务系统，任务保存在 `repoRoot/.tasks/*.json`

持久化任务系统当前支持：

- `task_create`：创建任务
- `task_list`：查看任务列表
- `task_get`：查看单个任务完整 JSON
- `task_claim`：认领已解锁任务
- `task_release`：释放已认领任务并回退到 `pending`
- `task_complete`：完成任务并返回因此解锁的下游任务
- `task_resume`：自动续跑当前 owner 的进行中任务，或找到下一批已解锁任务并可自动认领
- `task_board`：按 `ready / in_progress / blocked / completed` 查看任务看板

任务字段当前包括：

- `id / subject / description / status / owner`
- `blockedBy`：上游依赖
- `blocks`：下游任务

状态流转：

- `pending -> in_progress -> completed`

当前已经支持依赖环检测、release 回退、任务看板和自动续跑；更偏产品化的看板 UI、自动完成判定策略和更复杂的调度治理仍可继续增强。

## Multi-Agent

当前多 Agent 体系采用三层结构：

- 默认仍是单 Agent 路径
- 当局部分析会显著污染上下文时，主 Agent 可调用 `task_subagent`
- 当请求天然需要拆分、依赖收敛或并发扫描时，可升级到 Lead + team workers 的请求内编排路径

`task_subagent` 的当前约束：

- 子 Agent 使用独立上下文，不继承完整主会话历史
- 子 Agent 默认最小只读权限
- 子 Agent 不能递归创建新的 agent
- 子 Agent 只返回结构化结论，最终写仓库和最终回答仍由主 Agent 负责

Lead + team workers 的当前能力：

- 支持 `SERIAL_DAG`、`FAN_OUT_FAN_IN`、`HYBRID` 三种任务拓扑
- Lead 负责拆解 plan、管理依赖、派发 task、汇总结果与最终收口
- worker 通过 `.lumisight/teams/{teamId}/inboxes/*.jsonl` 收发 assignment、result、idle、permission 等生命周期消息
- 同一 wave 内多个 worker 可以并发执行，结果和 task state 会统一回流给 Lead
- 执行状态会持续保存到 `.lumisight/teams/.../execution-state.json`，`resume` 会从已完成 wave 之后继续，而不是整轮重跑

当前边界：

- 这还是“请求内 Lead 编排系统”，不是长期自治的常驻多角色团队
- planner 和任务拆解质量仍在继续增强，跨请求共享工作记忆也还没有完整成型

### KG / Vector

仅在开启对应开关后可用：

- `POST /api/lumisight/kg/build`
- `POST /api/lumisight/kg/view`
- `POST /api/lumisight/kg/query`
- `POST /api/lumisight/vector/code-chunk/ingest`
- `POST /api/lumisight/vector/symbol-doc/ingest`

## 浏览器 / DOM 能力

当前 Agent 已接入一组会话级浏览器工具，适合本地页面联调、DOM 识别、表单操作与截图验证：

- `browser_open`
- `browser_snapshot`
- `browser_click`
- `browser_type`
- `browser_screenshot`
- `browser_close`

行为模型：

- 每个 Agent `sessionId` 会复用一页浏览器上下文
- `browser_snapshot` 会返回标题、URL、可见文本和一组可操作 DOM 元素摘要
- 截图默认保存在 `.lumisight/browser-artifacts/<sessionId>/`

当前更适合：

- 本地 `localhost` / 管理后台 / 调试页验证
- 改完前端后做最小行为闭环
- 让 Agent 自己识别按钮、输入框、链接并继续操作

## 上下文管理

Lumisight 当前不是简单把历史消息堆成一个字符串列表，而是走结构化上下文账本：

- 会话恢复时保留 `AgentContextSession`
- 大结果可以落盘到 `.lumisight/context-artifacts`，账本里只保留 preview 与 artifact 引用
- 会额外维护一层“最近访问热缓存”，用于 Auto-Compact 后优先恢复最近文件/工具证据
- 调模型前先做读时投影，而不是每轮都塞入全量历史
- 必要时会按顺序触发大结果落盘、Snip、Micro-Compact、读时投影和 Auto-Compact
- Auto-Compact 后会按预算恢复热文件/热结果、活跃 skill，以及必要的 todo/plan 视图

## 自修复闭环

当前 BUG_FIX 主链路已经具备最小自修复闭环：

- 当 Agent 通过 `writeRepoFile` 修改 Java 文件后，系统会自动触发 `compileJava` 和 `lintJavaByJdtls`
- 若自动验证失败，失败结果会回灌到结构化上下文里，下一轮继续修复而不是直接结束
- 若最近一次自动验证仍未通过，Agent 不允许直接给出最终答案

可调配置：

- `lumisight.agent.self-heal.enabled`
- `lumisight.agent.self-heal.run-compile`
- `lumisight.agent.self-heal.run-lint`
- `lumisight.agent.self-heal.require-success-before-final`
- `lumisight.agent.self-heal.max-validation-files`

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
- BROWSER：`browser_open / browser_snapshot / browser_click / browser_type / browser_screenshot / browser_close`
- MEMORY：`memory_list / memory_write`
- PLANNING：`todo_write / task_create / task_list / task_get / task_claim / task_complete`

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
- `lumisight.browser.*`
- `lumisight.task.*`

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
