# Lumisight 快速续聊指南（CODE_FLOW）

## 1. 当前状态（简版）
- 图谱写入：直接写 Nebula（不再需要 `snapshotPath` 入参）。
- 更新门禁：仅允许 `main/master`，且当前提交必须领先图谱记录提交。
- 增量策略：首次全量，后续按 Git diff 增量。
- 删除策略（主路径）：逻辑删除（`status=1`）。
- Nebula 已打通：已处理 Space/Schema 初始化时序问题。

## 2. API / CLI 入口

### API
- `POST /api/kg/build`
- 入参：`repoRoot`
- 出参关键：`updated`、`skipReason`、`gitBranch`、`gitCommit`

示例：
```bash
curl -X POST http://127.0.0.1:8080/api/kg/build \
  -H "Content-Type: application/json" \
  -d '{"repoRoot":"/path/to/repo"}'
```

### CLI
- 主类：`com.lumisight.tools.kg.cli.KnowledgeGraphBuildMain`
- 参数：`<repoRoot>`

## 3. 增量构建流程（当前代码）
1. 读取仓库 Git 上下文：`branch + HEAD commit`
2. 校验分支：非 `main/master` 直接失败
3. 读取 `repo_meta.git_commit` 作为图谱基线提交
4. 基线与当前提交关系检查：
   - 相同：跳过
   - 非祖先关系：跳过
5. 若首次（无基线）：全量解析全部 `.java`
6. 若非首次：`git diff <base>..HEAD -- *.java` 获取变更文件
7. 对每个变更文件：
   - 解析旧版与新版
   - 按类名并集处理
   - 对“旧有新无”的方法：`status=1`（逻辑删除）并标记相关边删除
   - 对保留/新增方法：重写节点
   - 类节点同理（存在则重写，不存在则逻辑删除）
8. 更新 `repo_meta`（仓库元结点）

## 4. Nebula 模型
- Space：`kg_工程名`（例如仓库目录名 `rpc` -> `kg_rpc`）
- Tag：`kg_node`
- Edge：`kg_rel`
- Tag：`repo_meta`

### 4.0 Space 规则
- 代码会按 `repoName` 自动派生 Space（前缀 `kg_`，并做合法字符规范化）。
- 首次构建可由程序自动创建 Space；排障时也可手工用 `nebula-console` 创建。

### 4.1 状态字段
- `kg_node.status`：`0=ACTIVE`，`1=DELETED`
- `kg_rel.status`：`0=ACTIVE`，`1=DELETED`

### 4.2 行号字段
- 方法节点包含：`start_line`、`end_line`

### 4.3 仓库元结点（repo_meta）
- `repo_name`
- `repo_root`
- `git_branch`
- `git_commit`
- `updated_at`
- `tracked_file_count/node_count/edge_count`（非全量场景可能写 `-1`）

## 5. ID 规则
- 节点 ID：`N_<TYPE>_<sha256(type|qualifiedName)>`
- 边 ID：`E_<TYPE>_<sha256(type|from|to)>`

说明：
- 边可先于目标点出现，只要 ID 规则一致，后续补点可对齐。

## 6. 重要说明（当前仍需注意）
- 主流程已经改为逻辑删除。
- `NebulaGraphStore` 里仍保留 `deleteVertices`（物理删除接口），当前主流程未调用，但建议后续移除，避免误用。
- `SessionPool` 模式下禁止单独执行 `USE SPACE`，当前代码已移除该语句。
- 新建 Space/Tag 后存在元数据传播延迟：当前实现已增加 SessionPool 初始化重试与 Schema 就绪等待。

## 7. 常用命令
```bash
./scripts/start-infra.sh
./scripts/check-infra.sh
./scripts/stop-infra.sh

mvn -pl lumisight-tools,lumisight-api -am -DskipTests compile
```

## 8. 关键代码位置
- 构建主流程：`lumisight-tools/.../IncrementalGraphBuilder.java`
- Nebula 读写：`lumisight-tools/.../NebulaGraphStore.java`
- 解析器：`lumisight-tools/.../JavaCodeGraphParser.java`
- API 控制器：`lumisight-api/.../KnowledgeGraphController.java`
