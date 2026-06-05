package com.lumisight.core.support.context;

import com.lumisight.core.model.AgentContextItem;

public record AgentContextHotCacheEntry(
        String sourceEntryId,
        String toolName,
        String logicalResourceId,
        AgentContextItem item,
        AgentContextArtifactRef artifactRef,
        boolean restorable,
        boolean retriable,
        int tokenEstimate,
        long createdAt,
        long lastAccessedAt,
        int priority
) {

    public AgentContextHotCacheEntry touch(long timestamp) {
        return new AgentContextHotCacheEntry(
                sourceEntryId,
                toolName,
                logicalResourceId,
                item,
                artifactRef,
                restorable,
                retriable,
                tokenEstimate,
                createdAt,
                timestamp,
                priority
        );
    }

    public AgentContextEntry toRestoredEntry(String entryId, long timestamp, int restorePriority) {
        return toRestoredEntry(entryId, timestamp, restorePriority, item);
    }

    public AgentContextEntry toRestoredEntry(String entryId, long timestamp, int restorePriority, AgentContextItem restoredItem) {
        return AgentContextEntry.of(
                entryId,
                AgentContextEntryKind.TOOL_RESULT,
                restoredItem,
                false,
                retriable,
                toolName,
                artifactRef,
                restoredItem == null || restoredItem.content() == null ? 0 : restoredItem.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                tokenEstimate,
                timestamp,
                restorePriority
        );
    }
}
