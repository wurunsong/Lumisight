package com.lumisight.api.rageval.dto;

import java.util.List;

public record CodeSearchNetEvalMissResponse(
        String query,
        String expectedQualifiedName,
        String expectedPath,
        Integer actualRank,
        List<String> topResults
) {
}
