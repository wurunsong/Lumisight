# Lumisight 本地续聊流水账（CODE_FLOW）

## 0. 固定测试仓库（显眼置顶）
- 测试仓库路径：`/Users/lilac/rpc`

> 规则：只增不减；按日期追加；仅本地使用，不推远程。

## 2026-05-24
- Nebula 最小可用链路已打通：space 自动派生（`kg_工程名`）、初始化与 schema 时序问题已修复。
- KG 接口前缀统一为：`/api/lumisight`。
- 新增并可用接口：
  - `POST /api/lumisight/kg/build`
  - `POST /api/lumisight/kg/view`
  - `POST /api/lumisight/kg/query`
- 新增向量入库骨架（Milvus + Spring AI）：
  - `POST /api/lumisight/vector/code-chunk/ingest`
  - `POST /api/lumisight/vector/symbol-doc/ingest`
- `symbol_doc` 的 AI 生成已预留接口：`SymbolDocGenerator`（当前模板实现，后续可替换真实模型调用）。
- 约定保留：
  - Nebula space 命名：`kg_工程名`
  - 图谱删除策略：逻辑删除（`status=1`）
  - 节点/边 ID：哈希稳定规则
- 今日提交摘要（22 commits）：
  - 新增图谱查询能力：`/api/lumisight/kg/view`、`/api/lumisight/kg/query` 及单节点查询接口，Nebula 查询语法已修正。
  - 向量能力从 KG 拆分为独立模块并重整目录分层：`api/vector` + `tools/vector`，控制器/DTO/SPI/Model 职责分离。
  - 增强向量入库：支持代码自动切片、按仓库方法解析入库、基于 `git diff` 的增量更新。
  - Milvus 双集合（`code_chunk` / `symbol_doc`）改为双 Bean + `@Qualifier` 注入，避免别名冲突并保留懒加载开关。
  - AI 与向量配置迁移至 `lumisight-core`，并将 chat/embedding API key 拆分，统一配置来源。
  - Spring AI 非必需模型默认关闭，禁用 OpenAI 音频自动配置，避免无 key 启动失败。
  - 最终提交对向量入库管线与 provider 配置再次对齐，补充 README 说明与核心配置细化。
- 关键修复：
  - 启动阶段多模块同名配置冲突 -> 重命名 core 资源为 `lumisight-core.yml` 并在 API 显式导入。
  - 向量存储 Bean 冲突与覆盖 -> 删除重复别名配置，切换为双集合 Bean 显式注入。
  - API 层临时 AI 探针与直连配置引入噪音 -> 回滚临时探针，保留稳定配置路径。
- 验证：
  - `git log --since="2026-05-24 00:00:00" --until="2026-05-24 23:59:59"`：识别到 22 条提交。
  - `git log --stat`：确认变更覆盖 `api/core/tools` 与 `application.yml|lumisight-core.yml`，与摘要一致。

## 2026-05-29
- 今日提交摘要（20 commits）：
  - Agent 流式主链路落地：`PLAN/NORMAL`、`ask_user`、SSE 事件流接口与事件模型统一。
  - 主循环能力增强：对话模式 `FOLLOW/COLLECT/STEER`、工具参数契约、结构化工具结果、软状态机与事件协议 v2。
  - 会话控制增强：中断/恢复、反问闭环、待确认任务恢复执行。
  - 执行治理增强：`Verifier` 复核环、`Human Gate` 人工确认节点、工具 `Retry/Fallback` 策略层。
  - 工具体系扩展：
    - 本地终端语义工具：`grep/cat/ls/pwd/writeRepoFile`
    - 本地质量工具：`lintJava`、`compileJava`
    - 本地 Git 语义工具：`gitStatus/gitDiff/gitBlame`
    - 本地 Java LSP 工具：`javaGoToDefinition/javaFindReferences`
    - jdtls 语义诊断工具：`lintJavaByJdtls`
  - jdtls 会话池新增：按 `repoRoot` 复用进程，`lintJavaByJdtls` 改为复用会话。
  - 架构模块化：
    - 独立 `lumisight-mcp`、`lumisight-hooks` 模块并接入主编排
    - 新增 `multiagent` 接口骨架（orchestrator/sub-agent/router + 默认实现）
  - 目录结构重构：`agent` 目录仅保留核心 agent 与 `multiagent`，其余下沉到 `core` 一级；`config` 目录合并。
  - skill 规范升级：`daily-commit-architecture-sync` 强制“当天清晰记录 + HTML/CODE_FLOW 清晰展示 + 最终 push”，并增加“必要时可重写 README”条款。
