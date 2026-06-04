package com.lumisight.core.support.context;

import com.lumisight.core.model.AgentContextItem;

import java.util.Map;

public record AgentContextEntry(
        String id,
        AgentContextEntryKind kind,
        AgentContextItem item,
        boolean compactable,
        boolean retriable,
        String toolName,
        AgentContextArtifactRef artifactRef,
        int byteSize,
        int tokenEstimate,
        long createdAt,
        long lastAccessedAt,
        boolean compacted,
        int priority
) {

    public AgentContextEntry withItem(AgentContextItem nextItem, int nextByteSize, int nextTokenEstimate) {
        return new AgentContextEntry(id, kind, nextItem, compactable, retriable, toolName, artifactRef, nextByteSize, nextTokenEstimate, createdAt, lastAccessedAt, compacted, priority);
    }

    public AgentContextEntry withCompactedItem(AgentContextItem nextItem, int nextByteSize, int nextTokenEstimate) {
        return new AgentContextEntry(id, kind, nextItem, compactable, retriable, toolName, artifactRef, nextByteSize, nextTokenEstimate, createdAt, lastAccessedAt, true, priority);
    }

    public AgentContextEntry touch(long timestamp) {
        return new AgentContextEntry(id, kind, item, compactable, retriable, toolName, artifactRef, byteSize, tokenEstimate, createdAt, timestamp, compacted, priority);
    }

    public AgentContextEntry withArtifact(AgentContextItem nextItem, AgentContextArtifactRef nextArtifactRef, int nextByteSize, int nextTokenEstimate) {
        return new AgentContextEntry(id, kind, nextItem, compactable, retriable, toolName, nextArtifactRef, nextByteSize, nextTokenEstimate, createdAt, lastAccessedAt, compacted, priority);
    }

    public static AgentContextEntry of(
            String id,
            AgentContextEntryKind kind,
            AgentContextItem item,
            boolean compactable,
            boolean retriable,
            String toolName,
            AgentContextArtifactRef artifactRef,
            int byteSize,
            int tokenEstimate,
            long createdAt,
            int priority
    ) {
        return new AgentContextEntry(
                id,
                kind,
                item,
                compactable,
                retriable,
                toolName,
                artifactRef,
                byteSize,
                tokenEstimate,
                createdAt,
                createdAt,
                false,
                priority
        );
    }

    public static AgentContextEntry marker(String id, AgentContextEntryKind kind, String sourceId, String content, Map<String, Object> metadata, long now, int priority) {
        AgentContextItem item = new AgentContextItem("context_marker", sourceId, content, metadata);
        int bytes = content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int tokens = Math.max(1, content == null ? 0 : content.length() / 4);
        return AgentContextEntry.of(id, kind, item, false, false, null, null, bytes, tokens, now, priority);
    }
}
