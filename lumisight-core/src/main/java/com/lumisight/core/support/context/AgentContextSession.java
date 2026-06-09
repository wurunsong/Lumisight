package com.lumisight.core.support.context;

import java.util.ArrayList;
import java.util.List;

public record AgentContextSession(
        // 当前会话保留下来的正式上下文条目，会参与后续投影、压缩和恢复。
        List<AgentContextEntry> entries,
        // 为高频可复用信息保留的热缓存条目，用于减少重复展开大块上下文。
        List<AgentContextHotCacheEntry> hotCacheEntries,
        // 对旧上下文做 compact 后得到的摘要文本，作为长上下文的压缩表示。
        String compactedSummary,
        // 通过 snip/compact 等裁剪动作累计释放的 token 数，用于衡量压缩收益。
        int snipTokensFreed,
        // 最近一次把 session 投影给模型决策/回答使用的时间戳。
        long lastProjectionAt,
        // 最近一次执行上下文压缩的时间戳。
        long lastCompactionAt,
        // 自动压缩连续失败的次数，用于避免反复触发高成本压缩。
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
