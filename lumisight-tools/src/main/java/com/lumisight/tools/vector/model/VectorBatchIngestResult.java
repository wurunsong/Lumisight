package com.lumisight.tools.vector.model;

import java.util.List;

public record VectorBatchIngestResult(
        String collection,
        String repoRoot,
        String sourceFile,
        int chunkCount,
        List<String> documentIds
) {
}
