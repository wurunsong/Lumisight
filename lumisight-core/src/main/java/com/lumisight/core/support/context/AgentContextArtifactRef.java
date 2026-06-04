package com.lumisight.core.support.context;

public record AgentContextArtifactRef(
        String artifactId,
        String relativePath,
        int fullBytes,
        int previewBytes
) {
}
