# Lumisight 代码流程记录

## 1. 知识图谱离线构建主流程

入口类：`com.lumisight.tools.kg.cli.KnowledgeGraphBuildMain`

API 入口：`POST /api/kg/build`

执行顺序：
1. 读取参数：`repoRoot`（目标代码仓库）和 `snapshotPath`（快照输出路径）
2. 调用 `IncrementalGraphBuilder.build(repoRoot, snapshotPath)`
3. 增量构建完成后输出统计信息（节点数、边数、索引文件数）

---

## 2. 核心类职责

### 2.1 `IncrementalGraphBuilder`
职责：增量构建总控

主要步骤：
1. 从 `GraphSnapshotStore` 加载历史快照
2. 扫描当前仓库全部 `.java` 文件并计算哈希
3. 比较哈希，识别：
   - 新增/变更文件（需要重解析）
   - 删除文件（需要清理图数据）
4. 删除受影响文件的旧节点和旧边
5. 对新增/变更文件调用 `JavaCodeGraphParser` 重新解析
6. 合并新解析结果到快照
7. 清理悬挂边（边端点不存在）
8. 保存新快照

### 2.2 `JavaCodeGraphParser`
职责：单文件语法解析与图片段构建

节点建模：
1. `ModuleNode`
2. `PackageNode`
3. `ClassNode`
4. `MethodNode`

说明：上述 Node 类分别封装自身元信息，并通过 `toGraphNode()` 转换为统一图存储模型。

当前支持：
1. 节点：`module/package/class/method`
2. 边：
   - `MODULE_CONTAINS_PACKAGE`
   - `PACKAGE_CONTAINS_CLASS`
   - `CLASS_CONTAINS_METHOD`
   - `METHOD_CALLS_METHOD`（当前文件内基于方法名+参数个数匹配）

### 2.3 `GraphSnapshotStore`
职责：快照持久化

说明：
1. 从 JSON 文件加载历史快照
2. 将新快照写回 JSON 文件
3. 支持 `Instant` 时间序列化

### 2.4 工具类
1. `HashUtils`：计算文件 SHA-256
2. `NodeIdUtils`：统一生成节点 ID 与边 ID

---

## 3. 数据模型

### 3.1 节点
- `GraphNode(id, type, name, qualifiedName, sourceFile)`

### 3.2 边
- `GraphEdge(id, fromNodeId, toNodeId, type, sourceFile)`

### 3.3 快照
- `GraphSnapshot`
  - `repoRoot`
  - `generatedAt`
  - `nodes`
  - `edges`
  - `fileHashes`

---

## 4. 增量策略说明

1. 首次运行：无历史快照 => 全量解析
2. 后续运行：
   - 对比 `fileHashes` 仅解析新增/修改文件
   - 删除文件对应图数据
3. 通过 `sourceFile` 快速定位并清理旧节点/边

---

## 5. 已知限制（当前版本）

1. `METHOD_CALLS_METHOD` 目前主要识别同文件内调用关系
2. 跨文件/跨类的精确方法绑定需要补充符号解析策略
3. 目前结果写入本地快照，尚未接入 Nebula 写入器

---

## 6. 下一步计划

1. 实现 `snapshot -> Nebula` 导入器
2. 增强跨文件方法调用解析
3. 增加单元测试与样例仓库回归测试
