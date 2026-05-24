package com.lumisight.api.vector.dto.request;

public record RepoCodeChunkIngestRequest(
        String repoRoot,
        Integer maxChunkChars,
        Integer overlapChars,
        String gitBranch,
        String gitCommit
) {
}
