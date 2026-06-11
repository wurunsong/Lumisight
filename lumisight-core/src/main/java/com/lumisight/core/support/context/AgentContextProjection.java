package com.lumisight.core.support.context;

import com.lumisight.core.model.AgentContextItem;

import java.util.List;
import java.util.Map;

public record AgentContextProjection(
        // 本次投影结束后应继续保留的完整上下文 session，包含 entries、hot cache 和压缩状态等内部管理信息。
        AgentContextSession session,
        // 从 session 中筛选/裁剪后真正提供给模型使用的上下文视图。
        List<AgentContextItem> contexts,
        // 当前投影视图估算的 token 数，用于判断是否接近上下文窗口上限。
        int estimatedTokens,
        // 本次投影是否折叠/隐藏了一部分原始上下文条目。
        boolean collapsed,
        // 本次投影前是否触发了自动压缩。
        boolean autoCompacted,
        // 本次投影经历过的压缩/恢复阶段，便于调试上下文管理链路。
        List<AgentContextCompressionStage> stages,
        // 本次投影的附加指标，例如折叠数量、隐藏类型分布、恢复条目数等。
        Map<String, Object> metrics
) {
}
