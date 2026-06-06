package com.lumisight.api.rageval.dto;

import java.util.List;

public record CodeSearchNetEvalResponse(
        long generatedAt,
        String datasetPath,
        String language,
        String partition,
        int corpusSize,
        int queryCount,
        int skippedCount,
        CodeSearchNetEvalMetricsResponse metrics,
        List<CodeSearchNetEvalMissResponse> sampleMisses,
        String reportFile
) {
}
