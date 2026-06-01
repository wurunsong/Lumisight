---
name: project-progress-sync-reader
description: Read CODE_FLOW and architecture docs, cross-check consistency, and output actionable next steps.
metadata:
  short-description: Read project progress and sync gaps
---

# Project Progress Sync Reader Skill

## Purpose
通过阅读本地文档快速理解项目进展，并输出可执行的下一步建议：
1. 读取 `CODE_FLOW.md`（按日期与新增记录识别近期变更）。
2. 读取 `agent-architecture.html`（提取当前架构主流程与模块状态）。
3. 交叉校验两者是否一致并识别风险点。
4. 输出“当前进展摘要 + 差异点 + 下一步行动”。

## Inputs
- 日期范围：默认最近 1-3 天（可指定 `YYYY-MM-DD`）。
- 输出粒度：默认“简版”；可选“详细版”。

## Files To Read
- `<repo-root>/CODE_FLOW.md`
- `<repo-root>/agent-architecture.html`
- （可选）`<repo-root>/README.md` 用于对外文档一致性校验。

## Mandatory Rules
- 仅基于文件中的已存在事实输出，不臆造功能状态。
- 对日期敏感信息必须带绝对日期（如 `2026-05-24`）。
- 若两份文档冲突，先标注冲突，再给出建议修正位置。
- 不输出任何密钥、令牌、个人敏感信息。
- 默认不修改代码；仅在用户明确要求时再执行文档修订。

## Execution Steps

### Step 1) Read Progress Ledger
读取 `CODE_FLOW.md`：
- 定位最近日期小节（`## YYYY-MM-DD`）。
- 提取：能力新增、行为变化、关键修复、验证结果。

### Step 2) Read Architecture Narrative
读取 `agent-architecture.html`：
- 提取“整体框架/主流程”描述。
- 提取“模块职责、数据流、运行策略、异常降级”段落。

### Step 3) Cross-check Consistency
对比两份文档：
- 已落地能力是否一致（接口、模块、增量策略、配置策略）。
- 验证信息是否能支撑当前结论。
- 是否存在“流水账有、架构文档无”或“架构文档有、流水账无”的缺口。

### Step 4) Produce Progress Brief
输出四段内容：
1. 当前进展（3-6条）
2. 已知风险/缺口（1-4条）
3. 建议下一步（1-5条，按优先级）
4. 文档同步建议（是否需要更新 README / CODE_FLOW / HTML）

## Output Contract
执行结束后输出：
1. 覆盖的日期范围。
2. 当前进展摘要（3-6条）。
3. 冲突或缺口列表（若无写“未发现明显冲突”）。
4. 下一步建议（按优先级编号）。
5. 是否建议立即执行文档同步（`yes/no`）与目标文件。

## Suggested Follow-up
- `docs: align CODE_FLOW and architecture narrative`
