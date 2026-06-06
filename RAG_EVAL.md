# RAG Eval

Lumisight 现在提供一条面向代码检索场景的轻量评测链路，优先支持 `CodeSearchNet` 风格数据集。

## 推荐数据集

当前最推荐的公开测试集是 GitHub 官方开源的 `CodeSearchNet`：

- 仓库：`https://github.com/github/CodeSearchNet`
- Java 数据：`https://s3.amazonaws.com/code-search-net/CodeSearchNet/v2/java.zip`

为什么选它：

- 它本身就是自然语言到代码片段的检索 benchmark
- 有 Java 子集，和 Lumisight 当前仓库场景接近
- 数据结构稳定，适合先做一个能跑的个人项目评测能力

## 当前评测方式

当前实现的是 `CodeSearchNet docstring -> code retrieval` 评测：

- 从 `jsonl / jsonl.gz` 里读取 `docstring` 作为 query
- 用 `qualifiedName + code` 作为被检索文档
- 基于 `SimpleVectorStore + EmbeddingModel` 临时构建评测语料库
- 输出 `Recall@K / MRR / mean NDCG / mean rank`

说明：

- 这条链路优先验证“代码向量检索是否能把目标方法召回回来”
- 它比完整产品级 benchmark 轻很多，但对个人项目展示已经够用

## 接口

- `POST /api/lumisight/rag-eval/codesearchnet/run`

请求示例：

```json
{
  "datasetPath": "/absolute/path/to/codesearchnet/java/final/jsonl/test",
  "language": "java",
  "partition": "test",
  "maxExamples": 200,
  "minDocstringLength": 12,
  "topKValues": [1, 5, 10],
  "persistReport": true
}
```

返回内容包括：

- 数据集路径与语言
- 语料规模、query 数量
- `Recall@1 / Recall@5 / Recall@10`
- `MRR`
- `mean NDCG`
- 若干失败样例
- 落盘报告路径

## 报告落盘

默认会把报告写到：

- `.lumisight/evals/rag/*.json`

这样你后面可以直接拿这些结果做展示、截图或者继续人工分析失败样例。

## 额外说明

如果 `lumisight.vector.enabled=true`，当前仓库内的 `searchHybridVector` 也已经接上真实的 `VectorStore` provider，不再只是 noop 占位。
