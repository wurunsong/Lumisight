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
