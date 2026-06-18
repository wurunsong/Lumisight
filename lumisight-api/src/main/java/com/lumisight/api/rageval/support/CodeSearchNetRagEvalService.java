package com.lumisight.api.rageval.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.api.rageval.dto.CodeSearchNetEvalMetricsResponse;
import com.lumisight.api.rageval.dto.CodeSearchNetEvalMissResponse;
import com.lumisight.api.rageval.dto.CodeSearchNetEvalResponse;
import com.lumisight.api.rageval.dto.CodeSearchNetEvalRunRequest;
import com.lumisight.tools.vector.service.CodeChunkSplitter;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * CodeSearchNet 风格的轻量 RAG / 向量检索评测。
 *
 * 这类评测不是让模型回答问题，而是只评估“检索器能不能把正确代码找回来”：
 * - query：CodeSearchNet 里的 docstring，自然语言描述一段代码做什么。
 * - corpus：同一批样本里的 code，先复用正式入库的 CodeChunkSplitter 切成 chunk，再写进评测向量库。
 * - ground truth：当前 query 原本对应的那段 code，也就是 expectedId。
 *
 * backend=simple 时使用内存向量库，适合快速验证 embedding/chunking 参数。
 * backend=milvus 时使用独立的 eval collection，适合把 Milvus 索引、距离计算和真实召回链路也纳入评测。
 * retrievalMode=vector/bm25/hybrid 只影响这条评测链路：vector 走向量召回，bm25 走本地 Lucene 倒排索引，
 * hybrid 会合并两路候选；rerank=true 时会对初召回候选做一次本地融合重排。
 *
 * 每次查询后，看 expectedId 在 topK 检索结果里的排名，再汇总 Recall@K、MRR、NDCG。
 * 这是代码检索领域常见的 offline retrieval eval，比端到端问答评测更小、更稳定，也更适合先验证 embedding / vector search 质量。
 */
