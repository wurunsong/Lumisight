# Multi-Agent Plan

Lumisight 的多 Agent 体系仍然采用三层渐进结构，而不是“只要有 subagent 就默认多 Agent”：

- 单 Agent：默认路径，绝大多数任务先走这里
- 单 Agent + subagent：中等复杂度路径，主 Agent 仍是唯一执行主体，只在局部分析时按需调用干净上下文的子 Agent
- Lead + team agents：重型协作路径，只在并行分工、依赖调度、fan-out/fan-in 明显有收益时升级

核心硬约束：

- 父子 Agent 上下文严格隔离，只传最小任务包
- 子 Agent 独立上下文管理，不共享完整账本
- 子 Agent 默认最小只读权限
- 子 Agent / teammate 默认禁止递归创建新的 Agent
- 只有主 Agent / Lead 有最终写仓库、编译验证、回滚、最终回答权限

## Runtime Decision

运行决策目前固定分成三步：

1. `MultiAgentModeDecider` 先判断是否保持单 Agent
2. 若任务局部复杂但整体仍适合主 Agent 主导，则允许主 Agent 在执行中调用 `task_subagent`
3. 只有显式进入 `MULTI_AGENT`，或启发式 auto-upgrade 达到阈值时，才走 orchestration + team execution 路径

当前实现状态：

- 已落地：显式 `MULTI_AGENT` 模式
- 已落地：基于问题长度、`同时/分别/并行/多个模块/前后端/测试修复` 等信号的自动升级
- 未落地：基于 repo 状态、工具可用性、历史失败率的更强 planner

## Phase 1：task_subagent

Phase 1 的目标是把 `task_subagent` 做成干净隔离的局部分析工具，而不是把主执行权交给 child。

已完成：

- 新建 child execution context
- 使用全新 session / 全新 context session
- 子 Agent 深度限制、最大轮次和超时约束
- 返回结构化结果，不回并 child 中间消息
- 子 Agent 默认禁止写仓库、写记忆、写 todo、递归 spawn
- `SUBAGENT_SPAWNED / SUBAGENT_RESULT` 事件已并入主事件流

当前边界：

- 子 Agent 本质上仍是“请求内一次性 worker”
- 最终写入、编译验证、最终答案依然只由 Lead / 主 Agent 负责

结论：`Phase 1 已完成`

## Phase 2：team execution protocol

Phase 2 的目标是引入 team mailbox 和显式生命周期，让多任务协作不再只是多个 child 顺序跑。

已完成：

- `.lumisight/teams/{teamId}/inboxes/` 文件收件箱
- `TASK_ASSIGNMENT / RESULT / IDLE / PERMISSION_REQUEST / PERMISSION_RESPONSE / SHUTDOWN_*` 结构化消息协议
- Lead 权限审批与 worker 权限申请闭环
- team task state 跟踪：`PENDING / RUNNING / SUCCEEDED / FAILED / BLOCKED / SKIPPED`
- 生命周期事件回传到主事件流
- 执行状态快照持久化到 `.lumisight/teams/{teamId}/execution-state.json`
- 基于同一 `teamId` 的 resume 恢复，不重跑已完成 task
- wave 边界暂停与恢复

当前边界：

- 现在的 team agent 仍是“请求内执行协议”，不是独立常驻后台实体
- inbox poller 不是独立服务进程，而是由当前 orchestration 驱动消费
- 还没有跨请求长期存活的 worker 角色和独立工作记忆

结论：`Phase 2 已完成基础版`

## Phase 3：topology + team orchestration

Phase 3 的目标是把 team protocol 真正接到拓扑编排里，而不是只有 mailbox 能收发消息。

已完成：

- `SERIAL_DAG / FAN_OUT_FAN_IN / HYBRID` 三种拓扑
- `DefaultOrchestratorAgent` 会基于问题形态构造多任务计划
- `MultiAgentCoordinator` 在主执行链路里返回完整 `executionState`
- 依赖波次构建、失败依赖阻断、HYBRID 模式下的继续收敛
- 同一 wave 的 team worker 真实并发执行

当前边界：

- 计划生成仍是启发式规则，不是更强的 planner
- 角色仍偏通用 worker，没有长期专业化身份
- fan-in 总结还比较轻，缺少更强的冲突消解和证据排序

结论：`Phase 3 已完成基础版`

## 已落地能力清单

按当前实现，已经具备：

- `MULTI_AGENT` 运行模式接入主 Agent 入口
- 多 Agent 计划事件、task 状态事件、team 生命周期事件
- `task_subagent` 隔离执行与最小权限
- Lead/team mailbox 协议
- team worker 权限申请与批准
- 拓扑驱动的 wave 调度
- 同 wave 并发执行
- 执行状态持久化
- wave 边界暂停与 `resume`

## 演示口径

如果按“学习 / 个人项目 / 找工作展示”的标准来讲，这一块现在已经足够作为一个完成态亮点使用：

- 可以演示：显式 `MULTI_AGENT` 模式
- 可以演示：自动升级到多 Agent 的启发式判断
- 可以演示：`task_subagent` 的隔离执行
- 可以演示：Lead/team 协议、波次调度、并发执行、状态持久化与 `resume`
- 可以明确边界：当前是“请求内 Lead 编排系统”，不是长期自治团队

对当前项目来说，最重要的不是继续无限扩能力，而是把这几个点讲清楚、演示顺畅、边界明确。

## 仍然缺什么

下一阶段最值得继续补的是这些，而不是再扩名词：

1. 更强的 orchestration planner
2. 长期存活的 team role 与跨请求异步 worker
3. team 级共享工作记忆 / artifact 汇聚层
4. 更细粒度的权限解释与审批策略
5. 端到端集成验证，而不只是 focused unit test

## Remaining Build Order

建议后续顺序：

1. 提升 planner 质量，让任务拆解和拓扑选择更像真实开发代理
2. 引入更长期的 role identity，而不是每次请求临时生成 worker
3. 建立 team 级共享 artifact / scratchpad / evidence 汇聚层
4. 扩充多 Agent 集成测试，覆盖真实主链路与恢复行为
5. 再考虑更复杂的 team 间协作或跨队列通信

## Short Version

一句话总结当前状态：

> Lumisight 的多 Agent 已经不再是“只有骨架”的阶段，而是具备了主链路接入、team 协议、拓扑调度、并发执行、状态持久化和恢复能力的基础版实现；但它现在更准确地说是“请求内 Lead 编排系统”，还不是长期自治的多角色开发团队。
