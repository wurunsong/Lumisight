---
name: daily-commit-architecture-sync
description: At end of day, summarize today's git commits into CODE_FLOW.md and sync architecture updates into agent-architecture.html based only on committed facts.
metadata:
  short-description: Sync daily commits to docs and architecture
---

# Daily Commit Architecture Sync Skill

## Purpose
在每日收尾时执行四件事：
1. 读取“今天”的 Git commit 记录。
2. 将今日变更总结追加到 `CODE_FLOW.md`（仅追加，不删除历史）。
3. 根据今日变更完善 `agent-architecture.html`，并保持结构顺序：先整体框架，再分点说明。
4. 同步完善 `README.md`，确保对外文档与当日已提交能力一致。

## Inputs
- 日期：默认使用本地当天（可手动指定 `YYYY-MM-DD`）。
- 分支：默认当前分支。

## Files To Update
- `/Users/lilac/ai/Lumisight/CODE_FLOW.md`
- `/Users/lilac/ai/Lumisight/agent-architecture.html`
- `/Users/lilac/ai/Lumisight/README.md`

## Mandatory Rules
- 只基于当日 commit 事实更新，不臆造未提交功能。
- `CODE_FLOW.md` 只追加到当天小节，不重写旧日期内容。
- `agent-architecture.html` 的组织顺序必须为：
  1. 整体框架（端到端、分层、主流程）
  2. 分点细化（模块职责、数据流、运行策略、异常与降级）
- `CODE_FLOW.md` 与 `agent-architecture.html` 仅本地使用，不纳入 Git 管理（不提交、不建议提交）。
- `README.md` 为仓库对外说明文档，可按当日提交事实更新并纳入 Git。
- 不写入任何明文密钥、令牌、个人敏感信息。
- 若当天无 commit，`CODE_FLOW.md` 追加“无代码提交，仅运行验证/排障”的记录，HTML仅做必要校对不做虚构增量。

## Execution Steps

### Step 1) Collect Today Commits
使用以下命令抓取当天 commit：

```bash
git log --since="$(date +%F) 00:00:00" --until="$(date +%F) 23:59:59" \
  --pretty=format:'%h|%ad|%an|%s' --date=iso
```

同时抓取变更文件统计：

```bash
git log --since="$(date +%F) 00:00:00" --until="$(date +%F) 23:59:59" \
  --stat --pretty=format:'commit %h %s'
```

### Step 2) Summarize Into CODE_FLOW.md
在 `CODE_FLOW.md` 的当天日期小节（例如 `## 2026-05-24`）下追加条目：
- 今日能力新增（接口/模块/配置）
- 行为变化（默认值、开关、分支逻辑）
- 排障与修复（错误类型 -> 修复动作）
- 验证结果（编译、运行、接口调用）

推荐追加模板：

```md
- 今日提交摘要（<commit-count> commits）：
  - <能力/功能变更 1>
  - <能力/功能变更 2>
- 关键修复：
  - <问题现象> -> <修复方案>
- 验证：
  - <命令或接口>：<结果>
```

### Step 3) Update agent-architecture.html
根据当天变更更新 HTML 的“架构叙述”内容，保持先总后分：

1. 先更新整体框架区块：
- 主流程（离线构建 -> 在线运行）
- 核心数据链路（Parser/VectorStore/KG/Runtime）

2. 再更新分点区块：
- 各模块职责变化（API/Core/Tools/Memory/Skills）
- 向量链路变化（模型、批次、集合维度、重试/超时策略）
- 增量策略变化（baseline commit、跳过条件、强制重建方式）
- 可观测性变化（新增日志点、错误抛出策略）

3. 若当天涉及接口行为变化，在 HTML 的“执行流/验收标准”同步体现。

### Step 4) Update README.md
根据当日提交同步更新 README（仅写已提交事实）：
- 新增/更新接口清单（如 KG 与 Vector 能力变化）。
- 运行与配置变化（例如 core 配置迁移、环境变量要求、默认开关）。
- 使用方式变化（例如增量入库、repo 解析、启动注意事项）。

### Step 5) Consistency Check
更新完成后执行：

```bash
rg -n "TODO|TBD|占位|待补" CODE_FLOW.md agent-architecture.html README.md
```

并人工确认：
- `CODE_FLOW.md` 是否仅追加。
- HTML 是否保持“先整体后分点”。
- README/HTML 描述是否与今日 commit 一致。

## Output Contract
执行结束后输出：
1. 当天识别到的 commit 数量。
2. `CODE_FLOW.md` 新增要点（3-8条）。
3. `agent-architecture.html` 更新区块列表。
4. `README.md` 更新要点（2-6条）。
5. 本地维护结论（固定为“`CODE_FLOW.md` 与 `agent-architecture.html` 为本地文件，不建议提交”）。
6. README 是否建议提交（`yes/no`）及建议 commit message。

## Suggested Commit Message
- `docs: sync README with today's committed capabilities`
