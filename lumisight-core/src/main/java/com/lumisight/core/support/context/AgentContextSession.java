package com.lumisight.core.support.context;

import java.util.ArrayList;
import java.util.List;

public record AgentContextSession(
        List<AgentContextEntry> entries,
        String compactedSummary,
        int snipTokensFreed,
        long lastProjectionAt,
        long lastCompactionAt,
        int autoCompactFailureCount
) {

    public static AgentContextSession empty() {
        return new AgentContextSession(new ArrayList<>(), "", 0, 0L, 0L, 0);
    }

    public AgentContextSession withEntries(List<AgentContextEntry> nextEntries) {
        return new AgentContextSession(nextEntries, compactedSummary, snipTokensFreed, lastProjectionAt, lastCompactionAt, autoCompactFailureCount);
    }

    public AgentContextSession withSummary(String nextSummary) {
        return new AgentContextSession(entries, nextSummary, snipTokensFreed, lastProjectionAt, lastCompactionAt, autoCompactFailureCount);
    }

    public AgentContextSession withSnipTokensFreed(int tokens) {
        return new AgentContextSession(entries, compactedSummary, tokens, lastProjectionAt, lastCompactionAt, autoCompactFailureCount);
    }

    public AgentContextSession withLastProjectionAt(long timestamp) {
        return new AgentContextSession(entries, compactedSummary, snipTokensFreed, timestamp, lastCompactionAt, autoCompactFailureCount);
    }

    public AgentContextSession withCompactionState(long timestamp, int failures) {
        return new AgentContextSession(entries, compactedSummary, snipTokensFreed, lastProjectionAt, timestamp, failures);
    }
}