- 关键修复：
  - 由一次性 LSP 诊断改为会话池复用，减少重复拉起 jdtls 的开销。
  - 由粗粒度写文件改为“人审门控 + 可恢复”执行路径，降低高风险写入误触发。
  - 文档与代码结构不一致问题通过目录重构与规则补充统一。
- 验证：
  - `git log --since="2026-05-29 00:00:00" --until="2026-05-29 23:59:59"`：识别到 20 条提交。
  - `git log --stat`：确认变更覆盖 `api/core/mcp/hooks/skills/docs`，与当日能力摘要一致。

## 2026-05-28
- 今日提交摘要（26 commits）：
  - 搭建并持续重构 Spring AI Agent 脚手架，形成“手动循环编排 + 工具注册中心 + 权限控制”的执行主线。
  - 工具能力从双向量查询扩展到：混合向量并发召回、基于 `kgNodeId` 的一跳图谱检索、基于 `sourceFile/startLine/endLine` 的源码回查。
  - 向量与图谱完成关联字段打通：`symbol_doc` 入库新增 `kgNodeId`，并写入向量元数据 `kg_node_id`。
  - Agent 主类演进为单类流式输出形态，支持 `Flux<String>` 流式返回。
  - API 层新增 Agent SSE 接口（次日提交 `c5b158c` 落地），用于实时消费 Agent 事件流。
- 行为变化：
  - 工具调用从模型自动路由改为手动编排循环（服务端控制轮次、权限和回退）。
  - 图谱/源码工具从模型直调转为可控链路；后续预留 Advisor 承接上下文增强。
  - 目录结构收敛：核心 Agent 类位置多次调整并最终统一到 `core/agent`。
- 关键修复：
  - `repoRoot` 不再要求模型显式传参 -> 使用运行时上下文自动注入。
  - 工具膨胀导致主类耦合高 -> 引入工具分类与注册中心，按名称调度。
  - 请求校验逻辑重复 -> 抽取 `AgentRequestValidators` 统一复用。
- 验证：
  - `git log --since="2026-05-28 00:00:00" --until="2026-05-28 23:59:59"`：识别到 26 条提交。
  - `git log --stat`：确认变更主要集中在 `lumisight-core/agent` 与 `lumisight-api/agent`。

## 2026-06-01
- 今日提交摘要（1 commit）：
  - Agent 与 KG/Vector 基础设施隔离开关落地：默认关闭 `lumisight.kg.enabled`、`lumisight.vector.enabled`，并排除 Milvus 自动配置，支持无 Nebula/Milvus 环境启动调试。
  - KG 相关 API/Service/配置改为条件装配：`lumisight.kg.enabled=true` 时才启用 KG Controller、NebulaProperties 与 KG 服务链路。
  - Agent 运行时补齐 Noop/Fallback Provider：向量与图谱 Provider 缺失时使用本地兜底实现，避免启动阶段 Bean 缺失导致失败。
  - 工具调度兼容层新增：在 `CodeAssistantAgentService` 增加常见工具别名映射（如 `read_file/read_directory/list_directory`）到本地工具 `cat/ls/grep`，减少 unknown_tool。
  - 新增前端调试页：`/agent-console.html`，支持浏览器直接调用 `/api/lumisight/agent/stream` 并实时查看 SSE 事件流。
  - 项目级 Maven 配置新增：`.mvn/settings.xml` + `.mvn/maven.config`，仓库内独立依赖缓存与中央仓库配置，避免污染其他仓库。
- 关键修复：
  - 无向量库环境启动超时（Milvus DEADLINE_EXCEEDED）-> 关闭向量默认开关并排除 Milvus 自动配置。
  - Agent 请求返回 500 且难定位 -> GlobalExceptionHandler 增加 AI 上游异常映射（上游 401 返回 401）与静态资源 404 映射。
  - 模型反复调用未注册目录工具 -> 增加工具名与参数兼容映射，降低编排失败率。
- 验证：
  - `git log --since="2026-06-01 00:00:00" --until="2026-06-01 23:59:59"`：识别到 1 条提交（`7e3198c`）。
  - 浏览器页面 `http://localhost:8080/agent-console.html`：可发起 `/api/lumisight/agent/stream` 请求并接收 `LOOP_STATE/TOOL_CALL/TOOL_RESULT/TOKEN` 事件流。