@Service
public class CodeSearchNetRagEvalService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("([a-z0-9])([A-Z])");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final CodeChunkSplitter codeChunkSplitter;
    private final RagEvalMilvusVectorStoreFactory milvusVectorStoreFactory;

    public CodeSearchNetRagEvalService(
            EmbeddingModel embeddingModel,
            ObjectMapper objectMapper,
            CodeChunkSplitter codeChunkSplitter,
            RagEvalMilvusVectorStoreFactory milvusVectorStoreFactory
    ) {
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.codeChunkSplitter = codeChunkSplitter;
        this.milvusVectorStoreFactory = milvusVectorStoreFactory;
    }

    public CodeSearchNetEvalResponse run(CodeSearchNetEvalRunRequest request) {
        if (request == null || !StringUtils.hasText(request.datasetPath())) {
            throw new IllegalArgumentException("datasetPath is required");
        }
        Path datasetPath = Path.of(request.datasetPath()).toAbsolutePath().normalize();
        if (!Files.exists(datasetPath)) {
            throw new IllegalArgumentException("datasetPath does not exist: " + datasetPath);
        }
        String language = normalizeLanguage(request.language());
        String partition = normalizePartition(request.partition());
        // 这次评测最多取多少条样本
        int maxExamples = request.maxExamples() == null ? 200 : Math.max(20, Math.min(request.maxExamples(), 2000));
        // 过滤样本时，docstring 至少要多长
        int minDocstringLength = request.minDocstringLength() == null ? 12 : Math.max(0, Math.min(request.minDocstringLength(), 200));
        // 和正式向量入库一样，评测也要走同一个 chunker；这两个参数就是用来评估切分大小和 overlap 是否合理。
        Integer maxChunkChars = request.maxChunkChars();
        Integer overlapChars = request.overlapChars();
        // 这次评测要按哪些 top-K 阈值来统计指标
        List<Integer> topKValues = normalizeTopKValues(request.topKValues());
        boolean persistReport = request.persistReport() == null || request.persistReport();
        String backend = normalizeBackend(request.backend());
        String retrievalMode = normalizeRetrievalMode(request.retrievalMode());
        boolean rerank = request.rerank() != null && request.rerank();
        long now = System.currentTimeMillis();

        List<CodeSearchNetExample> examples = loadExamples(datasetPath, language, partition, maxExamples, minDocstringLength);
        if (examples.isEmpty()) {
            throw new IllegalArgumentException("no usable CodeSearchNet examples found under " + datasetPath);
        }

        // 评测 corpus 只来自本次抽样数据。simple 后端是进程内临时库；milvus 后端是独立 eval collection。
        // 两者都不复用线上业务 collection，避免评测数据污染正常 RAG 链路。
        // 切分方式复用正式 CodeChunkSplitter，所以可以评估 chunk 大小/overlap 对召回的影响。
        // 这样每条 query 的正确答案一定在同一个 corpus 里，指标才可解释、可复现。
        List<Document> corpus = examples.stream()
                .flatMap(example -> toDocuments(example, maxChunkChars, overlapChars).stream())
                .toList();
        int maxTopK = topKValues.stream().max(Integer::compareTo).orElse(10);
        int candidateTopN = normalizeCandidateTopN(request.candidateTopN(), maxTopK);
        VectorStore vectorStore = null;
        if (usesVector(retrievalMode)) {
            vectorStore = createVectorStore(backend);
            vectorStore.add(corpus);
        }
        Path bm25IndexPath = usesBm25(retrievalMode) ? bm25IndexPath(now, language, partition) : null;
        LocalLuceneBm25Index bm25Index = usesBm25(retrievalMode) ? LocalLuceneBm25Index.create(corpus, bm25IndexPath) : null;

        try {
            double reciprocalRankSum = 0D;
            double ndcgSum = 0D;
            double rankSum = 0D;
            Map<Integer, Integer> hitsByK = new LinkedHashMap<>();
            for (Integer k : topKValues) {
                hitsByK.put(k, 0);
            }
            List<CodeSearchNetEvalMissResponse> misses = new ArrayList<>();
            int queryCount = 0;

            for (CodeSearchNetExample example : examples) {
                // 用 docstring 做自然语言 query，检索目标是同批 code 文档。
                // 如果 embedding 质量好，当前 docstring 对应的 code 应该排在结果前面。
                List<Document> results = retrieve(example.query(), vectorStore, bm25Index, retrievalMode, rerank, candidateTopN, maxTopK);
                queryCount++;
                int rank = findRank(example.id(), results);
                if (rank > 0) {
                    // MRR：越早命中越好。rank=1 贡献 1，rank=10 只贡献 0.1。
                    reciprocalRankSum += 1D / rank;
                    rankSum += rank;
                    // NDCG：同样奖励靠前命中，但折扣曲线比 1/rank 平滑。
                    // 这里每个 query 只有一个正确答案，所以 DCG 就是 1/log2(rank+1)。
                    ndcgSum += 1D / log2(rank + 1D);
                    for (Integer k : topKValues) {
                        // Recall@K：正确代码是否出现在前 K 个结果里。
                        // 例如 Recall@5=0.8 表示 80% 的 query 能在前 5 个结果找回正确代码。
                        if (rank <= k) {
                            hitsByK.computeIfPresent(k, (ignored, hit) -> hit + 1);
                        }
                    }
                } else if (misses.size() < 10) {
                    // 只保留少量 miss 样例，方便人工看失败原因：query 太泛、代码太短、embedding 不适合代码等。
                    misses.add(new CodeSearchNetEvalMissResponse(
                            example.query(),
                            example.qualifiedName(),
                            example.path(),
                            null,
                            results.stream()
                                    .map(doc -> String.valueOf(doc.getMetadata().getOrDefault("qualified_name", doc.getId())))
                                    .limit(5)
                                    .toList()
                    ));
                }
            }

            Map<String, Double> recallAtK = new LinkedHashMap<>();
            for (Integer k : topKValues) {
                recallAtK.put("Recall@" + k, queryCount == 0 ? 0D : hitsByK.get(k) / (double) queryCount);
            }
            CodeSearchNetEvalMetricsResponse metrics = new CodeSearchNetEvalMetricsResponse(
                    queryCount == 0 ? 0D : reciprocalRankSum / queryCount,
                    queryCount == 0 ? 0D : ndcgSum / queryCount,
                    queryCount == 0 ? 0D : rankSum / Math.max(1, hitsByK.get(topKValues.getLast())),
                    recallAtK
            );

            String bm25IndexPathText = bm25IndexPath == null ? null : bm25IndexPath.toString();
            String reportFile = persistReport ? persistReport(now, datasetPath, language, partition, backend, retrievalMode, rerank, candidateTopN, bm25IndexPathText, queryCount, corpus.size(), metrics, misses) : null;
            return new CodeSearchNetEvalResponse(
                    now,
                    datasetPath.toString(),
                    language,
                    partition,
                    backend,
                    retrievalMode,
                    rerank,
                    candidateTopN,
                    bm25IndexPathText,
                    corpus.size(),
                    queryCount,
                    0,
                    metrics,
                    List.copyOf(misses),
                    reportFile
            );
        } finally {
            closeQuietly(bm25Index);
        }
    }

    private List<CodeSearchNetExample> loadExamples(
            Path datasetPath,
            String language,
            String partition,
            int maxExamples,
            int minDocstringLength
    ) {
        List<Path> files = resolveDatasetFiles(datasetPath);
        List<CodeSearchNetExample> examples = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Path file : files) {
            if (examples.size() >= maxExamples) {
                break;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(openInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && examples.size() < maxExamples) {
                    if (line.isBlank()) {
                        continue;
                    }
                    // CodeSearchNet 是 jsonl/jsonl.gz：一行就是一个函数样本。
                    Map<?, ?> row = objectMapper.readValue(line, Map.class);
                    CodeSearchNetExample example = toExample(row, language, partition, minDocstringLength);
                    if (example == null || !seenIds.add(example.id())) {
                        continue;
                    }
                    examples.add(example);
                }
            } catch (IOException e) {
                throw new IllegalStateException("failed to read CodeSearchNet dataset file: " + file, e);
            }
        }
        return List.copyOf(examples);
    }

    private List<Path> resolveDatasetFiles(Path datasetPath) {
        try {
            if (Files.isRegularFile(datasetPath)) {
                return List.of(datasetPath);
            }
            try (var stream = Files.walk(datasetPath)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                            return name.endsWith(".jsonl") || name.endsWith(".jsonl.gz") || name.endsWith(".gz");
                        })
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to scan datasetPath: " + datasetPath, e);
        }
    }

    private InputStream openInputStream(Path file) throws IOException {
        InputStream in = Files.newInputStream(file);
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".gz") ? new GZIPInputStream(in) : in;
    }

    private CodeSearchNetExample toExample(Map<?, ?> row, String language, String partition, int minDocstringLength) {
        // 只评估指定语言/分区，避免把 train/test 或不同语言混在一起导致指标失真。
        String rowLanguage = stringValue(row.get("language"));
        if (!language.equalsIgnoreCase(rowLanguage)) {
            return null;
        }
        String rowPartition = stringValue(row.get("partition"));
        if (StringUtils.hasText(partition) && !partition.equalsIgnoreCase(rowPartition)) {
            return null;
        }
        String docstring = normalizeWhitespace(stringValue(row.get("docstring")));
        String code = stringValue(row.get("code"));
        // docstring 太短通常语义不足，比如 "get id" 这类 query 很难公平评估检索质量。
        if (docstring.length() < minDocstringLength || code.isBlank()) {
            return null;
        }
        String path = stringValue(row.get("path"));
        String repo = stringValue(row.get("repo"));
        String funcName = stringValue(row.get("func_name"));
        String url = stringValue(row.get("url"));
        String id = !url.isBlank() ? url : repo + "::" + path + "::" + funcName;
        String qualifiedName = funcName.isBlank() ? path : funcName + " (" + path + ")";
        return new CodeSearchNetExample(id, docstring, code, qualifiedName, path, repo, url);
    }

    private List<Document> toDocuments(CodeSearchNetExample example, Integer maxChunkChars, Integer overlapChars) {
        // 被检索文档复用正式代码入库的切分方式，而不是整段函数塞成一个 Document。
        // 方法名/路径作为前缀给 embedding 一点语义锚点；chunk 正文保持和生产 code_chunk 更接近。
        List<CodeChunkSplitter.ChunkSlice> slices = codeChunkSplitter.split(example.code(), maxChunkChars, overlapChars);
        if (slices.isEmpty()) {
            slices = List.of(new CodeChunkSplitter.ChunkSlice(example.code(), 1, 1));
        }
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < slices.size(); i++) {
            CodeChunkSplitter.ChunkSlice slice = slices.get(i);
            String chunkQualifiedName = slices.size() == 1 ? example.qualifiedName() : example.qualifiedName() + "#chunk" + (i + 1);
            String documentId = slices.size() == 1 ? example.id() : example.id() + "#chunk" + (i + 1);
            String indexedText = chunkQualifiedName + "\n" + slice.text();
            docs.add(new Document(
                    documentId,
                    indexedText,
                    Map.of(
                            "parent_id", example.id(),
                            "qualified_name", chunkQualifiedName,
                            "path", example.path(),
                            "repo", example.repo(),
                            "url", example.url(),
                            "start_line", slice.startLine(),
                            "end_line", slice.endLine(),
                            "doc_type", "codesearchnet_eval_code_chunk"
                    )
            ));
        }
        return docs;
    }

    private int findRank(String expectedId, List<Document> results) {
        // rank 从 1 开始；返回 -1 表示 topK 里没有找回正确代码。
        // 一个函数可能被切成多个 chunk；只要命中任意 parent_id 相同的 chunk，就算召回了这条代码样本。
        for (int i = 0; i < results.size(); i++) {
            Object parentId = results.get(i).getMetadata().get("parent_id");
            if (Objects.equals(expectedId, parentId) || Objects.equals(expectedId, results.get(i).getId())) {
                return i + 1;
            }
        }
        return -1;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2D);
    }

    private String persistReport(
            long now,
            Path datasetPath,
            String language,
            String partition,
            String backend,
            String retrievalMode,
            boolean rerank,
            int candidateTopN,
            String bm25IndexPath,
            int queryCount,
            int corpusSize,
            CodeSearchNetEvalMetricsResponse metrics,
            List<CodeSearchNetEvalMissResponse> misses
    ) {
        try {
            Path reportDir = Path.of(".").toAbsolutePath().normalize().resolve(".lumisight/evals/rag");
            Files.createDirectories(reportDir);
            String filename = FILE_TIME.format(Instant.ofEpochMilli(now)) + "-codesearchnet-" + language + ".json";
            Path reportFile = reportDir.resolve(filename);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("generatedAt", now);
            payload.put("datasetPath", datasetPath.toString());
            payload.put("language", language);
            payload.put("partition", partition);
            payload.put("backend", backend);
            payload.put("retrievalMode", retrievalMode);
            payload.put("rerank", rerank);
            payload.put("candidateTopN", candidateTopN);
            payload.put("bm25IndexPath", bm25IndexPath);
            if ("milvus".equals(backend)) {
                payload.put("milvusCollection", milvusVectorStoreFactory.collectionName());
            }
            payload.put("queryCount", queryCount);
            payload.put("corpusSize", corpusSize);
            payload.put("metrics", metrics);
            payload.put("sampleMisses", misses);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportFile.toFile(), payload);
            return reportFile.toString();
        } catch (IOException e) {
            throw new IllegalStateException("failed to persist rag eval report", e);
        }
    }

    private String normalizeLanguage(String language) {
        return StringUtils.hasText(language) ? language.trim().toLowerCase(Locale.ROOT) : "java";
    }

    private String normalizePartition(String partition) {
        return StringUtils.hasText(partition) ? partition.trim().toLowerCase(Locale.ROOT) : "test";
    }

    private String normalizeBackend(String backend) {
        if (!StringUtils.hasText(backend)) {
            return "simple";
        }
        String value = backend.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("simple", "milvus").contains(value)) {
            throw new IllegalArgumentException("unsupported rag eval backend: " + backend + ". Supported values: simple, milvus");
        }
        return value;
    }

    private VectorStore createVectorStore(String backend) {
        if ("simple".equals(backend)) {
            return SimpleVectorStore.builder(embeddingModel).build();
        }
        return milvusVectorStoreFactory.create();
    }

    private Path bm25IndexPath(long now, String language, String partition) {
        String runId = FILE_TIME.format(Instant.ofEpochMilli(now)) + "-" + language + "-" + partition;
        return Path.of(".").toAbsolutePath().normalize()
                .resolve(".lumisight/evals/rag/lucene-bm25")
                .resolve(runId);
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 评测结果已经生成时，关闭本地 Lucene reader/directory 失败不应该覆盖主错误。
        }
    }

    private String normalizeRetrievalMode(String retrievalMode) {
        if (!StringUtils.hasText(retrievalMode)) {
            return "vector";
        }
        String value = retrievalMode.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("vector", "bm25", "hybrid").contains(value)) {
            throw new IllegalArgumentException("unsupported rag eval retrievalMode: " + retrievalMode + ". Supported values: vector, bm25, hybrid");
        }
        return value;
    }

    private int normalizeCandidateTopN(Integer candidateTopN, int maxTopK) {
        int defaultValue = Math.max(50, maxTopK * 3);
        int value = candidateTopN == null ? defaultValue : candidateTopN;
        return Math.max(maxTopK, Math.min(value, 200));
    }

    private boolean usesVector(String retrievalMode) {
        return "vector".equals(retrievalMode) || "hybrid".equals(retrievalMode);
    }

    private boolean usesBm25(String retrievalMode) {
        return "bm25".equals(retrievalMode) || "hybrid".equals(retrievalMode);
    }

    private List<Document> retrieve(
            String query,
            VectorStore vectorStore,
            LocalLuceneBm25Index bm25Index,
            String retrievalMode,
            boolean rerank,
            int candidateTopN,
            int maxTopK
    ) {
        int initialLimit = rerank || "hybrid".equals(retrievalMode) ? candidateTopN : maxTopK;
        Map<String, RetrievalCandidate> candidates = new LinkedHashMap<>();
        if (usesVector(retrievalMode)) {
            List<Document> vectorResults = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(initialLimit)
                            .similarityThresholdAll()
                            .build()
            );
            for (int i = 0; i < vectorResults.size(); i++) {
                Document doc = vectorResults.get(i);
                RetrievalCandidate candidate = candidates.computeIfAbsent(doc.getId(), ignored -> new RetrievalCandidate(doc));
                candidate.vectorRank = i + 1;
                candidate.vectorScore = doc.getScore() == null ? 1D / (i + 1D) : doc.getScore();
            }
        }
        if (usesBm25(retrievalMode)) {
            List<Bm25Hit> bm25Results = bm25Index.search(query, initialLimit);
            for (int i = 0; i < bm25Results.size(); i++) {
                Bm25Hit hit = bm25Results.get(i);
                RetrievalCandidate candidate = candidates.computeIfAbsent(hit.document().getId(), ignored -> new RetrievalCandidate(hit.document()));
                candidate.bm25Rank = i + 1;
                candidate.bm25Score = hit.score();
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievalCandidate> ranked = new ArrayList<>(candidates.values());
        if ("vector".equals(retrievalMode) && !rerank) {
            ranked.sort(Comparator.comparingInt(candidate -> candidate.vectorRank));
        } else if ("bm25".equals(retrievalMode) && !rerank) {
            ranked.sort(Comparator.comparingInt(candidate -> candidate.bm25Rank));
        } else {
            rerank(query, ranked);
        }
        return ranked.stream()
                .limit(maxTopK)
                .map(candidate -> candidate.document)
                .toList();
    }

    private void rerank(String query, List<RetrievalCandidate> candidates) {
        double minVector = candidates.stream()
                .filter(candidate -> candidate.vectorRank < Integer.MAX_VALUE)
                .mapToDouble(candidate -> candidate.vectorScore)
                .min()
                .orElse(0D);
        double maxVector = candidates.stream()
                .filter(candidate -> candidate.vectorRank < Integer.MAX_VALUE)
                .mapToDouble(candidate -> candidate.vectorScore)
                .max()
                .orElse(0D);
        double minBm25 = candidates.stream()
                .filter(candidate -> candidate.bm25Rank < Integer.MAX_VALUE)
                .mapToDouble(candidate -> candidate.bm25Score)
                .min()
                .orElse(0D);
        double maxBm25 = candidates.stream()
                .filter(candidate -> candidate.bm25Rank < Integer.MAX_VALUE)
                .mapToDouble(candidate -> candidate.bm25Score)
                .max()
                .orElse(0D);
        for (RetrievalCandidate candidate : candidates) {
            double vector = candidate.vectorRank < Integer.MAX_VALUE ? normalize(candidate.vectorScore, minVector, maxVector) : 0D;
            double bm25 = candidate.bm25Rank < Integer.MAX_VALUE ? normalize(candidate.bm25Score, minBm25, maxBm25) : 0D;
            double rrf = reciprocalRank(candidate.vectorRank) + reciprocalRank(candidate.bm25Rank);
            double exact = exactMatchBonus(query, candidate.document);
            candidate.rerankScore = vector * 0.45D + bm25 * 0.35D + rrf * 0.15D + exact;
        }
        candidates.sort(Comparator.comparingDouble((RetrievalCandidate candidate) -> candidate.rerankScore).reversed());
    }

    private double normalize(double value, double min, double max) {
        if (max <= min) {
            return value > 0D ? 1D : 0D;
        }
        return (value - min) / (max - min);
    }

    private double reciprocalRank(int rank) {
        return rank == Integer.MAX_VALUE ? 0D : 1D / (60D + rank);
    }

    private double exactMatchBonus(String query, Document document) {
        Set<String> queryTokens = new HashSet<>(tokenize(query));
        if (queryTokens.isEmpty()) {
            return 0D;
        }
        String metadataText = String.join(" ",
                String.valueOf(document.getMetadata().getOrDefault("qualified_name", "")),
                String.valueOf(document.getMetadata().getOrDefault("path", "")),
                String.valueOf(document.getMetadata().getOrDefault("repo", ""))
        );
        Set<String> metadataTokens = new HashSet<>(tokenize(metadataText));
        int metadataHits = 0;
        for (String token : queryTokens) {
            if (metadataTokens.contains(token)) {
                metadataHits++;
            }
        }
        return Math.min(0.2D, metadataHits * 0.04D);
    }

    private List<Integer> normalizeTopKValues(List<Integer> topKValues) {
        List<Integer> values = topKValues == null || topKValues.isEmpty() ? List.of(1, 5, 10) : topKValues;
        return values.stream()
                .filter(Objects::nonNull)
                .map(value -> Math.max(1, Math.min(value, 50)))
                .distinct()
                .sorted()
                .toList();
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalizeWhitespace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String expanded = CAMEL_BOUNDARY.matcher(text).replaceAll("$1 $2").toLowerCase(Locale.ROOT);
        String[] rawTokens = TOKEN_SPLIT.split(expanded);
        List<String> tokens = new ArrayList<>();
        for (String token : rawTokens) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * 内部归一化后的评测样本，不是完整 CodeSearchNet schema。
     *
     * CodeSearchNet 原始 jsonl 里还有 code_tokens、docstring_tokens、sha 等字段；这里仅保留评测需要的字段：
     * docstring 只作为查询侧 query 使用，code 只作为被检索侧 corpus 使用，避免把答案提示写进向量库。
     */
    private record CodeSearchNetExample(
            String id,
            String query,
            String code,
            String qualifiedName,
            String path,
            String repo,
            String url
    ) {
    }

    private static final class RetrievalCandidate {
        private final Document document;
        private int vectorRank = Integer.MAX_VALUE;
        private double vectorScore = 0D;
        private int bm25Rank = Integer.MAX_VALUE;
        private double bm25Score = 0D;
        private double rerankScore = 0D;

        private RetrievalCandidate(Document document) {
            this.document = document;
        }
    }

    private record Bm25Hit(Document document, double score) {
    }

    /**
     * 评测专用的本地 Lucene BM25 索引。
     *
     * 索引写入 .lumisight/evals/rag/lucene-bm25/{runId}，方便确认“本地 BM25”到底存在哪里。
     * 这条索引只服务 CodeSearchNet eval，不接入正常 RAG / Milvus 主链路。
     */
    private static final class LocalLuceneBm25Index implements Closeable {
        private static final String FIELD_DOC_INDEX = "doc_index";
        private static final String FIELD_CONTENT = "content";

        private final List<Document> documents;
        private final Analyzer analyzer;
        private final FSDirectory directory;
        private final DirectoryReader reader;
        private final IndexSearcher searcher;

        private LocalLuceneBm25Index(
                List<Document> documents,
                Analyzer analyzer,
                FSDirectory directory,
                DirectoryReader reader,
                IndexSearcher searcher
        ) {
            this.documents = documents;
            this.analyzer = analyzer;
            this.directory = directory;
            this.reader = reader;
            this.searcher = searcher;
        }

        private static LocalLuceneBm25Index create(List<Document> documents, Path indexPath) {
            try {
                Files.createDirectories(indexPath);
                Analyzer analyzer = new StandardAnalyzer();
                FSDirectory directory = FSDirectory.open(indexPath);
                IndexWriterConfig config = new IndexWriterConfig(analyzer);
                config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
                config.setSimilarity(new BM25Similarity());
                try (IndexWriter writer = new IndexWriter(directory, config)) {
                    for (int docIndex = 0; docIndex < documents.size(); docIndex++) {
                        Document source = documents.get(docIndex);
                        org.apache.lucene.document.Document luceneDoc = new org.apache.lucene.document.Document();
                        luceneDoc.add(new StringField(FIELD_DOC_INDEX, String.valueOf(docIndex), Field.Store.YES));
                        luceneDoc.add(new TextField(FIELD_CONTENT, searchableText(source), Field.Store.NO));
                        writer.addDocument(luceneDoc);
                    }
                }
                DirectoryReader reader = DirectoryReader.open(directory);
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());
                return new LocalLuceneBm25Index(List.copyOf(documents), analyzer, directory, reader, searcher);
            } catch (IOException e) {
                throw new IllegalStateException("failed to build local Lucene BM25 index: " + indexPath, e);
            }
        }

        private List<Bm25Hit> search(String queryText, int limit) {
            if (queryText == null || queryText.isBlank() || documents.isEmpty()) {
                return List.of();
            }
            try {
                QueryParser parser = new QueryParser(FIELD_CONTENT, analyzer);
                Query query = parser.parse(QueryParser.escape(queryText));
                ScoreDoc[] scoreDocs = searcher.search(query, limit).scoreDocs;
                List<Bm25Hit> hits = new ArrayList<>();
                for (ScoreDoc scoreDoc : scoreDocs) {
                    org.apache.lucene.document.Document luceneDoc = searcher.doc(scoreDoc.doc);
                    int sourceIndex = Integer.parseInt(luceneDoc.get(FIELD_DOC_INDEX));
                    hits.add(new Bm25Hit(documents.get(sourceIndex), scoreDoc.score));
                }
                return hits;
            } catch (IOException | ParseException e) {
                throw new IllegalStateException("failed to search local Lucene BM25 index", e);
            }
        }

        private static String searchableText(Document document) {
            return String.join(" ",
                    document.getText(),
                    String.valueOf(document.getMetadata().getOrDefault("qualified_name", "")),
                    String.valueOf(document.getMetadata().getOrDefault("path", "")),
                    String.valueOf(document.getMetadata().getOrDefault("repo", ""))
            );
        }

        @Override
        public void close() throws IOException {
            reader.close();
            directory.close();
            analyzer.close();
        }
    }
}
