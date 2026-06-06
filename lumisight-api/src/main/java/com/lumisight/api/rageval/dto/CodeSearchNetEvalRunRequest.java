package com.lumisight.api.rageval.dto;

import java.util.List;

public record CodeSearchNetEvalRunRequest(
        String datasetPath,
        String language,
        String partition,
        Integer maxExamples,
        Integer minDocstringLength,
        List<Integer> topKValues,
        Boolean persistReport
) {
}
