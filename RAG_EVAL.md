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
- 复用正式入库的 `CodeChunkSplitter`，把 `qualifiedName + code chunk` 作为被检索文档
- 默认基于 `SimpleVectorStore + EmbeddingModel` 临时构建评测语料库
- 可选 `backend=milvus`，写入独立评测 collection，不复用正常 RAG 的 Milvus collection
- 可选 `retrievalMode=bm25/hybrid`，在 `.lumisight/evals/rag/lucene-bm25/{runId}` 构建本地 Lucene BM25 倒排索引，不接入正常向量主链路
- 可选 `rerank=true`，对初召回候选做本地融合重排
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
  "backend": "simple",
  "retrievalMode": "hybrid",
  "rerank": true,
  "candidateTopN": 50,
  "maxExamples": 200,
  "minDocstringLength": 12,
  "maxChunkChars": 2600,
  "overlapChars": 240,
  "topKValues": [1, 5, 10],
  "persistReport": true
}
```

返回内容包括：

- 数据集路径与语言
- 本次使用的向量后端：`simple` 或 `milvus`
- 本次使用的检索模式：`vector`、`bm25` 或 `hybrid`
- 是否启用本地融合重排，以及初召回候选池大小
- 语料规模、query 数量。语料规模统计的是切分后的 chunk 数量，不是原始函数样本数。
- `Recall@1 / Recall@5 / Recall@10`
- `MRR`
- `mean NDCG`
- 若干失败样例
- 落盘报告路径

## 报告落盘

默认会把报告写到：

- `.lumisight/evals/rag/*.json`

这样你后面可以直接拿这些结果做展示、截图或者继续人工分析失败样例。

## 本地 BM25 与重排

`retrievalMode` 只影响当前 CodeSearchNet 评测链路，不会改变正常 RAG / Milvus 主链路。

- `vector`：默认模式，只用向量召回。
- `bm25`：只用本地 Lucene BM25。评测开始时会基于本次 corpus 在 `.lumisight/evals/rag/lucene-bm25/{runId}` 建文件索引。
- `hybrid`：同时跑向量召回和 BM25，合并候选后再排序。

响应里的 `bm25IndexPath` 会返回本次 Lucene 索引目录；未启用 `bm25/hybrid` 时为 `null`。

`rerank=true` 时，会先取 `candidateTopN` 个初召回候选，然后用本地融合分数重排：

- 向量相似度归一化分数
- BM25 归一化分数
- reciprocal-rank fusion
- query 与 `qualified_name/path/repo` 的精确命中奖励

这不是 cross-encoder 或 LLM reranker，所以不需要额外模型服务；它的目标是先评估“Lucene BM25 + 向量混合检索 + 轻量重排”是否比纯向量更稳。

## 可选 Milvus 后端

默认评测后端是 `simple`，它是进程内内存向量库，适合快速验证 embedding、chunk size、overlap 对召回的影响。

如果你想把 Milvus 的索引、距离计算和真实向量库行为也纳入评测，可以显式开启独立评测后端：

```yaml
lumisight:
  rag-eval:
    milvus:
      enabled: true
      host: 127.0.0.1
      port: 19530
      database-name: default
      collection-name: rag_eval_codesearchnet_1024
      clear-before-run: true
```

然后请求里传：

```json
{
  "backend": "milvus"
}
```

注意：

- 这条链路默认关闭，不会因为正常 RAG 开了 Milvus 就自动启用。
- 它使用 `lumisight.rag-eval.milvus.*` 独立配置和独立 collection，避免污染线上/正常开发用的 `lumisight_vectors_1024`。
- `clear-before-run=true` 会在每次评测前清掉评测文档，保证指标更可复现。

## 额外说明

如果 `lumisight.vector.enabled=true`，当前仓库内的 `searchHybridVector` 也已经接上真实的 `VectorStore` provider，不再只是 noop 占位。
