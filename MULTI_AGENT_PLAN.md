# Multi-Agent Plan

Lumisight 的多 Agent 体系采用三层渐进结构，而不是“只要有 subagent 就默认多 Agent”：

- 单 Agent：默认路径，绝大多数任务先走这里
- 单 Agent + subagent：中等复杂度路径，主 Agent 仍是唯一执行主体，只在局部分析时按需调用干净上下文的子 Agent
- Lead + team agents：重型协作路径，只有在长期并行分工、异步通信、持续 fan-in/fan-out 明显有收益时才升级

核心硬约束：

- 父子 Agent 上下文严格隔离，只传最小任务包
- 子 Agent 独立上下文管理，不共享完整账本
- 子 Agent 默认最小只读权限
- 子 Agent / teammate 默认禁止递归创建新的 Agent
- 只有主 Agent / Lead 有最终写仓库、编译验证、回滚、最终回答权限

## Runtime Decision

入口仍保留现有 `/api/lumisight/agent/run`，但运行决策固定分成三步：

1. orchestrator 先判断是否保持单 Agent
2. 若任务局部复杂但整体仍适合主 Agent 主导，则允许主 Agent 在执行中调用 subagent
3. 只有当任务需要多个长期角色并行协作、异步通信、分层调度时，才升级到 team agent 模式

## Phase 1

Phase 1 先实现 `task_subagent`：

- 新建 child execution context
- 使用全新 messages / 全新 context session
- 带安全轮次上限
- 执行完成后只返回结构化结果
- child 中间过程丢弃，不合并回主对话

child 默认只读，不能递归创建新的 agent，也不能直接写仓库。

## Phase 2

Phase 2 引入 team agent：

- `.lumisight/teams/{teamId}/inboxes/` 文件收件箱
- 结构化消息协议
- inbox poller
- idle / permission / shutdown 协议

## Phase 3

Phase 3 引入统一拓扑：

- SERIAL_DAG
- FAN_OUT_FAN_IN
- HYBRID

角色类型与调度拓扑正交组合：

- SubAgent 更适合一次性 fan-out
- TeamAgent 更适合长期协作 fan-out

## Implementation Order

1. 持久化本方案文档
2. 扩展运行模式与 orchestration 状态模型
3. 实现 `task_subagent`
4. 接入 child context isolation / permission policy / anti-recursion
5. 接入 subagent 事件流
6. 实现 team inbox bus 与 team agent 生命周期骨架
7. 再做 fan-out/fan-in 与 hybrid 调度
