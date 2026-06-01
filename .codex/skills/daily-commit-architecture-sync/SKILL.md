---
name: daily-commit-architecture-sync
description: At end of day, summarize today's git commits into CODE_FLOW.md and sync architecture updates into agent-architecture.html based only on committed facts.
metadata:
  short-description: Sync daily commits to docs and architecture
---

# Daily Commit Architecture Sync Skill

## Purpose
在每日收尾时执行五件事：
1. 读取“今天”的 Git commit 记录。
2. 将今日变更清晰总结到 `CODE_FLOW.md`（仅追加，不删除历史）。
3. 在 `agent-architecture.html` 中清晰展示当日架构/流程变化（先整体后分点）。
4. 同步完善 `README.md`，确保对外文档与当日已提交能力一致。
5. 将上述文档更新提交并 push 到远程仓库。

## Inputs
- 日期：默认使用本地当天（可手动指定 `YYYY-MM-DD`）。
- 分支：默认当前分支。

## Files To Update
- `<repo-root>/CODE_FLOW.md`
- `<repo-root>/agent-architecture.html`
- `<repo-root>/README.md`

## Mandatory Rules
- 只基于当日 commit 事实更新，不臆造未提交功能。
- `CODE_FLOW.md` 只追加到当天小节，不重写旧日期内容。
- `agent-architecture.html` 必须维护为“统一融合架构视图”，不按日期新增“今日增量”章节；历史变更要融合进现有模块描述。
- `agent-architecture.html` 的组织顺序必须为：
  1. 整体框架（端到端、分层、主流程）
  2. 分点细化（模块职责、数据流、运行策略、异常与降级）
- `agent-architecture.html` 必须“突出流程与技术亮点”，避免堆砌参数级、实现级细节：
  - 优先展示：端到端流程、在线执行主链路、关键技术差异点（如协议解耦、会话控制、安全闭环）。
  - 每个技术亮点必须展开为“完整链路介绍”：触发条件 -> 核心处理 -> 约束/保护 -> 输出结果（或业务价值）。
  - 弱化/省略：`xxx.yyy.zzz` 形式的配置项清单、字段级罗列、与主流程无关的实现细枝末节。
- 视觉呈现必须保证可读性与对称性：
  - 字体清晰统一，避免默认系统混搭导致观感松散。
  - 流程节点与箭头对齐，卡片网格尽量对称，留白一致。
  - 避免“信息墙”式堆叠，优先“少而清楚”的结构表达。
- `CODE_FLOW.md` 与 `agent-architecture.html` 必须清晰反映“当天变更”并纳入 Git。
- `README.md` 为仓库对外说明文档，按当日提交事实更新并纳入 Git。
- 当天开发过程必须按“每个独立功能一个 commit”执行（小步提交、可回滚）；禁止将多个无关功能合并为单一大 commit。
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

强制要求（清晰记录）：
- 每个条目要能对应到当日 commit 事实（功能/行为/修复/验证）。
- 需要明确“改了什么、为什么改、结果是什么”，避免仅写标题。

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
根据当天变更更新 HTML 的“架构叙述”内容，保持先总后分，并融合进统一架构主视图（禁止按日期堆叠）：

1. 先更新整体框架区块：
- 主流程（离线构建 -> 在线运行）
- 核心数据链路（Parser/VectorStore/KG/Runtime）

2. 再更新分点区块：
- 各模块职责变化（API/Core/Tools/Memory/Skills）
- 向量链路变化（模型、批次、集合维度、重试/超时策略）
- 增量策略变化（baseline commit、跳过条件、强制重建方式）
- 可观测性变化（新增日志点、错误抛出策略）

3. 若当天涉及接口行为变化，在 HTML 的“执行流/验收标准”同步体现。

4. 视觉与信息密度控制：
- 页面主标题下先给出一句话架构定位（当前统一视图的边界与目的）。
- 主流程图保持单条主链路，不并列过多支线；支线能力放到“技术亮点”区块。
- 每个亮点区块建议 4-6 条“链路步骤”要点，必须能读出因果顺序，而非关键词堆叠。

强制要求（清晰展示）：
- HTML 中必须新增或更新能直接看出“当前架构已吸收当天新增/调整点”的区块或条目。
- 不允许新增“今日架构增量（YYYY-MM-DD）”这类日期章节。
- 不允许只做格式微调而不体现真实变更。
- 不允许将页面重心放在细枝末节配置清单，而弱化核心流程本身。
- 不允许仅写“亮点标题 + 口号式短句”；必须写清楚亮点链路的输入、处理、保护、输出。

### Step 4) Update README.md
根据当日提交同步更新 README（仅写已提交事实）：
- 新增/更新接口清单（如 KG 与 Vector 能力变化）。
- 运行与配置变化（例如 core 配置迁移、环境变量要求、默认开关）。
- 使用方式变化（例如增量入库、repo 解析、启动注意事项）。

补充规则（README 重写）：
- 当出现大规模架构调整、目录重构、核心流程迁移、接口语义明显变化时，可“重写 README”的相关章节，必要时可重写整份 README。
- 重写后仍必须满足：只基于已提交事实，不引入未实现能力，不删除仍有效的关键信息（如快速启动、核心能力、配置要求）。
- 若执行了重写，输出中必须标注“README 重写：yes”，并给出重写原因（1-3条）。

### Step 5) Consistency Check
更新完成后执行：

```bash
rg -n "TODO|TBD|占位|待补" CODE_FLOW.md agent-architecture.html README.md
```

并人工确认：
- `CODE_FLOW.md` 是否仅追加。
- HTML 是否保持“先整体后分点”。
- README/HTML 描述是否与今日 commit 一致。

### Step 6) Commit And Push
在一致性检查通过后，执行：
1. 先确认当天功能提交粒度符合“每个独立功能一个 commit”；若发现多功能混合提交，先在输出中标注风险并给出拆分建议。
2. `git add CODE_FLOW.md agent-architecture.html README.md`
3. 使用中文 commit message 提交文档同步（文档同步 commit 与功能 commit 分离）。
4. `git push` 到当前分支对应远程。

若 push 失败，必须在输出中说明失败原因与下一步建议。

## Output Contract
执行结束后输出：
1. 当天识别到的 commit 数量。
2. `CODE_FLOW.md` 新增要点（3-8条）。
3. `agent-architecture.html` 更新区块列表。
4. `README.md` 更新要点（2-6条）。
5. 本次文档 commit hash 与 push 结果（成功/失败）。

## Suggested Commit Message
- `docs: 同步当日提交到 CODE_FLOW 与架构文档`
