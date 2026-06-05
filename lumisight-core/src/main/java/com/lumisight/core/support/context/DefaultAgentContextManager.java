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
    private static final String COMPACTED_TOOL_RESULT_MARKER = "[Old tool result content cleared. Re-run the tool to restore details.]";
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
            "searchHybridVector",
            "fetchOneHopByKgNodeId",
            "fetchMethodSourceByLocation"
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
        return new AgentContextSession(migrated, new ArrayList<>(), "", 0, 0L, 0L, 0);
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
        return applyWriteTimeCompaction(sessionId, nextSession).session();
    }

    @Override
    public ToolAppendResult appendToolResult(String sessionId, AgentContextSession session, int round, AgentToolExecutionResult result) {
        long now = System.currentTimeMillis();
        List<AgentContextEntry> resultEntries = new ArrayList<>();
        int totalBytes = 0;
        boolean compactable = isCompactableTool(result.toolName());
        if (result.items() != null) {
            for (AgentContextItem item : result.items()) {
                AgentContextEntry entry = buildEntry(
                        sessionId,
                        AgentContextEntryKind.TOOL_RESULT,
                        item,
                        compactable,
                        compactable,
                        result.toolName(),
                        toolPriority(item),
                        now + resultEntries.size()
                );
                resultEntries.add(entry);
                totalBytes += entry.byteSize();
            }
        }
        if (totalBytes > properties.getToolMessageBytes()) {
            resultEntries = artifactizeLargestEntries(sessionId, resultEntries, totalBytes);
        }
        resultEntries = resultEntries.stream().map(this::decorateToolEntry).toList();

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
        nextSession = mergeHotCacheEntries(nextSession, buildHotCacheEntries(resultEntries));
        nextSession = applyWriteTimeCompaction(sessionId, nextSession).session();
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
        WriteTimeCompactionResult writeResult = applyWriteTimeCompaction(sessionId, input);
        AgentContextSession session = writeResult.session();
        List<AgentContextCompressionStage> stages = new ArrayList<>(writeResult.stages());
        Map<String, Object> metrics = new LinkedHashMap<>(writeResult.metrics());

        int estimatedTokens = estimateTokens(session.entries());
        boolean autoCompacted = false;
        int autoCompactThreshold = properties.getEffectiveContextWindow()
                - properties.getResponseReserveTokens()
                - properties.getAutoCompactBufferTokens();
        if (estimatedTokens > autoCompactThreshold && session.autoCompactFailureCount() < properties.getMaxAutoCompactFailures()) {
            CompactionStepResult autoCompactResult = autoCompact(sessionId, session, request, skillPlan, purpose);
            session = autoCompactResult.session();
            estimatedTokens = estimateTokens(session.entries());
            autoCompacted = autoCompactResult.applied();
            appendStage(stages, metrics, autoCompactResult);
        }

        ProjectionResult collapseResult = collapseForProjection(session, purpose);
        AgentContextSession touched = touch(session, collapseResult.projectedEntries());
        touched = touched.withLastProjectionAt(System.currentTimeMillis());

        stages.addAll(collapseResult.stages());
        metrics.putAll(collapseResult.metrics());
        List<AgentContextItem> contexts = collapseResult.projectedEntries().stream().map(AgentContextEntry::item).toList();
        return new AgentContextProjection(
                touched,
                contexts,
                collapseResult.estimatedTokens(),
                collapseResult.collapsed(),
                autoCompacted,
                List.copyOf(stages),
                Map.copyOf(metrics)
        );
    }

    private CompactionStepResult autoCompact(
            String sessionId,
            AgentContextSession session,
            AgentRequest request,
            SkillPlan skillPlan,
            ProjectionPurpose purpose
    ) {
        try {
            List<AgentContextEntry> entries = session.entries();
            if (entries.size() < 6) {
                return CompactionStepResult.noop(session);
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
                    Map.of(
                            "entriesCompacted", compactedSegment.size(),
                            "purpose", purpose.name(),
                            "snipTokensFreed", session.snipTokensFreed(),
                            "trigger", "context_threshold"
                    ),
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

            RestoreBundle hotRestore = restoreHotEntries(session, recentTail, now + 2);
            rebuilt.addAll(hotRestore.entries());

            RestoreBundle skillRestore = restoreSkillEntries(sessionId, skillPlan, hotRestore.remainingTokenBudget(), now + 100);
            rebuilt.addAll(skillRestore.entries());

            RestoreBundle planRestore = restorePlanEntries(session, compactedSegment, recentTail, skillRestore.remainingTokenBudget(), now + 200);
            rebuilt.addAll(planRestore.entries());

            rebuilt.addAll(recentTail);
            AgentContextSession nextSession = new AgentContextSession(
                    rebuilt,
                    session.hotCacheEntries(),
                    summary,
                    session.snipTokensFreed(),
                    session.lastProjectionAt(),
                    now,
                    0
            );
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("entriesCompacted", compactedSegment.size());
            metrics.put("restoredHotEntries", hotRestore.entries().size());
            metrics.put("restoredSkillEntries", skillRestore.entries().size());
            metrics.put("restoredPlanEntries", planRestore.entries().size());
            metrics.put("summaryTokens", estimateTokens(summary));
            return new CompactionStepResult(
                    nextSession,
                    true,
                    List.of(
                            AgentContextCompressionStage.AUTO_COMPACT,
                            AgentContextCompressionStage.RESTORE_HOT_CACHE,
                            AgentContextCompressionStage.RESTORE_SKILL,
                            AgentContextCompressionStage.RESTORE_PLAN
                    ),
                    metrics
            );
        } catch (Exception e) {
            log.warn("auto compact failed, sessionId={}, error={}", sessionId, e.getMessage(), e);
            return new CompactionStepResult(
                    session.withCompactionState(System.currentTimeMillis(), session.autoCompactFailureCount() + 1),
                    false,
                    List.of(),
                    Map.of("autoCompactFailureCount", session.autoCompactFailureCount() + 1, "error", e.getMessage())
            );
        }
    }

    private RestoreBundle restoreHotEntries(AgentContextSession session, List<AgentContextEntry> recentTail, long now) {
        List<AgentContextHotCacheEntry> hotCacheEntries = session.hotCacheEntries() == null ? List.of() : session.hotCacheEntries();
        if (hotCacheEntries.isEmpty()) {
            return new RestoreBundle(List.of(), properties.getPostCompactTokenBudget());
        }
        Set<String> existingResources = collectLogicalResourceIds(recentTail);
        List<AgentContextHotCacheEntry> restorable = hotCacheEntries.stream()
                .filter(AgentContextHotCacheEntry::restorable)
                .sorted(Comparator.comparingLong(AgentContextHotCacheEntry::lastAccessedAt).reversed())
                .toList();
        List<AgentContextEntry> restored = new ArrayList<>();
        int tokenBudget = properties.getPostCompactTokenBudget();
        Set<String> seen = new LinkedHashSet<>();
        for (AgentContextHotCacheEntry entry : restorable) {
            if (restored.size() >= properties.getPostCompactMaxRestoreEntries()) {
                break;
            }
            if (entry.tokenEstimate() > properties.getPostCompactMaxTokensPerEntry()) {
                continue;
            }
            String logicalResourceId = entry.logicalResourceId();
            if (StringUtils.hasText(logicalResourceId) && (existingResources.contains(logicalResourceId) || !seen.add(logicalResourceId))) {
                continue;
            }
            if (tokenBudget - entry.tokenEstimate() < 0) {
                continue;
            }
            AgentContextItem restoredItem = restoreHotCacheItem(entry);
            AgentContextEntry restoredEntry = entry.toRestoredEntry(
                    newEntryId("restore_hot_" + safeId(entry.logicalResourceId())),
                    now + restored.size(),
                    87,
                    restoredItem
            );
            restoredEntry = decorateRestoredEntry(restoredEntry, "hot_cache_restore");
            restored.add(restoredEntry);
            tokenBudget -= entry.tokenEstimate();
        }
        return new RestoreBundle(restored, tokenBudget);
    }

    private RestoreBundle restoreSkillEntries(String sessionId, SkillPlan skillPlan, int remainingBudget, long now) {
        if (skillPlan == null || remainingBudget <= 0) {
            return new RestoreBundle(List.of(), remainingBudget);
        }
        int skillBudget = Math.min(properties.getPostCompactSkillTokenBudget(), remainingBudget);
        if (skillBudget <= 0) {
            return new RestoreBundle(List.of(), remainingBudget);
        }

        StringBuilder content = new StringBuilder();
        if (StringUtils.hasText(skillPlan.summary())) {
            content.append("当前活跃技能摘要: ").append(skillPlan.summary()).append("\n");
        }
        if (skillPlan.executionSteps() != null && !skillPlan.executionSteps().isEmpty()) {
            content.append("技能步骤:\n");
            for (String step : skillPlan.executionSteps()) {
                content.append("- ").append(step).append("\n");
            }
        }
        if (StringUtils.hasText(skillPlan.outputContract())) {
            content.append("技能输出约束: ").append(skillPlan.outputContract()).append("\n");
        }
        if (StringUtils.hasText(skillPlan.rawSkillContent())) {
            content.append("技能原文:\n");
            content.append(trimToTokenBudget(skillPlan.rawSkillContent(), skillBudget - estimateTokens(content.toString())));
        }
        String rendered = content.toString().trim();
        if (!StringUtils.hasText(rendered)) {
            return new RestoreBundle(List.of(), remainingBudget);
        }
        AgentContextEntry entry = buildEntry(
                sessionId,
                AgentContextEntryKind.SYSTEM,
                new AgentContextItem("skill_restore", "active_skill_restore", rendered, Map.of("restored", true)),
                false,
                false,
                null,
                89,
                now
        );
        int consumed = Math.min(skillBudget, entry.tokenEstimate());
        return new RestoreBundle(List.of(entry), Math.max(0, remainingBudget - consumed));
    }

    private RestoreBundle restorePlanEntries(
            AgentContextSession session,
            List<AgentContextEntry> compactedSegment,
            List<AgentContextEntry> recentTail,
            int remainingBudget,
            long now
    ) {
        if (remainingBudget <= 0) {
            return new RestoreBundle(List.of(), remainingBudget);
        }
        boolean alreadyPresent = recentTail.stream().anyMatch(this::isPlanOrTodoEntry);
        if (alreadyPresent) {
            return new RestoreBundle(List.of(), remainingBudget);
        }
        AgentContextEntry latestPlan = latestPlanEntry(session, compactedSegment);
        if (latestPlan == null || latestPlan.tokenEstimate() > Math.min(remainingBudget, properties.getPostCompactMaxTokensPerEntry())) {
            return new RestoreBundle(List.of(), remainingBudget);
        }
        AgentContextEntry restored = decorateRestoredEntry(
                AgentContextEntry.of(
                        newEntryId("restore_plan"),
                        AgentContextEntryKind.TODO,
                        new AgentContextItem(
                                latestPlan.item().sourceType(),
                                latestPlan.item().sourceId(),
                                latestPlan.item().content(),
                                mergeMetadata(latestPlan.item().metadata(), Map.of("restored", true, "restoredFrom", "plan_restore"))
                        ),
                        false,
                        false,
                        latestPlan.toolName(),
                        latestPlan.artifactRef(),
                        latestPlan.byteSize(),
                        latestPlan.tokenEstimate(),
                        now,
                        90
                ),
                "plan_restore"
        );
        return new RestoreBundle(List.of(restored), remainingBudget - restored.tokenEstimate());
    }

    private AgentContextEntry latestPlanEntry(AgentContextSession session, List<AgentContextEntry> compactedSegment) {
        List<AgentContextEntry> candidates = new ArrayList<>();
        if (session.hotCacheEntries() != null) {
            session.hotCacheEntries().stream()
                    .filter(cache -> "todo_write".equals(cache.toolName()) || "todo_write".equals(cache.item().sourceType()))
                    .sorted(Comparator.comparingLong(AgentContextHotCacheEntry::lastAccessedAt).reversed())
                    .findFirst()
                    .ifPresent(cache -> candidates.add(cache.toRestoredEntry(newEntryId("plan_cache"), System.currentTimeMillis(), 90)));
        }
        compactedSegment.stream()
                .filter(this::isPlanOrTodoEntry)
                .max(Comparator.comparingLong(AgentContextEntry::lastAccessedAt))
                .ifPresent(candidates::add);
        return candidates.stream().max(Comparator.comparingLong(AgentContextEntry::lastAccessedAt)).orElse(null);
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
                你是一个代码代理的上下文压缩器。请输出结构化摘要，必须包含以下小节且不能遗漏任何一类关键信息：
                1. 用户主要请求与意图（覆盖历史关键用户要求）
                2. 关键文件与代码位置
                3. 关键命令/工具调用及结论
                4. 遇到的错误、失败尝试与修复进展
                5. 当前状态、未完成事项与下一步
                规则：
                - 不要输出寒暄。
                - 优先保留对继续完成任务有决定作用的事实。
                - 对“用户提出过但尚未完成”的要求必须显式写出。
                - 若历史里出现多个候选方案，写清当前采用哪个以及为何。
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
        builder.append("## 用户主要请求与意图\n");
        builder.append("- ").append(safeText(request.question())).append("\n");
        builder.append("## 关键文件与代码位置\n");
        entries.stream()
                .filter(entry -> StringUtils.hasText(entry.item().sourceId()) && entry.item().sourceId().contains("."))
                .limit(5)
                .forEach(entry -> builder.append("- ").append(entry.item().sourceId()).append("\n"));
        builder.append("## 关键命令/工具调用及结论\n");
        entries.stream()
                .filter(entry -> StringUtils.hasText(entry.toolName()))
                .limit(5)
                .forEach(entry -> builder.append("- ").append(entry.toolName()).append(": ").append(safeText(entry.item().content())).append("\n"));
        builder.append("## 当前状态、未完成事项与下一步\n");
        builder.append("- 需要根据最近摘要和恢复内容继续推进。\n");
        return builder.toString().trim();
    }

    private ProjectionResult collapseForProjection(AgentContextSession session, ProjectionPurpose purpose) {
        List<AgentContextEntry> entries = new ArrayList<>(session.entries());
        int estimatedTokens = estimateTokens(entries);
        if (estimatedTokens <= properties.getProjectionSoftTokens()) {
            return new ProjectionResult(entries, estimatedTokens, false, List.of(), Map.of());
        }

        int recentConversationBudget = purpose == ProjectionPurpose.DECISION ? 10 : 14;
        Set<String> recentConversationIds = recentConversationIds(entries, recentConversationBudget);
        LinkedHashSet<String> requiredIds = new LinkedHashSet<>();
        for (AgentContextEntry entry : entries) {
            if (isAlwaysProjected(entry) || recentConversationIds.contains(entry.id())) {
                requiredIds.add(entry.id());
            }
        }

        int targetBudget = estimatedTokens > properties.getProjectionHardTokens()
                ? properties.getProjectionHardTokens()
                : properties.getProjectionSoftTokens();
        List<AgentContextEntry> selected = new ArrayList<>();
        int usedTokens = 0;
        for (AgentContextEntry entry : entries) {
            if (requiredIds.contains(entry.id())) {
                selected.add(entry);
                usedTokens += entry.tokenEstimate();
            }
        }

        List<AgentContextEntry> optional = entries.stream()
                .filter(entry -> !requiredIds.contains(entry.id()))
                .sorted(this::compareProjectionPriority)
                .toList();
        for (AgentContextEntry entry : optional) {
            if (usedTokens >= targetBudget && entry.priority() < 88) {
                continue;
            }
            if (usedTokens + entry.tokenEstimate() > targetBudget && entry.priority() < 92) {
                continue;
            }
            selected.add(entry);
            usedTokens += entry.tokenEstimate();
        }

        selected = selected.stream()
                .sorted(Comparator.comparingLong(AgentContextEntry::createdAt))
                .toList();
        List<AgentContextEntry> finalSelected = selected;
        int collapsedCount = Math.max(0, entries.size() - selected.size());
        Map<String, Long> hiddenByKind = entries.stream()
                .filter(entry -> finalSelected.stream().noneMatch(kept -> kept.id().equals(entry.id())))
                .collect(Collectors.groupingBy(entry -> entry.kind().name(), LinkedHashMap::new, Collectors.counting()));

        List<AgentContextEntry> projected = new ArrayList<>();
        if (collapsedCount > 0) {
            projected.add(AgentContextEntry.marker(
                    newEntryId("projection-collapse"),
                    AgentContextEntryKind.ARTIFACT_MARKER,
                    "projection_collapse",
                    "本轮仅投影最重要的上下文视图；更早或可重取的条目已隐藏，必要时可重新读取。",
                    Map.of(
                            "collapsedCount", collapsedCount,
                            "hiddenByKind", hiddenByKind,
                            "targetBudget", targetBudget,
                            "purpose", purpose.name()
                    ),
                    System.currentTimeMillis(),
                    92
            ));
        }
        projected.addAll(selected);

        int projectedTokens = estimateTokens(projected);
        if (projectedTokens > properties.getProjectionHardTokens()) {
            projected = trimToHardLimit(projected, recentConversationIds);
            projectedTokens = estimateTokens(projected);
        }
        return new ProjectionResult(
                projected,
                projectedTokens,
                collapsedCount > 0,
                collapsedCount > 0 ? List.of(AgentContextCompressionStage.CONTEXT_COLLAPSE) : List.of(),
                collapsedCount > 0 ? Map.of("collapsedCount", collapsedCount, "hiddenByKind", hiddenByKind, "projectedTokens", projectedTokens) : Map.of("projectedTokens", projectedTokens)
        );
    }

    private List<AgentContextEntry> trimToHardLimit(List<AgentContextEntry> entries, Set<String> recentConversationIds) {
        List<AgentContextEntry> required = entries.stream()
                .filter(entry -> isAlwaysProjected(entry) || recentConversationIds.contains(entry.id()))
                .sorted(Comparator.comparingLong(AgentContextEntry::createdAt))
                .toList();
        int used = estimateTokens(required);
        List<AgentContextEntry> kept = new ArrayList<>(required);
        if (used >= properties.getProjectionHardTokens()) {
            return kept;
        }
        List<AgentContextEntry> optional = entries.stream()
                .filter(entry -> required.stream().noneMatch(requiredEntry -> requiredEntry.id().equals(entry.id())))
                .sorted(this::compareProjectionPriority)
                .toList();
        for (AgentContextEntry entry : optional) {
            if (used + entry.tokenEstimate() > properties.getProjectionHardTokens() && entry.priority() < 90) {
                continue;
            }
            kept.add(entry);
            used += entry.tokenEstimate();
            if (used >= properties.getProjectionHardTokens()) {
                break;
            }
        }
        kept.sort(Comparator.comparingLong(AgentContextEntry::createdAt));
        return kept;
    }

    private WriteTimeCompactionResult applyWriteTimeCompaction(String sessionId, AgentContextSession session) {
        CompactionStepResult snipResult = applySnip(session);
        AgentContextSession compacted = snipResult.session();
        CompactionStepResult microResult = applyMicroCompact(compacted);
        compacted = microResult.session();

        List<AgentContextCompressionStage> stages = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        appendStage(stages, metrics, snipResult);
        appendStage(stages, metrics, microResult);
        return new WriteTimeCompactionResult(compacted, List.copyOf(stages), Map.copyOf(metrics));
    }

    private CompactionStepResult applySnip(AgentContextSession session) {
        int estimatedTokens = estimateTokens(session.entries());
        if (estimatedTokens <= properties.getSnipTriggerTokens()) {
            return CompactionStepResult.noop(session);
        }
        List<AgentContextEntry> entries = new ArrayList<>(session.entries());
        List<Integer> removableIndexes = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            AgentContextEntry entry = entries.get(i);
            if (entry.kind() == AgentContextEntryKind.CONVERSATION
                    && entry.priority() <= 60
                    && !isAlwaysProjected(entry)
                    && !"context_marker".equals(entry.item().sourceType())) {
                removableIndexes.add(i);
            }
        }
        int keepRecent = properties.getSnipKeepRecentConversation();
        if (removableIndexes.size() <= keepRecent) {
            return CompactionStepResult.noop(session);
        }
        List<Integer> toRemove = removableIndexes.subList(0, removableIndexes.size() - keepRecent);
        List<AgentContextEntry> next = new ArrayList<>();
        int freed = 0;
        int removedCount = 0;
        Set<Integer> removeSet = new LinkedHashSet<>(toRemove);
        for (int i = 0; i < entries.size(); i++) {
            if (removeSet.contains(i) && estimatedTokens - freed > properties.getSnipTargetTokens()) {
                freed += entries.get(i).tokenEstimate();
                removedCount++;
                continue;
            }
            next.add(entries.get(i));
        }
        if (freed <= 0) {
            return CompactionStepResult.noop(session);
        }
        next.add(0, AgentContextEntry.marker(
                newEntryId("snip-boundary"),
                AgentContextEntryKind.ARTIFACT_MARKER,
                "snip_boundary",
                "更早的对话轮次已被直接清理，以释放上下文空间。",
                Map.of(
                        "snipTokensFreed", freed,
                        "removedConversationEntries", removedCount,
                        "trigger", "snip_trigger_tokens",
                        "beforeTokens", estimatedTokens
                ),
                System.currentTimeMillis(),
                91
        ));
        AgentContextSession nextSession = new AgentContextSession(
                next,
                session.hotCacheEntries(),
                session.compactedSummary(),
                session.snipTokensFreed() + freed,
                session.lastProjectionAt(),
                session.lastCompactionAt(),
                session.autoCompactFailureCount()
        );
        return new CompactionStepResult(
                nextSession,
                true,
                List.of(AgentContextCompressionStage.SNIP),
                Map.of("snipTokensFreed", freed, "removedConversationEntries", removedCount)
        );
    }

    private CompactionStepResult applyMicroCompact(AgentContextSession session) {
        long now = System.currentTimeMillis();
        long staleMillis = Duration.ofMinutes(properties.getMicroCompactStaleMinutes()).toMillis();
        boolean stale = session.lastProjectionAt() > 0 && now - session.lastProjectionAt() >= staleMillis;
        boolean oversized = estimateTokens(session.entries()) > properties.getProjectionSoftTokens();
        if (!stale && !oversized) {
            return CompactionStepResult.noop(session);
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
            return CompactionStepResult.noop(session);
        }
        Set<Integer> keepSet = new LinkedHashSet<>(compactableIndexes.subList(
                Math.max(0, compactableIndexes.size() - keepRecent),
                compactableIndexes.size()
        ));
        List<AgentContextEntry> next = new ArrayList<>();
        int compactedCount = 0;
        for (int i = 0; i < entries.size(); i++) {
            AgentContextEntry entry = entries.get(i);
            if (!entry.compactable() || keepSet.contains(i) || entry.compacted()) {
                next.add(entry);
                continue;
            }
            next.add(compactEntry(entry));
            compactedCount++;
        }
        if (compactedCount == 0) {
            return CompactionStepResult.noop(session);
        }
        return new CompactionStepResult(
                session.withEntries(next),
                true,
                List.of(AgentContextCompressionStage.MICRO_COMPACT),
                Map.of("microCompactedEntries", compactedCount, "staleTriggered", stale, "oversizedTriggered", oversized)
        );
    }

    private AgentContextEntry compactEntry(AgentContextEntry entry) {
        Map<String, Object> metadata = mergeMetadata(entry.item().metadata(), Map.of(
                "compacted", true,
                "retriable", entry.retriable()
        ));
        if (entry.artifactRef() != null) {
            metadata = mergeMetadata(metadata, Map.of(
                    "artifactPath", entry.artifactRef().relativePath(),
                    "artifactId", entry.artifactRef().artifactId()
            ));
        }
        AgentContextItem item = new AgentContextItem(entry.item().sourceType(), entry.item().sourceId(), COMPACTED_TOOL_RESULT_MARKER, metadata);
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
        AgentContextEntry seed = AgentContextEntry.of(
                newEntryId(item.sourceId()),
                kind,
                item,
                compactable,
                retriable,
                toolName,
                null,
                byteSize,
                tokenEstimate,
                now,
                priority
        );
        AgentContextEntry entry = decorateToolEntry(seed);
        if (byteSize > properties.getSingleArtifactBytes()) {
            entry = artifactizeEntry(sessionId, entry);
        }
        return entry;
    }

    private AgentContextEntry artifactizeEntry(String sessionId, AgentContextEntry entry) {
        int fullTokens = estimateTokens(entry.item().content());
        AgentContextArtifactRef artifactRef = artifactStore.persist(
                sessionId,
                entry.id(),
                entry.item().content(),
                entry.item().metadata() == null ? Map.of() : entry.item().metadata()
        );
        String preview = preview(entry.item().content());
        Map<String, Object> metadata = mergeMetadata(entry.item().metadata(), Map.of(
                "artifactPath", artifactRef.relativePath(),
                "artifactId", artifactRef.artifactId(),
                "artifactized", true,
                "fullBytes", artifactRef.fullBytes(),
                "fullTokens", fullTokens
        ));
        AgentContextItem previewItem = new AgentContextItem(entry.item().sourceType(), entry.item().sourceId(), preview, metadata);
        return entry.withArtifact(previewItem, artifactRef, bytes(preview), estimateTokens(preview));
    }

    private AgentContextEntry decorateToolEntry(AgentContextEntry entry) {
        if (!StringUtils.hasText(entry.toolName()) && entry.kind() != AgentContextEntryKind.TOOL_RESULT) {
            return entry;
        }
        String logicalResourceId = logicalResourceId(entry.toolName(), entry.item());
        boolean restorable = isRestorableToolResult(entry.toolName(), entry.item());
        Map<String, Object> metadata = mergeMetadata(entry.item().metadata(), Map.of(
                "toolName", entry.toolName() == null ? "" : entry.toolName(),
                "logicalResourceId", logicalResourceId,
                "retriable", entry.retriable(),
                "restorable", restorable
        ));
        AgentContextItem decorated = new AgentContextItem(entry.item().sourceType(), entry.item().sourceId(), entry.item().content(), metadata);
        return entry.withItem(decorated, bytes(decorated.content()), estimateTokens(decorated.content()));
    }

    private AgentContextEntry decorateRestoredEntry(AgentContextEntry entry, String restoredFrom) {
        Map<String, Object> metadata = mergeMetadata(entry.item().metadata(), Map.of("restored", true, "restoredFrom", restoredFrom));
        AgentContextItem item = new AgentContextItem(entry.item().sourceType(), entry.item().sourceId(), entry.item().content(), metadata);
        return entry.withItem(item, bytes(item.content()), estimateTokens(item.content()));
    }

    private AgentContextSession appendEntries(AgentContextSession session, Collection<AgentContextEntry> entries) {
        List<AgentContextEntry> next = new ArrayList<>(session == null ? List.of() : session.entries());
        next.addAll(entries);
        if (session == null) {
            return AgentContextSession.empty().withEntries(next);
        }
        return session.withEntries(next);
    }

    private AgentContextSession mergeHotCacheEntries(AgentContextSession session, List<AgentContextHotCacheEntry> newEntries) {
        if (newEntries == null || newEntries.isEmpty()) {
            return session;
        }
        Map<String, AgentContextHotCacheEntry> merged = new LinkedHashMap<>();
        List<AgentContextHotCacheEntry> existing = session.hotCacheEntries() == null ? List.of() : session.hotCacheEntries();
        for (AgentContextHotCacheEntry entry : existing) {
            merged.put(cacheKey(entry), entry);
        }
        for (AgentContextHotCacheEntry entry : newEntries) {
            merged.put(cacheKey(entry), entry);
        }
        List<AgentContextHotCacheEntry> ordered = merged.values().stream()
                .sorted(Comparator.comparingLong(AgentContextHotCacheEntry::lastAccessedAt).reversed())
                .limit(64)
                .toList();
        return session.withHotCacheEntries(ordered);
    }

    private List<AgentContextHotCacheEntry> buildHotCacheEntries(List<AgentContextEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<AgentContextHotCacheEntry> hotCacheEntries = new ArrayList<>();
        for (AgentContextEntry entry : entries) {
            if (!shouldCache(entry)) {
                continue;
            }
            String logicalResourceId = logicalResourceId(entry.toolName(), entry.item());
            hotCacheEntries.add(new AgentContextHotCacheEntry(
                    entry.id(),
                    entry.toolName(),
                    logicalResourceId,
                    entry.item(),
                    entry.artifactRef(),
                    isRestorableToolResult(entry.toolName(), entry.item()),
                    entry.retriable(),
                    fullTokenEstimate(entry),
                    entry.createdAt(),
                    entry.lastAccessedAt(),
                    entry.priority()
            ));
        }
        return hotCacheEntries;
    }

    private AgentContextItem restoreHotCacheItem(AgentContextHotCacheEntry entry) {
        if (entry == null || entry.item() == null) {
            return new AgentContextItem("tool_restore", "unknown", "", Map.of("restored", true));
        }
        if (entry.artifactRef() == null) {
            return entry.item();
        }
        String fullContent = artifactStore.load(entry.artifactRef());
        if (!StringUtils.hasText(fullContent)) {
            return entry.item();
        }
        Map<String, Object> metadata = mergeMetadata(entry.item().metadata(), Map.of(
                "restoredFromArtifact", true,
                "artifactPath", entry.artifactRef().relativePath(),
                "artifactId", entry.artifactRef().artifactId()
        ));
        return new AgentContextItem(entry.item().sourceType(), entry.item().sourceId(), fullContent, metadata);
    }

    private int fullTokenEstimate(AgentContextEntry entry) {
        if (entry == null) {
            return 0;
        }
        if (entry.item() != null && entry.item().metadata() != null) {
            Object fullTokens = entry.item().metadata().get("fullTokens");
            if (fullTokens instanceof Number number) {
                return number.intValue();
            }
        }
        return entry.tokenEstimate();
    }

    private AgentContextSession touch(AgentContextSession session, List<AgentContextEntry> projectedEntries) {
        Set<String> ids = projectedEntries.stream().map(AgentContextEntry::id).collect(Collectors.toSet());
        Set<String> logicalResourceIds = collectLogicalResourceIds(projectedEntries);
        long now = System.currentTimeMillis();
        List<AgentContextEntry> nextEntries = session.entries().stream()
                .map(entry -> ids.contains(entry.id()) ? entry.touch(now) : entry)
                .toList();
        List<AgentContextHotCacheEntry> nextCache = (session.hotCacheEntries() == null ? List.<AgentContextHotCacheEntry>of() : session.hotCacheEntries()).stream()
                .map(entry -> ids.contains(entry.sourceEntryId()) || logicalResourceIds.contains(entry.logicalResourceId()) ? entry.touch(now) : entry)
                .toList();
        return session.withEntries(nextEntries).withHotCacheEntries(nextCache);
    }

    private AgentContextEntryKind inferKind(AgentContextItem item) {
        if (item == null) {
            return AgentContextEntryKind.SYSTEM;
        }
        if ("todo_reminder".equals(item.sourceType()) || "todo_write".equals(item.sourceType())) {
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
        if ("tool_error".equals(item.sourceType()) || "todo_reminder".equals(item.sourceType()) || "verifier".equals(item.sourceType()) || "todo_write".equals(item.sourceType())) {
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
        if ("todo_reminder".equals(item.sourceType()) || "todo_write".equals(item.sourceType())) {
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
        if ("summary".equals(item.sourceType())) {
            return 95;
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
        if ("todo_write".equals(item.sourceType())) {
            return 90;
        }
        return 72;
    }

    private boolean isCompactableTool(String toolName) {
        return StringUtils.hasText(toolName) && COMPACTABLE_TOOLS.contains(toolName);
    }

    private boolean shouldCache(AgentContextEntry entry) {
        if (entry == null || entry.item() == null) {
            return false;
        }
        if ("tool_error".equals(entry.item().sourceType()) || "verifier".equals(entry.item().sourceType())) {
            return false;
        }
        return entry.kind() == AgentContextEntryKind.TOOL_RESULT || "todo_write".equals(entry.item().sourceType());
    }

    private boolean isRestorableToolResult(String toolName, AgentContextItem item) {
        if ("todo_write".equals(toolName) || "todo_write".equals(item.sourceType())) {
            return true;
        }
        if (!StringUtils.hasText(toolName)) {
            return false;
        }
        return isCompactableTool(toolName) || StringUtils.hasText(logicalResourceId(toolName, item));
    }

    private boolean isAlwaysProjected(AgentContextEntry entry) {
        return switch (entry.kind()) {
            case TODO, VERIFIER, SUMMARY, SYSTEM, ARTIFACT_MARKER -> true;
            case CONVERSATION -> "tool_error".equals(entry.item().sourceType()) || "todo_write".equals(entry.item().sourceType());
            default -> false;
        };
    }

    private boolean isPlanOrTodoEntry(AgentContextEntry entry) {
        return entry != null && entry.item() != null
                && ("todo_write".equals(entry.item().sourceType()) || entry.kind() == AgentContextEntryKind.TODO);
    }

    private int compareProjectionPriority(AgentContextEntry left, AgentContextEntry right) {
        int priorityCompare = Integer.compare(right.priority(), left.priority());
        if (priorityCompare != 0) {
            return priorityCompare;
        }
        int accessCompare = Long.compare(right.lastAccessedAt(), left.lastAccessedAt());
        if (accessCompare != 0) {
            return accessCompare;
        }
        if (left.retriable() != right.retriable()) {
            return Boolean.compare(left.retriable(), right.retriable());
        }
        return Long.compare(right.createdAt(), left.createdAt());
    }

    private Set<String> recentConversationIds(List<AgentContextEntry> entries, int budget) {
        List<AgentContextEntry> conversationEntries = entries.stream()
                .filter(entry -> entry.kind() == AgentContextEntryKind.CONVERSATION)
                .toList();
        return conversationEntries.stream()
                .skip(Math.max(0, conversationEntries.size() - budget))
                .map(AgentContextEntry::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> collectLogicalResourceIds(Collection<AgentContextEntry> entries) {
        Set<String> ids = new LinkedHashSet<>();
        if (entries == null) {
            return ids;
        }
        for (AgentContextEntry entry : entries) {
            if (entry == null || entry.item() == null || entry.item().metadata() == null) {
                continue;
            }
            Object logicalResourceId = entry.item().metadata().get("logicalResourceId");
            if (logicalResourceId instanceof String text && !text.isBlank()) {
                ids.add(text);
            }
        }
        return ids;
    }

    private String logicalResourceId(String toolName, AgentContextItem item) {
        if (item != null && item.metadata() != null) {
            for (String key : List.of("logicalResourceId", "sourceFile", "path", "kgNodeId", "capability")) {
                Object value = item.metadata().get(key);
                if (value instanceof String text && !text.isBlank()) {
                    return toolName == null ? text.trim() : toolName + ":" + text.trim();
                }
            }
        }
        if (item != null && StringUtils.hasText(item.sourceId())) {
            return (toolName == null ? "context" : toolName) + ":" + item.sourceId().trim();
        }
        return (toolName == null ? "context" : toolName) + ":" + (item == null ? "unknown" : item.sourceType());
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

    private String trimToTokenBudget(String raw, int tokenBudget) {
        if (!StringUtils.hasText(raw) || tokenBudget <= 0) {
            return "";
        }
        int charBudget = Math.max(0, tokenBudget * 4);
        String normalized = raw.trim();
        if (normalized.length() <= charBudget) {
            return normalized;
        }
        return normalized.substring(0, charBudget) + "\n...(技能内容已按恢复预算截断)";
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
        return safeId(sourceId) + "_" + UUID.randomUUID();
    }

    private String safeId(String value) {
        return StringUtils.hasText(value) ? value.replaceAll("[^A-Za-z0-9_.-]", "_") : "context";
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (extra != null) {
            merged.putAll(extra);
        }
        return merged;
    }

    private void appendStage(List<AgentContextCompressionStage> stages, Map<String, Object> metrics, CompactionStepResult result) {
        if (result == null || !result.applied()) {
            return;
        }
        stages.addAll(result.stages());
        metrics.putAll(result.metrics());
    }

    private String cacheKey(AgentContextHotCacheEntry entry) {
        return entry.toolName() + "|" + entry.logicalResourceId();
    }

    private enum ProjectionPurpose {
        DECISION,
        FINAL,
        VERIFY
    }

    private record WriteTimeCompactionResult(
            AgentContextSession session,
            List<AgentContextCompressionStage> stages,
            Map<String, Object> metrics
    ) {
    }

    private record CompactionStepResult(
            AgentContextSession session,
            boolean applied,
            List<AgentContextCompressionStage> stages,
            Map<String, Object> metrics
    ) {
        static CompactionStepResult noop(AgentContextSession session) {
            return new CompactionStepResult(session, false, List.of(), Map.of());
        }
    }

    private record RestoreBundle(List<AgentContextEntry> entries, int remainingTokenBudget) {
    }

    private record ProjectionResult(
            List<AgentContextEntry> projectedEntries,
            int estimatedTokens,
            boolean collapsed,
            List<AgentContextCompressionStage> stages,
            Map<String, Object> metrics
    ) {
    }
}