- 今日提交补充摘要（新增 5 commits，总计 6 commits）：
  - `a17a1ef`：Skill 执行链路从“内置默认技能”扩展为“文件驱动技能（skillPath）”，新增 `FileBackedAgentSkill`、`SkillMarkdownParser`，并在 API 请求模型中加入 `skillPath` 字段，支持把外部技能文件直接挂载到 Agent 执行。
  - `4bbaabb`：工具权限从粗粒度放开调整为“按请求与场景组合授权”，并把最终答案复核改为条件触发，避免普通闲聊场景每轮都走复核导致时延上升。
  - `099864b`：Hook 机制对齐 codeflicker 风格，支持读取 `.codeflicker/config.json` 中 `PreToolUse` 规则并执行 `type=command` 钩子；失败时可阻断高风险工具调用。
  - `a291553`：新增统一 Sandbox 命令执行器（超时、输出上限、全局网络开关、Docker 模式资源限制），Git 工具全部切换到统一执行器；新增写文件快照与 `rollbackRepoFile` 回滚工具。
  - `74eebeb`：文档同步流程首次落地，`CODE_FLOW.md`、`agent-architecture.html`、`README.md` 与当日提交事实保持一致。
- 关键修复（补充）：
  - Hook 配置与用户期望不一致（代码写死式扩展）-> 改为配置文件驱动 + 命令钩子标准输入输出协议。
  - 最终答案复核导致对话卡顿 -> 通过条件复核降低非精确问答场景延迟。
  - 工具执行缺乏统一治理与回滚 -> 引入 SandboxCommandRunner + SnapshotManager，形成“执行约束 + 可回退”闭环。
- 验证（补充）：
  - `git log --since="2026-06-01 00:00:00" --until="2026-06-01 23:59:59"`：识别 6 条提交（`7e3198c`、`74eebeb`、`a17a1ef`、`4bbaabb`、`099864b`、`a291553`）。
  - `git log --stat`：确认变更覆盖 `api/core/tools/hooks/skills/docs`，并新增 `sandbox` 与 `rollback` 相关实现文件。

- TODO（讨论结论，待实现）：
  - Hook 执行模型升级为“三段式”：`hook_suggest`（模型建议） -> `hook_policy`（系统裁决） -> `hook_execute`（受限执行）。
  - 保持“模型可建议、系统强约束执行”原则，避免模型直接决定高风险脚本执行；后续补齐审计日志与回放能力。
  - Agent 目前是“事件流 + 最终文本聚合”，还不是真正的模型 token 级流式输出；后续需要把 LLM 决策/回答阶段改为真实 streaming，避免必须等待整轮工具调用与思考完成后才开始回复。

- 今日提交补充摘要（再次新增 15 commits，总计 21 commits）：
  - `672f3bb`：Agent 主流程职责拆分，工具执行下沉到 `core/tool/runtime`，Hook AOP 与上下文下沉到 `core/hooks/runtime`，`agent` 目录仅保留核心编排类。
  - `54d203d` + `e2a54fe`：`FOLLOW/COLLECT/STEER` 从提示词语义升级为会话调度语义；新增排队/合并队列与 `epoch` 抢占机制，`STEER` 可中断旧请求并终止旧流输出。
  - `8f59dba`：修复 AOP 启动失败（`AmbiguousBindingException`），移除 `@Around args(...)` 歧义绑定，改为从 `joinPoint` 读取参数。
  - `9810969`：移除 `followUpAnswer`，统一用 `question` 作为输入，后端请求模型、控制器、校验器与前端调试页同步收敛。
  - `4caaf27` + `afb90c8` + `fa4ba98`：Skill 机制升级为“注册式 Markdown skill”：
    - 启动扫描白名单目录（`lumisight.skills.allowed-paths`）构建 `SkillCatalog`
    - `skillPath` 改为注册引用（id/name/path），不再任意路径直读
    - 未显式指定 skill 时，通过小模型在已注册 skill 列表中自动路由
    - `SkillRegistry` 统一 `resolve + buildPlan`，Service 不直接操作 skill 实例
  - `aa483b9`：移除 `executeSkillSteps` 预执行链路与 `mapSkillStepToDecision` 文字映射，`SkillPlan` 仅保留策略/约束作用，不再强行转工具调用。
  - `4d0cbb9` + `797e9bb` + `cc59f68`：Hook 与 Agent 维护性修复：修正 `ConfigurableAgentHook` 变量作用域编译错误，补充 `HUMAN_GATE` 恢复路径注释（中文）。
