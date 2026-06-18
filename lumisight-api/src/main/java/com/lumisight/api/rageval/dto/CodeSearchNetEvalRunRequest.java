package com.lumisight.api.rageval.dto;

import java.util.List;

public record CodeSearchNetEvalRunRequest(
        String datasetPath,
        String language,
        String partition,
        String backend,
        String retrievalMode,
        Boolean rerank,
        Integer candidateTopN,
        Integer maxExamples,
        Integer minDocstringLength,
        Integer maxChunkChars,
        Integer overlapChars,
        List<Integer> topKValues,
        Boolean persistReport
) {
}
