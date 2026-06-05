---
name: architecture-flow-html-designer
description: Create or refresh polished architecture HTML pages that explain a system as an information flow first, then keep technical highlights in a secondary section. Use for pages like agent architecture, memory/context architecture, protocol flow, or similar repo-local system diagrams where visual quality and clear flow matter.
metadata:
  short-description: Design architecture flow HTML pages
---

# Architecture Flow HTML Designer

## Purpose
把架构页写成“先一眼看懂主流程，再往下看技术亮点”的漂亮 HTML 页面，而不是说明书式卡片堆砌。

适用场景：
- 新建或重写 `agent-architecture.html`
- 新建或重写 `memory-context-management.html`
- 新建协议流、系统流、执行流、记忆流等架构页面
- 用户明确说“画得好看点”“强调流程”“不要太多技术细节”“保持这个风格”

## Files To Read First
- 当前目标 HTML 文件
- 同目录下已被用户认可的 HTML 页面
- `CODE_FLOW.md` 或相关实现文档，用来确认只写已提交事实

优先参考：
- `<repo-root>/agent-architecture.html`
- `<repo-root>/memory-context-management.html`

## Core Outcome
产物应同时满足四点：

1. 第一屏先讲信息流，不先讲实现细节
2. 视觉上像“架构海报”，不是默认白底说明书
3. 技术亮点保留，但放在主流程之后
4. 文字解释服务于流程，不反过来淹没流程

## Mandatory Rules
- 先提炼主流程，再写页面；不要边想边堆卡片
- 主流程默认控制在 `5-7` 个步骤
- 页面上半部分优先展示“请求/信息如何流动”
- 特殊分支（如多 Agent、记忆系统、协议分发）单独做第二层流程图
- 技术亮点必须保留在下面，但它们是补充层，不是首页主角
- 不要把配置项、类名清单、参数表塞进主流程区
- 只写已实现事实；如果能力还只是骨架，要明确写成边界，不要拔高
- 如果用户已经认可某一张页面的风格，后续页面默认延续同一套视觉语言

## Page Structure

默认采用 4 段结构：

### 1) Hero
- 大标题
- 一句定位
- 1 行解释“这页主要讲什么，不讲什么”
- 2-4 个 badge 可选，用来快速标识主题

### 2) Main Flow
- 页面最重要部分
- 用横向 timeline 或大卡片流描述主信息流
- 每步都回答：
  - 发生了什么
  - 为什么这一步存在
  - 输出流向哪里

### 3) Branch Flow
- 当系统存在关键支线时使用
- 例如：
  - Lead / Worker 多 Agent 流
  - Session Context / Long-Term Memory 双层流
  - 协议入口 / 会话分发 / 运行时回流
- 推荐用泳道图、双栏图或主流程 + side panel

### 4) Technical Highlights
- 放在下半部分
- 用 3-6 个卡片保留工程亮点
- 每个亮点只讲“为什么重要”，不写成长段实现文档

## Visual Style

沿用这套视觉方向：

- 深色渐变 Hero
- 浅色雾面 Section 容器
- 大圆角、柔和阴影、明显留白
- 主流程卡片使用编号
- 颜色分层：
  - 蓝色：主流程/控制层
  - 绿色：协作/增强层
  - 金色：计划/调度层
  - 淡紫：收敛/结果层
- 文字密度控制在“扫一眼能懂，停下来能读”

避免：
- 默认浏览器风格方块
- 一整屏等宽小卡片
- 大段无层次说明文字
- 把所有区块都画成同一种组件

## Content Reduction Rules

当原始材料很多时，先压缩成下面三层：

### Layer A: 主流程必须出现
- 请求从哪来
- 系统如何判断路径
- 核心工作如何展开
- 结果如何回流

### Layer B: 存在性补充
- 记忆系统存在于哪里
- 上下文管理存在于哪里
- RAG / 图谱 / 外挂证据存在于哪里

### Layer C: 技术亮点
- 为什么这个系统和普通流水线不同
- 为什么它能恢复/压缩/并发/隔离

如果一个细节不属于这三层，通常不该出现在这页里。

## Writing Style
- 用“信息流”语言，不用“模块列表”语言
- 多写“进入、判断、派发、回收、沉淀、恢复、收敛”
- 少写“该类负责、该配置为、该字段是”
- 每段文字优先解释作用，不优先解释实现名词

## HTML Workflow

1. 先判断这页的唯一主角是谁
2. 写出 `5-7` 步主流程草稿
3. 再判断是否需要一个 branch flow
4. 最后才补技术亮点区
5. 复查：如果删掉下半部分，第一屏是否仍能讲清系统怎么运作

## Good Signs
- 用户一打开页面先看图，不是先看说明文字
- 第一屏就能回答“这个系统怎么流”
- 下半部分继续有工程密度，但不压垮页面
- 不同页面风格统一，但每页主流程都围绕自己的主题

## Bad Signs
- 页面像接口文档
- 每一块都是同款方块
- 技术亮点抢了主流程位置
- 用户看完只记住名词，记不住流向

## Suggested Reuse Pattern

如果已经有一张用户认可的页面：

- 直接复用它的视觉 token、section 节奏和卡片层次
- 只替换主流程内容，不要重新发明新 UI 语言
- 新页面要看起来像同一套架构站，而不是另一套模板