- 关键修复（再次补充）：
  - AOP 切面参数绑定导致应用启动失败 -> 改为无 `args` 绑定的切面签名 + `joinPoint.getArgs()` 解析。
  - `STEER` 仅软中断导致旧流仍可能输出 -> 引入会话代际 `epoch`，主循环与 Token 流双重活性检查。
  - skill 任意路径注入风险 -> 改为白名单目录扫描注册与注册引用执行。
- 验证（再次补充）：
  - `git log --since="2026-06-01 00:00:00" --until="2026-06-01 23:59:59"`：识别 21 条提交（含 `7e3198c` 至 `797e9bb` 全量链路）。
  - `git log --stat`：确认变更覆盖 `api/core/hooks/skills/docs`，并新增 `SkillCatalog`、`SkillAutoRouter`、会话 `epoch` 与 Hook AOP 运行时分层实现。

- 今日提交补充摘要（再次新增 8 commits，总计 29 commits）：
  - `80b0c9f`：Skill 自动路由升级为结构化结果（`skillId/confidence/reason`），新增低置信回退与 `SKILL_ROUTE` 事件；Hook 命令执行增加超时与输出上限，避免慢脚本拖挂。
  - `555c089`：会话状态迁移显式建模（`RUNNING/WAITING_USER/WAITING_GATE/INTERRUPTED/COMPLETED`）并加入非法迁移保护；Skill 路由阈值与回退策略改为配置化。
  - `3c9b094`：新增会话级 single-flight 互斥，保证同一 `sessionId` 并发请求不踩状态；通过 `doFinally` 统一释放会话租约。
  - `af2e48b`：新增 WebSocket 并存入口 `/ws/lumisight/agent`，不改 SSE 主链路；WS 入站请求复用既有 `AgentRunRequest` 与 `AgentEvent`。
  - `1a8ad98`：协议层与核心编排解耦：引入 `AgentExecutionEngine` 与 `AgentInteractionOrchestrator`；WS 协议升级为命令式（`START/RESUME/INTERRUPT/PING`）。
  - `76c3819`：传输适配 SPI 化：新增 `AgentTransportAdapter`、`AgentEventChannel`、`AgentStreamGateway`、`AgentTransportRegistry`，SSE/WS 统一走可插拔通道。
  - `4378462`：补齐 `AGENT_PROTOCOL.md` 协议契约，新增会话等待 TTL 与定时清理任务；WS 增加连接数、消息大小、速率限制治理配置与策略。
  - `3687231`：中段文档同步补充，确保当时已完成的 skill/hook/会话改造同步到 `CODE_FLOW`、架构图与 README。
- 关键修复（再次补充）：
  - Hook 脚本无治理风险 -> 增加命令级 timeout + 输出字节上限，并在失败/超时时显式阻断。
  - 多请求并发进入同一会话导致状态漂移 -> 引入 single-flight 会话租约与并发分流（FOLLOW/COLLECT/STEER）。
  - 协议层与核心流程耦合高、后续扩展困难 -> 通过 `ExecutionEngine + TransportAdapter` 双层抽象完成解耦。
  - 等待态会话长期堆积 -> 增加 `WAITING_USER/WAITING_GATE` TTL 与定时清理。
- 验证（再次补充）：
  - `git log --since="2026-06-01 00:00:00" --until="2026-06-01 23:59:59"`：识别 29 条提交（最新至 `4378462`）。
  - `git log --stat`：确认新增覆盖 `api/ws/transport/core/support/docs`，包含协议文档、治理配置、清理任务与传输适配 SPI。

