package com.lumisight.api.rageval.dto;

import java.util.Map;

public record CodeSearchNetEvalMetricsResponse(
        double mrr,
        double meanNdcg,
        double meanRank,
        Map<String, Double> recallAtK
) {
}
