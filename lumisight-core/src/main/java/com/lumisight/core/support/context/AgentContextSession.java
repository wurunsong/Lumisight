package com.lumisight.core.support.context;

import java.util.ArrayList;
import java.util.List;

public record AgentContextSession(
        List<AgentContextEntry> entries,
        List<AgentContextHotCacheEntry> hotCacheEntries,
        String compactedSummary,
        int snipTokensFreed,
        long lastProjectionAt,
        long lastCompactionAt,
        int autoCompactFailureCount
) {

    public static AgentContextSession empty() {
        return new AgentContextSession(new ArrayList<>(), new ArrayList<>(), "", 0, 0L, 0L, 0);
    }

    public AgentContextSession withEntries(List<AgentContextEntry> nextEntries) {
        return new AgentContextSession(nextEntries, hotCacheEntries, compactedSummary, snipTokensFreed, lastProjectionAt, lastCompactionAt, autoCompactFailureCount);
    }

    public AgentContextSession withHotCacheEntries(List<AgentContextHotCacheEntry> nextHotCacheEntries) {
        return new AgentContextSession(entries, nextHotCacheEntries, compactedSummary, snipTokensFreed, lastProjectionAt, lastCompactionAt, autoCompactFailureCount);
    }

    public AgentContextSession withSummary(String nextSummary) {
        return new AgentContextSession(entries, hotCacheEntries, nextSummary, snipTokensFreed, lastProjectionAt, lastCompactionAt, autoCompactFailureCount);
    }

    public AgentContextSession withSnipTokensFreed(int tokens) {
        return new AgentContextSession(entries, hotCacheEntries, compactedSummary, tokens, lastProjectionAt, lastCompactionAt, autoCompactFailureCount);
    }

    public AgentContextSession withLastProjectionAt(long timestamp) {
        return new AgentContextSession(entries, hotCacheEntries, compactedSummary, snipTokensFreed, timestamp, lastCompactionAt, autoCompactFailureCount);
    }

    public AgentContextSession withCompactionState(long timestamp, int failures) {
        return new AgentContextSession(entries, hotCacheEntries, compactedSummary, snipTokensFreed, lastProjectionAt, timestamp, failures);
    }
}