## 2026-06-02
- 今日提交摘要（23 commits）：
  - 会话接入层从“入口直接驱动执行”重构为“按 `sessionId` 分发的 mailbox/worker 模型”：新增 `AgentSessionDispatcher`，同会话串行、跨会话并行；`FOLLOW/COLLECT/STEER` 统一在消息投递层决策。
  - Skill 正式进入提示词主链路：`SkillPlan` 新增原文内容承载，编排/计划/最终回答 prompt 都可注入 skill 摘要、步骤、输出约束与原文片段；同时补充中文自我介绍测试 skill。
  - 工具执行从单调用升级为“连续并发安全批次”模型：支持一次决策返回 `toolCalls`，按连续块分批，批间串行、批内并发，读工具可并发执行。
  - Hook 与 Tool 命令执行统一到共享执行内核：抽出 `CommandExecutionRequest/Policy/Result` 与共享 runner，Hook 不再走任意 `bash -lc`，改为受限脚本目录执行。
  - Sandbox 能力升级为复用操作系统能力：新增 `mac-seatbelt` 模式并设为默认；共享配置下 Hook 与 Tool 都走同一执行边界。
  - 工具提示词补齐可理解性：新增工具 description、`argsSchema`、`exampleArgs`，随后又把工具入参整体重构为 typed record，统一从 DTO 生成 schema、示例、执行绑定与参数校验。
  - Prompt 约束继续强化：显式禁止模型依赖服务端默认值偷省范围参数，并对 `cat/grep/gitDiff/gitBlame/ls/lint/compile` 给出逐项用参规则。
  - 调试与体验修复：`agent-console.html` 修正 token 渲染逻辑，不再把每个 chunk 按逐词换行显示；同时把“当前仍非真正 token 级流式输出”记入 TODO。
  - AI 客户端治理补齐：显式配置聊天模型 `connect/read timeout`，并将 Spring AI 默认重试次数收敛为 1，避免第二轮决策反复 10 秒超时。
- 关键修复：
  - 同会话追问再次开执行链、交互语义不清 -> 重构为 session mailbox 调度模型，消息接收与 Agent 执行彻底解耦。
  - Hook 仍可能绕过工具沙箱 -> 抽出共享命令执行内核，并让 Hook 与 Tool 共用同一 sandbox policy。
  - Seatbelt 默认只放行系统目录，`nvm`/用户目录 runtime 启动被拒绝 -> 动态解析实际运行时路径并加入可执行白名单。
  - 工具参数弱类型导致 prompt/schema/执行三处漂移 -> 收敛到 typed record + 统一绑定校验链路。
  - 调试页输出被拆成逐词换行 -> 改为 `TOKEN` 直接拼接，`FINAL/ASK_USER/ERROR` 才按段落换行。
  - 第二轮决策频繁 10 秒超时 -> 显式配置模型 HTTP 连接/读取超时，并关闭多次自动重试带来的额外拖延。
- 验证：
  - `git log --since="2026-06-02 00:00:00" --until="2026-06-02 23:59:59"`：识别 23 条提交（`ce616ad` 至 `8b3c84e`，后续文档同步另计）。
  - `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home PATH="$JAVA_HOME/bin:$PATH" mvn -pl lumisight-api -am compile -DskipTests`：通过，覆盖 `common/tools/mcp/hooks/skills/core/memory/api` 全链路。
  - `agent-console.html` 本地调试：token 文本连续渲染正常；同一 `sessionId` 下可继续验证 `FOLLOW/COLLECT/STEER` 串行调度。
- TODO（今日新增确认）：
  - 当前返回的是“事件流 + 最终文本聚合”，不是真正的模型 token 级流式生成；后续需要把 LLM 决策/回答阶段改造成真实 streaming。
  - 当前 `mac-seatbelt` 只是“按模板动态生成临时 profile”，还不是像 Codex 那样按工具能力与实际参数自动规划最小权限规则；后续需要补齐统一的 sandbox planner，按 `tool + args` 推导读写路径、网络权限和可执行边界。

## 2026-06-03
- 今日提交摘要（0 commits）：
  - 今日未识别到新的代码提交；当前工作聚焦于收尾校对，确认昨日已提交的会话调度、Skill 注入、共享命令执行内核与 `mac-seatbelt` 文档描述保持一致。
  - 文档同步仅追加日结记录，不补写未发生的功能增量；架构页与 README 只做必要措辞收敛，避免把“注册式 skill 引用”误写成“任意路径直读”。
- 关键修复：
  - 文档表述可能让人误解 `skillPath` 仍可直接读取任意文件 -> 收紧为“字段名保留，但值必须命中已注册 skill 引用”，与 2026-06-02 前已提交实现保持一致。
  - 日结流程在无 commit 场景下容易遗漏留痕 -> 追加“无代码提交，仅运行验证/排障”的当日记录，保证流水账连续可追踪。
- 验证：
  - `git log --since="2026-06-03 00:00:00" --until="2026-06-03 23:59:59"`：未识别到代码提交。
  - `git log -5 --date=iso`：最近提交停留在 `2026-06-02 17:56:46 +0800`（`6218cc2 docs: 同步当日提交到 CODE_FLOW 与架构文档`）。
  - `rg -n "TODO|TBD|占位|待补" CODE_FLOW.md agent-architecture.html README.md`：用于检查本次同步后是否残留占位标记。
