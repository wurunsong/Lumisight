package com.lumisight.core.support.context;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.StreamingChatClientSupport;
import com.lumisight.skills.runtime.SkillPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class DefaultAgentContextManager implements AgentContextManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentContextManager.class);
    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "cat",
            "ls",
            "grep",
            "gitStatus",
            "gitDiff",
            "gitBlame",
            "compileJava",
            "lintJavaByJdtls",
            "lintJava",
            "writeRepoFile",
            "rollbackRepoFile",
            "callMcpCapability",
            "HYBRID_VECTOR_READ",
            "KG_ONE_HOP_READ",
            "METHOD_SOURCE_READ"
    );

    private final AgentContextManagementProperties properties;
    private final AgentContextArtifactStore artifactStore;
    private final ChatClient chatClient;
    private final StreamingChatClientSupport streamingChatClientSupport;

    public DefaultAgentContextManager(
            AgentContextManagementProperties properties,
            AgentContextArtifactStore artifactStore,
            ChatClient.Builder chatClientBuilder,
            StreamingChatClientSupport streamingChatClientSupport
    ) {
        this.properties = properties;
        this.artifactStore = artifactStore;
        this.chatClient = chatClientBuilder.build();
        this.streamingChatClientSupport = streamingChatClientSupport;
    }

    @Override
    public AgentContextSession restore(String sessionId, AgentConversationManager.ConversationState state) {
        if (state == null) {
            return AgentContextSession.empty();
        }
        if (state.contextSession() != null) {
            return state.contextSession();
        }
        List<AgentContextEntry> migrated = new ArrayList<>();
        long now = System.currentTimeMillis();
        List<AgentContextItem> contexts = state.contexts() == null ? List.of() : state.contexts();
        for (int i = 0; i < contexts.size(); i++) {
            AgentContextItem item = contexts.get(i);
            migrated.add(buildEntry(sessionId, inferKind(item), item, inferCompactable(item), inferRetriable(item), inferToolName(item), inferPriority(item), now + i));
        }
        return new AgentContextSession(migrated, "", 0, 0L, 0L, 0);
    }

    @Override
    public AgentContextSession append(String sessionId, AgentContextSession session, AgentContextItem item, AgentContextAppendOptions options) {
        long now = System.currentTimeMillis();
        AgentContextEntry entry = buildEntry(
                sessionId,
                options.kind(),
                item,
                options.compactable(),
                options.retriable(),
                options.toolName(),
                options.priority(),
                now
        );
        AgentContextSession nextSession = appendEntries(session, List.of(entry));
        return applyWriteTimeCompaction(sessionId, nextSession);
    }

    @Override
    public ToolAppendResult appendToolResult(String sessionId, AgentContextSession session, int round, AgentToolExecutionResult result) {
        long now = System.currentTimeMillis();
        List<AgentContextEntry> resultEntries = new ArrayList<>();
        int totalBytes = 0;
        boolean compactable = isCompactableTool(result.toolName());
        if (result.items() != null) {
            for (AgentContextItem item : result.items()) {
                AgentContextEntry entry = buildEntry(sessionId, AgentContextEntryKind.TOOL_RESULT, item, compactable, compactable, result.toolName(), toolPriority(item), now + resultEntries.size());
                resultEntries.add(entry);
                totalBytes += entry.byteSize();
            }
        }
        if (totalBytes > properties.getToolMessageBytes()) {
            resultEntries = artifactizeLargestEntries(sessionId, resultEntries, totalBytes);
        }
        List<AgentContextItem> itemViews = resultEntries.stream().map(AgentContextEntry::item).toList();
        AgentContextItem summaryItem = new AgentContextItem(
                "conversation",
                "tool_result_round_" + round + "_" + result.toolName(),
                renderToolResultContext(result.toolName(), result.status(), result.message(), result.metrics(), itemViews),
                Map.of(
                        "round", round,
                        "toolName", result.toolName(),
                        "status", result.status(),
                        "itemCount", itemViews.size()
                )
        );
        AgentContextEntry summaryEntry = buildEntry(
                sessionId,
                AgentContextEntryKind.CONVERSATION,
                summaryItem,
                compactable,
                compactable,
                result.toolName(),
                70,
                now - 1
        );
        List<AgentContextEntry> allEntries = new ArrayList<>();
        allEntries.add(summaryEntry);
        allEntries.addAll(resultEntries);
        AgentContextSession nextSession = appendEntries(session, allEntries);
        nextSession = applyWriteTimeCompaction(sessionId, nextSession);
        return new ToolAppendResult(nextSession, result.items() != null && !result.items().isEmpty());
    }

    @Override
    public AgentContextProjection projectForDecision(String sessionId, AgentContextSession session, AgentRequest request, SkillPlan skillPlan) {
        return project(sessionId, session, request, skillPlan, ProjectionPurpose.DECISION);
    }

    @Override
    public AgentContextProjection projectForFinal(String sessionId, AgentContextSession session, AgentRequest request, SkillPlan skillPlan) {
        return project(sessionId, session, request, skillPlan, ProjectionPurpose.FINAL);
    }

    @Override
    public AgentContextProjection projectForVerification(String sessionId, AgentContextSession session, AgentRequest request) {
        return project(sessionId, session, request, null, ProjectionPurpose.VERIFY);
    }

    @Override
    public List<AgentContextItem> snapshotContexts(AgentContextSession session) {
        if (session == null || session.entries() == null || session.entries().isEmpty()) {
            return List.of();
        }
        return session.entries().stream().map(AgentContextEntry::item).toList();
    }

    private AgentContextProjection project(
            String sessionId,
            AgentContextSession input,
            AgentRequest request,
            SkillPlan skillPlan,
            ProjectionPurpose purpose
    ) {
        AgentContextSession session = applyWriteTimeCompaction(sessionId, input);
        int estimatedTokens = estimateTokens(session.entries());
        boolean autoCompacted = false;
        int autoCompactThreshold = properties.getEffectiveContextWindow()
                - properties.getResponseReserveTokens()
                - properties.getAutoCompactBufferTokens();
        if (estimatedTokens > autoCompactThreshold && session.autoCompactFailureCount() < properties.getMaxAutoCompactFailures()) {
            session = autoCompact(sessionId, session, request, skillPlan, purpose);
            estimatedTokens = estimateTokens(session.entries());
            autoCompacted = true;
        }
        ProjectionResult collapseResult = collapseForProjection(session, purpose);
        AgentContextSession touched = touch(session, collapseResult.projectedEntries());
        touched = touched.withLastProjectionAt(System.currentTimeMillis());
        List<AgentContextItem> contexts = collapseResult.projectedEntries().stream().map(AgentContextEntry::item).toList();
        return new AgentContextProjection(touched, contexts, collapseResult.estimatedTokens(), collapseResult.collapsed(), autoCompacted);
    }

    private AgentContextSession autoCompact(
            String sessionId,
            AgentContextSession session,
            AgentRequest request,
            SkillPlan skillPlan,
            ProjectionPurpose purpose
    ) {
        try {
            List<AgentContextEntry> entries = session.entries();
            if (entries.size() < 6) {
                return session;
            }
            int keepTail = Math.min(8, entries.size());
            List<AgentContextEntry> compactedSegment = new ArrayList<>(entries.subList(0, entries.size() - keepTail));
            List<AgentContextEntry> recentTail = new ArrayList<>(entries.subList(entries.size() - keepTail, entries.size()));
            String summary = summarizeForCompaction(compactedSegment, request, skillPlan, purpose, session.snipTokensFreed());
            long now = System.currentTimeMillis();
            List<AgentContextEntry> rebuilt = new ArrayList<>();
            rebuilt.add(AgentContextEntry.marker(
                    newEntryId("auto-compact-boundary"),
                    AgentContextEntryKind.SUMMARY,
                    "auto_compact_boundary",
                    "更早的上下文已被自动压缩为摘要，以下为恢复后的继续工作视图。",
                    Map.of("entriesCompacted", compactedSegment.size(), "purpose", purpose.name()),
                    now,
                    95
            ));
            rebuilt.add(buildEntry(
                    sessionId,
                    AgentContextEntryKind.SUMMARY,
                    new AgentContextItem("summary", "auto_compact_summary", summary, Map.of("source", "auto_compact")),
                    false,
                    false,
                    null,
                    100,
                    now + 1
            ));
            rebuilt.addAll(restoreHotEntries(compactedSegment));
            rebuilt.addAll(recentTail);
            return new AgentContextSession(rebuilt, summary, session.snipTokensFreed(), session.lastProjectionAt(), now, 0);
        } catch (Exception e) {
            log.warn("auto compact failed, sessionId={}, error={}", sessionId, e.getMessage(), e);
            return session.withCompactionState(System.currentTimeMillis(), session.autoCompactFailureCount() + 1);
        }
    }

    private List<AgentContextEntry> restoreHotEntries(List<AgentContextEntry> compactedSegment) {
        List<AgentContextEntry> restorable = compactedSegment.stream()
                .filter(this::isRestorableHotEntry)
                .sorted(Comparator.comparingLong(AgentContextEntry::lastAccessedAt).reversed())
                .toList();
        List<AgentContextEntry> restored = new ArrayList<>();
        int tokenBudget = properties.getPostCompactTokenBudget();
        Set<String> seen = new LinkedHashSet<>();
        for (AgentContextEntry entry : restorable) {
            if (restored.size() >= properties.getPostCompactMaxRestoreEntries()) {
                break;
            }
            if (entry.tokenEstimate() > properties.getPostCompactMaxTokensPerEntry()) {
                continue;
            }
            String dedupeKey = entry.item().sourceId();
            if (!seen.add(dedupeKey)) {
                continue;
            }
            if (tokenBudget - entry.tokenEstimate() < 0) {
                break;
            }
            restored.add(entry);
            tokenBudget -= entry.tokenEstimate();
        }
        return restored;
    }

    private boolean isRestorableHotEntry(AgentContextEntry entry) {
        if (entry == null || entry.item() == null) {
            return false;
        }
        if (entry.kind() != AgentContextEntryKind.TOOL_RESULT) {
            return false;
        }
        if ("todo_reminder".equals(entry.item().sourceType()) || "tool_error".equals(entry.item().sourceType())) {
            return false;
        }
        String sourceId = entry.item().sourceId();
        return StringUtils.hasText(sourceId) && (sourceId.endsWith(".java") || sourceId.endsWith(".xml") || sourceId.endsWith(".md") || sourceId.contains("/"));
    }

    private String summarizeForCompaction(
            List<AgentContextEntry> entries,
            AgentRequest request,
            SkillPlan skillPlan,
            ProjectionPurpose purpose,
            int snipTokensFreed
    ) {
        String payload = entries.stream()
                .map(entry -> "- [" + entry.kind() + "] " + entry.item().sourceId() + "\n" + safeText(entry.item().content()))
                .collect(Collectors.joining("\n"));
        if (payload.isBlank()) {
            return "无可压缩的历史上下文。";
        }
        String systemPrompt = """
                你是一个代码代理的上下文压缩器。请把历史工作整理成结构化摘要，必须覆盖：
                1. 用户主要目标
                2. 已查看/修改的重要文件
                3. 已执行的关键工具和结论
                4. 遇到的问题与修复尝试
                5. 待完成事项与当前状态
                输出简洁但不要遗漏关键信息。
                """;
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("任务类型: ").append(request.taskType()).append("\n");
        userPrompt.append("问题: ").append(safeText(request.question())).append("\n");
        userPrompt.append("压缩用途: ").append(purpose.name()).append("\n");
        userPrompt.append("Snip已释放tokens: ").append(snipTokensFreed).append("\n");
        if (skillPlan != null && StringUtils.hasText(skillPlan.summary())) {
            userPrompt.append("技能摘要: ").append(skillPlan.summary()).append("\n");
        }
        userPrompt.append("历史上下文:\n").append(payload);
        String summary = streamingChatClientSupport.collect(chatClient, systemPrompt, userPrompt.toString());
        if (StringUtils.hasText(summary)) {
            return summary;
        }
        return heuristicSummary(entries, request);
    }

    private String heuristicSummary(List<AgentContextEntry> entries, AgentRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户目标: ").append(safeText(request.question())).append("\n");
        Map<String, Long> byType = entries.stream()
                .collect(Collectors.groupingBy(entry -> entry.item().sourceType(), LinkedHashMap::new, Collectors.counting()));
        builder.append("历史上下文分布: ").append(byType).append("\n");
        builder.append("最近重要条目:\n");
        entries.stream()
                .sorted(Comparator.comparingLong(AgentContextEntry::createdAt).reversed())
                .limit(8)
                .forEach(entry -> builder.append("- [")
                        .append(entry.kind()).append("] ")
                        .append(entry.item().sourceId()).append(": ")
                        .append(safeText(entry.item().content())).append("\n"));
        return builder.toString().trim();
    }

    private ProjectionResult collapseForProjection(AgentContextSession session, ProjectionPurpose purpose) {
        List<AgentContextEntry> entries = new ArrayList<>(session.entries());
        int estimatedTokens = estimateTokens(entries);
        if (estimatedTokens <= properties.getProjectionSoftTokens()) {
            return new ProjectionResult(entries, estimatedTokens, false);
        }
        List<AgentContextEntry> projected = new ArrayList<>();
        List<AgentContextEntry> olderConversation = new ArrayList<>();
        List<AgentContextEntry> recent = new ArrayList<>();
        int recentConversationBudget = purpose == ProjectionPurpose.DECISION ? 10 : 14;
        List<AgentContextEntry> conversationEntries = entries.stream()
                .filter(entry -> entry.kind() == AgentContextEntryKind.CONVERSATION)
                .toList();
        Set<String> recentIds = conversationEntries.stream()
                .skip(Math.max(0, conversationEntries.size() - recentConversationBudget))
                .map(AgentContextEntry::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (AgentContextEntry entry : entries) {
            if (entry.kind() == AgentContextEntryKind.CONVERSATION && !recentIds.contains(entry.id()) && entry.priority() <= 60) {
                olderConversation.add(entry);
            } else {
                recent.add(entry);
            }
        }
        if (!olderConversation.isEmpty()) {
            long now = System.currentTimeMillis();
            projected.add(AgentContextEntry.marker(
                    newEntryId("projection-collapse"),
                    AgentContextEntryKind.ARTIFACT_MARKER,
                    "projection_collapse",
                    "更早的对话轮次在本次调用中被折叠隐藏；如确有需要，可依据后续摘要或重新读取工具结果恢复。",
                    Map.of("collapsedCount", olderConversation.size()),
                    now,
                    92
            ));
        }
        projected.addAll(recent);
        int projectedTokens = estimateTokens(projected);
        if (projectedTokens > properties.getProjectionHardTokens()) {
            projected = trimToHardLimit(projected);
            projectedTokens = estimateTokens(projected);
        }
        return new ProjectionResult(projected, projectedTokens, true);
    }

    private List<AgentContextEntry> trimToHardLimit(List<AgentContextEntry> entries) {
        List<AgentContextEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(AgentContextEntry::priority).reversed().thenComparingLong(AgentContextEntry::createdAt));
        List<AgentContextEntry> kept = new ArrayList<>();
        int budget = properties.getProjectionHardTokens();
        for (int i = sorted.size() - 1; i >= 0; i--) {
            AgentContextEntry entry = sorted.get(i);
            if (entry.tokenEstimate() > budget && entry.priority() < 85) {
                continue;
            }
            kept.add(entry);
            budget -= entry.tokenEstimate();
            if (budget <= 0) {
                break;
            }
        }
        kept.sort(Comparator.comparingLong(AgentContextEntry::createdAt));
        return kept;
    }

    private AgentContextSession applyWriteTimeCompaction(String sessionId, AgentContextSession session) {
        AgentContextSession compacted = applySnip(session);
        compacted = applyMicroCompact(compacted);
        return compacted;
    }

    private AgentContextSession applySnip(AgentContextSession session) {
        int estimatedTokens = estimateTokens(session.entries());
        if (estimatedTokens <= properties.getSnipTriggerTokens()) {
            return session;
        }
        List<AgentContextEntry> entries = new ArrayList<>(session.entries());
        List<Integer> removableIndexes = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            AgentContextEntry entry = entries.get(i);
            if (entry.kind() == AgentContextEntryKind.CONVERSATION && entry.priority() <= 60) {
                removableIndexes.add(i);
            }
        }
        int keepRecent = properties.getSnipKeepRecentConversation();
        if (removableIndexes.size() <= keepRecent) {
            return session;
        }
        List<Integer> toRemove = removableIndexes.subList(0, removableIndexes.size() - keepRecent);
        List<AgentContextEntry> next = new ArrayList<>();
        int freed = 0;
        Set<Integer> removeSet = new LinkedHashSet<>(toRemove);
        for (int i = 0; i < entries.size(); i++) {
            if (removeSet.contains(i) && estimatedTokens - freed > properties.getSnipTargetTokens()) {
                freed += entries.get(i).tokenEstimate();
                continue;
            }
            next.add(entries.get(i));
        }
        if (freed > 0) {
            next.add(0, AgentContextEntry.marker(
                    newEntryId("snip-boundary"),
                    AgentContextEntryKind.ARTIFACT_MARKER,
                    "snip_boundary",
                    "更早的一批对话上下文已被直接清理，以释放上下文空间。",
                    Map.of("snipTokensFreed", freed),
                    System.currentTimeMillis(),
                    91
            ));
            return new AgentContextSession(next, session.compactedSummary(), session.snipTokensFreed() + freed, session.lastProjectionAt(), session.lastCompactionAt(), session.autoCompactFailureCount());
        }
        return session;
    }

    private AgentContextSession applyMicroCompact(AgentContextSession session) {
        long now = System.currentTimeMillis();
        long staleMillis = Duration.ofMinutes(properties.getMicroCompactStaleMinutes()).toMillis();
        boolean stale = session.lastProjectionAt() > 0 && now - session.lastProjectionAt() >= staleMillis;
        if (!stale && estimateTokens(session.entries()) <= properties.getProjectionSoftTokens()) {
            return session;
        }
        List<AgentContextEntry> entries = new ArrayList<>(session.entries());
        List<Integer> compactableIndexes = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).compactable()) {
                compactableIndexes.add(i);
            }
        }
        int keepRecent = properties.getMicroCompactKeepRecent();
        if (compactableIndexes.size() <= keepRecent) {
            return session;
        }
        Set<Integer> keepSet = new LinkedHashSet<>(compactableIndexes.subList(Math.max(0, compactableIndexes.size() - keepRecent), compactableIndexes.size()));
        List<AgentContextEntry> next = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            AgentContextEntry entry = entries.get(i);
            if (!entry.compactable() || keepSet.contains(i) || entry.compacted()) {
                next.add(entry);
                continue;
            }
            next.add(compactEntry(entry));
        }
        return session.withEntries(next);
    }

    private AgentContextEntry compactEntry(AgentContextEntry entry) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (entry.item().metadata() != null) {
            metadata.putAll(entry.item().metadata());
        }
        if (entry.artifactRef() != null) {
            metadata.put("artifactPath", entry.artifactRef().relativePath());
            metadata.put("artifactId", entry.artifactRef().artifactId());
        }
        metadata.put("compacted", true);
        metadata.put("retriable", entry.retriable());
        String marker = "该工具结果已被裁剪。tool=" + safeText(entry.toolName())
                + ", sourceId=" + safeText(entry.item().sourceId())
                + (entry.artifactRef() == null ? "" : ", artifact=" + entry.artifactRef().relativePath())
                + "。如果后续需要，可重新执行工具获取完整内容。";
        AgentContextItem item = new AgentContextItem(entry.item().sourceType(), entry.item().sourceId(), marker, metadata);
        return entry.withCompactedItem(item, bytes(item.content()), estimateTokens(item.content()));
    }

    private List<AgentContextEntry> artifactizeLargestEntries(String sessionId, List<AgentContextEntry> entries, int totalBytes) {
        List<AgentContextEntry> sorted = entries.stream()
                .sorted(Comparator.comparingInt(AgentContextEntry::byteSize).reversed())
                .toList();
        Map<String, AgentContextEntry> replacements = new HashMap<>();
        int currentBytes = totalBytes;
        for (AgentContextEntry entry : sorted) {
            if (currentBytes <= properties.getToolMessageBytes()) {
                break;
            }
            if (entry.artifactRef() != null) {
                continue;
            }
            AgentContextEntry artifactized = artifactizeEntry(sessionId, entry);
            replacements.put(entry.id(), artifactized);
            currentBytes -= Math.max(0, entry.byteSize() - artifactized.byteSize());
        }
        List<AgentContextEntry> result = new ArrayList<>(entries.size());
        for (AgentContextEntry entry : entries) {
            result.add(replacements.getOrDefault(entry.id(), entry));
        }
        return result;
    }

    private AgentContextEntry buildEntry(
            String sessionId,
            AgentContextEntryKind kind,
            AgentContextItem item,
            boolean compactable,
            boolean retriable,
            String toolName,
            int priority,
            long now
    ) {
        int byteSize = bytes(item.content());
        int tokenEstimate = estimateTokens(item.content());
        AgentContextEntry entry = AgentContextEntry.of(newEntryId(item.sourceId()), kind, item, compactable, retriable, toolName, null, byteSize, tokenEstimate, now, priority);
        if (byteSize > properties.getSingleArtifactBytes()) {
            return artifactizeEntry(sessionId, entry);
        }
        return entry;
    }

    private AgentContextEntry artifactizeEntry(String sessionId, AgentContextEntry entry) {
        AgentContextArtifactRef artifactRef = artifactStore.persist(
                sessionId,
                entry.id(),
                entry.item().content(),
                entry.item().metadata() == null ? Map.of() : entry.item().metadata()
        );
        String preview = preview(entry.item().content());
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (entry.item().metadata() != null) {
            metadata.putAll(entry.item().metadata());
        }
        metadata.put("artifactPath", artifactRef.relativePath());
        metadata.put("artifactId", artifactRef.artifactId());
        metadata.put("artifactized", true);
        metadata.put("fullBytes", artifactRef.fullBytes());
        AgentContextItem previewItem = new AgentContextItem(entry.item().sourceType(), entry.item().sourceId(), preview, metadata);
        return entry.withArtifact(previewItem, artifactRef, bytes(preview), estimateTokens(preview));
    }

    private AgentContextSession appendEntries(AgentContextSession session, Collection<AgentContextEntry> entries) {
        List<AgentContextEntry> next = new ArrayList<>(session == null ? List.of() : session.entries());
        next.addAll(entries);
        if (session == null) {
            return AgentContextSession.empty().withEntries(next);
        }
        return session.withEntries(next);
    }

    private AgentContextSession touch(AgentContextSession session, List<AgentContextEntry> projectedEntries) {
        Set<String> ids = projectedEntries.stream().map(AgentContextEntry::id).collect(Collectors.toSet());
        long now = System.currentTimeMillis();
        List<AgentContextEntry> next = session.entries().stream()
                .map(entry -> ids.contains(entry.id()) ? entry.touch(now) : entry)
                .toList();
        return session.withEntries(next);
    }

    private AgentContextEntryKind inferKind(AgentContextItem item) {
        if (item == null) {
            return AgentContextEntryKind.SYSTEM;
        }
        if ("todo_reminder".equals(item.sourceType())) {
            return AgentContextEntryKind.TODO;
        }
        if ("verifier".equals(item.sourceType())) {
            return AgentContextEntryKind.VERIFIER;
        }
        if ("summary".equals(item.sourceType())) {
            return AgentContextEntryKind.SUMMARY;
        }
        if ("conversation".equals(item.sourceType()) || StringUtils.hasText(item.sourceId()) && item.sourceId().startsWith("tool_result_round_")) {
            return AgentContextEntryKind.CONVERSATION;
        }
        return AgentContextEntryKind.TOOL_RESULT;
    }

    private boolean inferCompactable(AgentContextItem item) {
        if (item == null) {
            return false;
        }
        if ("tool_error".equals(item.sourceType()) || "todo_reminder".equals(item.sourceType()) || "verifier".equals(item.sourceType())) {
            return false;
        }
        Object toolName = item.metadata() == null ? null : item.metadata().get("toolName");
        return toolName instanceof String tool && isCompactableTool(tool);
    }

    private boolean inferRetriable(AgentContextItem item) {
        return inferCompactable(item);
    }

    private String inferToolName(AgentContextItem item) {
        if (item == null || item.metadata() == null) {
            return null;
        }
        Object toolName = item.metadata().get("toolName");
        return toolName instanceof String s ? s : null;
    }

    private int inferPriority(AgentContextItem item) {
        if (item == null) {
            return 50;
        }
        if ("todo_reminder".equals(item.sourceType())) {
            return 90;
        }
        if ("verifier".equals(item.sourceType())) {
            return 85;
        }
        if ("conversation".equals(item.sourceType())) {
            return 50;
        }
        if ("tool_error".equals(item.sourceType())) {
            return 88;
        }
        return toolPriority(item);
    }

    private int toolPriority(AgentContextItem item) {
        if (item == null) {
            return 70;
        }
        if ("tool_error".equals(item.sourceType())) {
            return 88;
        }
        if ("build".equals(item.sourceType()) || "lsp_java".equals(item.sourceType())) {
            return 80;
        }
        return 72;
    }

    private boolean isCompactableTool(String toolName) {
        return StringUtils.hasText(toolName) && COMPACTABLE_TOOLS.contains(toolName);
    }

    private int estimateTokens(List<AgentContextEntry> entries) {
        return entries.stream().mapToInt(AgentContextEntry::tokenEstimate).sum();
    }

    private int estimateTokens(String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        return Math.max(1, content.length() / 4);
    }

    private int bytes(String content) {
        if (content == null) {
            return 0;
        }
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    private String preview(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= properties.getPreviewBytes()) {
            return content;
        }
        return new String(bytes, 0, properties.getPreviewBytes(), StandardCharsets.UTF_8) + "\n...(preview truncated, full content stored as artifact)";
    }

    private String safeText(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return content.trim();
    }

    private String renderToolResultContext(
            String toolName,
            String status,
            String message,
            Map<String, Object> metrics,
            List<AgentContextItem> items
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("toolName: ").append(toolName).append("\n");
        builder.append("status: ").append(status).append("\n");
        builder.append("message: ").append(message).append("\n");
        builder.append("metrics: ").append(metrics == null ? Map.of() : metrics).append("\n");
        builder.append("items:\n");
        if (items == null || items.isEmpty()) {
            builder.append("- 无\n");
        } else {
            for (AgentContextItem item : items) {
                builder.append("- [").append(item.sourceType()).append("] ")
                        .append(item.sourceId()).append("\n")
                        .append(item.content()).append("\n")
                        .append("  metadata: ").append(item.metadata() == null ? Map.of() : item.metadata()).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private String newEntryId(String sourceId) {
        return (StringUtils.hasText(sourceId) ? sourceId.replaceAll("[^A-Za-z0-9_.-]", "_") : "context") + "_" + UUID.randomUUID();
    }

    private enum ProjectionPurpose {
        DECISION,
        FINAL,
        VERIFY
    }

    private record ProjectionResult(List<AgentContextEntry> projectedEntries, int estimatedTokens, boolean collapsed) {
    }
}
