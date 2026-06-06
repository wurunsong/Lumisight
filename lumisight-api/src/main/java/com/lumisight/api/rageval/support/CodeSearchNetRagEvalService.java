package com.lumisight.api.rageval.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.api.rageval.dto.CodeSearchNetEvalMetricsResponse;
import com.lumisight.api.rageval.dto.CodeSearchNetEvalMissResponse;
import com.lumisight.api.rageval.dto.CodeSearchNetEvalResponse;
import com.lumisight.api.rageval.dto.CodeSearchNetEvalRunRequest;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.GZIPInputStream;

@Service
public class CodeSearchNetRagEvalService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public CodeSearchNetRagEvalService(EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
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
        int maxExamples = request.maxExamples() == null ? 200 : Math.max(20, Math.min(request.maxExamples(), 2000));
        int minDocstringLength = request.minDocstringLength() == null ? 12 : Math.max(0, Math.min(request.minDocstringLength(), 200));
        List<Integer> topKValues = normalizeTopKValues(request.topKValues());
        boolean persistReport = request.persistReport() == null || request.persistReport();

        List<CodeSearchNetExample> examples = loadExamples(datasetPath, language, partition, maxExamples, minDocstringLength);
        if (examples.isEmpty()) {
            throw new IllegalArgumentException("no usable CodeSearchNet examples found under " + datasetPath);
        }

        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.add(examples.stream().map(this::toDocument).toList());

        int maxTopK = topKValues.stream().max(Integer::compareTo).orElse(10);
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
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(example.query())
                            .topK(maxTopK)
                            .similarityThresholdAll()
                            .build()
            );
            queryCount++;
            int rank = findRank(example.id(), results);
            if (rank > 0) {
                reciprocalRankSum += 1D / rank;
                rankSum += rank;
                ndcgSum += 1D / log2(rank + 1D);
                for (Integer k : topKValues) {
                    if (rank <= k) {
                        hitsByK.computeIfPresent(k, (ignored, hit) -> hit + 1);
                    }
                }
            } else if (misses.size() < 10) {
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

        long now = System.currentTimeMillis();
        String reportFile = persistReport ? persistReport(now, datasetPath, language, partition, queryCount, examples.size(), metrics, misses) : null;
        return new CodeSearchNetEvalResponse(
                now,
                datasetPath.toString(),
                language,
                partition,
                examples.size(),
                queryCount,
                0,
                metrics,
                List.copyOf(misses),
                reportFile
        );
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

    private Document toDocument(CodeSearchNetExample example) {
        String indexedText = example.qualifiedName() + "\n" + example.code();
        return new Document(
                example.id(),
                indexedText,
                Map.of(
                        "qualified_name", example.qualifiedName(),
                        "path", example.path(),
                        "repo", example.repo(),
                        "url", example.url(),
                        "query", example.query(),
                        "doc_type", "codesearchnet_eval_code"
                )
        );
    }

    private int findRank(String expectedId, List<Document> results) {
        for (int i = 0; i < results.size(); i++) {
            if (Objects.equals(expectedId, results.get(i).getId())) {
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
}
