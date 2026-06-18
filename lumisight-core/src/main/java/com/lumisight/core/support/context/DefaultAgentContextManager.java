package com.lumisight.core.support.context;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.PromptTemplateService;
import com.lumisight.core.support.StreamingChatClientSupport;
import com.lumisight.skills.dto.SkillPlan;
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
    private final PromptTemplateService promptTemplateService;
    private final AgentTokenEstimator tokenEstimator;

    public DefaultAgentContextManager(
            AgentContextManagementProperties properties,
            AgentContextArtifactStore artifactStore,
            ChatClient.Builder chatClientBuilder,
            StreamingChatClientSupport streamingChatClientSupport,
            PromptTemplateService promptTemplateService,
            AgentTokenEstimator tokenEstimator
    ) {
        this.properties = properties;
        this.artifactStore = artifactStore;
        this.chatClient = chatClientBuilder.build();
        this.streamingChatClientSupport = streamingChatClientSupport;
        this.promptTemplateService = promptTemplateService;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public AgentContextSession restore(String sessionId, AgentConversationManager.ConversationState state) {
        if (state == null) {
            return AgentContextSession.empty();
        }
        if (state.contextSession() != null) {
            return state.contextSession();
        }
        // 新老逻辑兼容，以前上下文是用AgentContextItem管理的
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
        // 工具的返回结果太大，需要进行压缩
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
        // snip/micro 是写入时压缩，已在 append/appendToolResult 中完成；投影阶段只负责按用途生成本轮视图。
        AgentContextSession session = input;
        List<AgentContextCompressionStage> stages = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();

        int estimatedTokens = estimateTokens(session.entries());
        boolean autoCompacted = false;
        int autoCompactThreshold = properties.getEffectiveContextWindow()
                - properties.getResponseReserveTokens()
                - properties.getAutoCompactBufferTokens();
        // autoCompact 是极限窗口下的模型摘要压缩；如果token数大于autoCompactThreshold，就先用模型对上下文进行压缩
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
            // 在压缩前的上下文中恢复出热点上下文
            RestoreBundle hotRestore = restoreHotEntries(session, recentTail, now + 2);
            rebuilt.addAll(hotRestore.entries());
            // 在压缩前的上下文中恢复出skill上下文
            RestoreBundle skillRestore = restoreSkillEntries(sessionId, skillPlan, hotRestore.remainingTokenBudget(), now + 100);
            rebuilt.addAll(skillRestore.entries());
            // 在压缩前的上下文中恢复出计划上下文
            RestoreBundle planRestore = restorePlanEntries(session, compactedSegment, recentTail, skillRestore.remainingTokenBudget(), now + 200);
            rebuilt.addAll(planRestore.entries());
            // 把近期的上下文加入到压缩后的上下文中
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
        // 把上下文按照重要顺序排序
        List<AgentContextHotCacheEntry> restorable = hotCacheEntries.stream()
                .filter(AgentContextHotCacheEntry::restorable)
                .sorted(Comparator.comparingLong(AgentContextHotCacheEntry::lastAccessedAt).reversed())
                .toList();
        List<AgentContextEntry> restored = new ArrayList<>();
        // 压缩后所能容忍的hot上下文token总额
        int tokenBudget = properties.getPostCompactTokenBudget();
        Set<String> seen = new LinkedHashSet<>();
        for (AgentContextHotCacheEntry entry : restorable) {
            // 恢复的上下文超过上限就不再继续恢复
            if (restored.size() >= properties.getPostCompactMaxRestoreEntries()) {
                break;
            }
            // 如果当前entry的token大于压缩后能容忍的最大token数，则跳过
            if (entry.tokenEstimate() > properties.getPostCompactMaxTokensPerEntry()) {
                continue;
            }
            String logicalResourceId = entry.logicalResourceId();
            // 如果当前资源logicalResourceId已经存在，则跳过
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
        // skill上下文的token容量
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
            // 这里会对skill原文做截断
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
        // 获取上次的计划
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
        // 只返回最新的计划
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
        // 模型生成摘要
        String summary = streamingChatClientSupport.collect(chatClient, systemPrompt, userPrompt.toString());
        if (StringUtils.hasText(summary)) {
            return summary;
        }
        return heuristicSummary(entries, request);
    }

    /**
     * 兜底的启发式摘要生成器。
     * @param entries
     * @param request
     * @return
     */
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

    /**
     * 读时投影裁剪：不改写 session 账本，只决定本轮 prompt 能看到哪些 entry。
     * 这里和 autoCompact 不同，autoCompact 会重建账本摘要；这里只是生成一个较小的上下文视图。
     */
    private ProjectionResult collapseForProjection(AgentContextSession session, ProjectionPurpose purpose) {
        List<AgentContextEntry> entries = new ArrayList<>(session.entries());
        int estimatedTokens = estimateTokens(entries);
        // 如果token没超限，就不剪裁直接返回
        if (estimatedTokens <= properties.getProjectionSoftTokens()) {
            return new ProjectionResult(entries, estimatedTokens, false, List.of(), Map.of());
        }
        // 第一层保护：必须投影的系统/摘要/计划类 entry + 最近若干条对话，先锁进 requiredIds。
        // DECISION 阶段更偏向给工具决策留空间；FINAL/VERIFY 阶段多保留一点最近对话，方便回答和复核。
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
        // 先把 required 全部放入 selected；它们可能已经超过预算，但仍优先保证语义连续性。
        List<AgentContextEntry> selected = new ArrayList<>();
        int usedTokens = 0;
        for (AgentContextEntry entry : entries) {
            if (requiredIds.contains(entry.id())) {
                selected.add(entry);
                usedTokens += entry.tokenEstimate();
            }
        }
        // 第二层回填：剩余 entry 按优先级、最近访问时间、是否可重取排序，尽量把有价值的内容塞回投影视图。
        List<AgentContextEntry> optional = entries.stream()
                .filter(entry -> !requiredIds.contains(entry.id()))
                .sorted(this::compareProjectionPriority)
                .toList();
        // priority < 88 的普通内容在预算已满后直接跳过；priority >= 92 的高价值内容允许轻微挤过 targetBudget。
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
        // 统计被隐藏的 entry 类型，写进 marker，方便模型和调试日志知道“少看了什么”。就是selected以外的上下文
        Map<String, Long> hiddenByKind = entries.stream()
                .filter(entry -> finalSelected.stream().noneMatch(kept -> kept.id().equals(entry.id())))
                .collect(Collectors.groupingBy(entry -> entry.kind().name(), LinkedHashMap::new, Collectors.counting()));

        List<AgentContextEntry> projected = new ArrayList<>();
        if (collapsedCount > 0) {
            // 插入一个轻量 marker 代替被隐藏的上下文，避免模型误以为历史本来就不存在。
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
        // 投影结果仍按原始时间线排列，只是中间少了一部分可隐藏 entry。
        projected.addAll(selected);

        int projectedTokens = estimateTokens(projected);
        // required + 高优先级回填可能会超过 hard limit，这时再走最后一道硬裁剪兜底。
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
        // hard limit 兜底裁剪：collapseForProjection 已经尽量按 targetBudget 选过一轮，
        // 但 required entry 或高优先级 entry 可能把 projectedTokens 顶过 hardTokens，这里再做最后收口。
        List<AgentContextEntry> required = entries.stream()
                .filter(entry -> isAlwaysProjected(entry) || recentConversationIds.contains(entry.id()))
                .sorted(Comparator.comparingLong(AgentContextEntry::createdAt))
                .toList();
        int used = estimateTokens(required);
        List<AgentContextEntry> kept = new ArrayList<>(required);
        // required 是最后防线：如果它们本身已经达到或超过 hard limit，就不再回填 optional。
        if (used >= properties.getProjectionHardTokens()) {
            return kept;
        }
        // optional 仍按投影优先级排序；能塞进 hard limit 的普通内容才回填。
        List<AgentContextEntry> optional = entries.stream()
                .filter(entry -> required.stream().noneMatch(requiredEntry -> requiredEntry.id().equals(entry.id())))
                .sorted(this::compareProjectionPriority)
                .toList();
        for (AgentContextEntry entry : optional) {
            // priority >= 90 的内容允许越过 hard limit 一点点；低优先级内容超过上限就跳过。
            if (used + entry.tokenEstimate() > properties.getProjectionHardTokens() && entry.priority() < 90) {
                continue;
            }
            kept.add(entry);
            used += entry.tokenEstimate();
            // 达到 hard limit 后停止继续回填，避免后续 optional 把 prompt 撑得更大。
            if (used >= properties.getProjectionHardTokens()) {
                break;
            }
        }
        kept.sort(Comparator.comparingLong(AgentContextEntry::createdAt));
        return kept;
    }

    private WriteTimeCompactionResult applyWriteTimeCompaction(String sessionId, AgentContextSession session) {
        // 通过snip方式对上下文进行一轮压缩，snip就是对旧的上下文进行删除
        CompactionStepResult snipResult = applySnip(session);
        AgentContextSession compacted = snipResult.session();
        // micro会对单条上下文进行瘦身
        CompactionStepResult microResult = applyMicroCompact(compacted);
        compacted = microResult.session();

        List<AgentContextCompressionStage> stages = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        appendStage(stages, metrics, snipResult);
        appendStage(stages, metrics, microResult);
        return new WriteTimeCompactionResult(compacted, List.copyOf(stages), Map.copyOf(metrics));
    }

    /**
     * snip 是写入时的硬裁剪：当整段上下文超过 snipTriggerTokens 时，
     * 只从较旧、低优先级、非保护的 conversation 中删除内容，直到接近 snipTargetTokens。
     * 它不会把 conversation 裁到只剩 keepRecent 条，而是保证最近 keepRecent 条可删除 conversation 不参与删除候选。
     */
    private CompactionStepResult applySnip(AgentContextSession session) {
        // token 估算统一走 AgentTokenEstimator；当前实现使用 Spring AI JTokkit，并带安全系数兜底。
        int estimatedTokens = estimateTokens(session.entries());
        // token未超过阈值，不处理
        if (estimatedTokens <= properties.getSnipTriggerTokens()) {
            return CompactionStepResult.noop(session);
        }
        List<AgentContextEntry> entries = new ArrayList<>(session.entries());
        List<Integer> removableIndexes = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            AgentContextEntry entry = entries.get(i);
            if (entry.kind() == AgentContextEntryKind.CONVERSATION
                    && entry.priority() <= 60
                    // isAlwaysProjected代表必须保留的上下文，不应该被删除
                    && !isAlwaysProjected(entry)
                    // sourceType=context_marker的消息是上下文管理器生成的，不应该被删除
                    && !"context_marker".equals(entry.item().sourceType())) {
                removableIndexes.add(i);
            }
        }
        // keepRecent 代表在可删除的 conversation 里，至少保留最近多少条。
        int keepRecent = properties.getSnipKeepRecentConversation();
        if (removableIndexes.size() <= keepRecent) {
            return CompactionStepResult.noop(session);
        }
        // removableIndexes 按时间从旧到新排列，只把更早的部分纳入删除候选。
        List<Integer> toRemove = removableIndexes.subList(0, removableIndexes.size() - keepRecent);
        // next存的是留下来的上下文
        List<AgentContextEntry> next = new ArrayList<>();
        int freed = 0;
        int removedCount = 0;
        Set<Integer> removeSet = new LinkedHashSet<>(toRemove);
        for (int i = 0; i < entries.size(); i++) {
            // i是待删除上下文，并且剩余的上下文的token总和仍然超过了阈值
            if (removeSet.contains(i) && estimatedTokens - freed > properties.getSnipTargetTokens()) {
                freed += entries.get(i).tokenEstimate();
                removedCount++;
                continue;
            }
            next.add(entries.get(i));
        }
        // freed <= 0等价于裁剪失败
        if (freed <= 0) {
            return CompactionStepResult.noop(session);
        }
        // 记录进行了上下文压缩
        next.addFirst(AgentContextEntry.marker(
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

    /**
     * micro compact 是写入时的轻量瘦身：当上下文过久未投影或超过 projectionSoftTokens 时，
     * 找出较旧的 compactable entry，并把它们的正文替换成可恢复的轻量 marker。
     * 它和 snip 的区别是：snip 会删除旧 conversation entry，micro compact 保留 entry 但清空大块内容。
     * keepRecent 只作用于 compactable 候选集合，表示最近 keepRecent 条可压缩 entry 保持原文。
     */
    private CompactionStepResult applyMicroCompact(AgentContextSession session) {
        long now = System.currentTimeMillis();
        long staleMillis = Duration.ofMinutes(properties.getMicroCompactStaleMinutes()).toMillis();
        // 判断上次投影的时间是否超过可容忍阈值
        boolean stale = session.lastProjectionAt() > 0 && now - session.lastProjectionAt() >= staleMillis;
        boolean oversized = estimateTokens(session.entries()) > properties.getProjectionSoftTokens();
        // 投影时间没超，上下文大小也没超
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
        // compactableIndexes代表可被压缩的上下文
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
            // 如果entry不可压缩，则原样保留
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
        // 根据字节数排序
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
            // 执行压缩逻辑
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
        // 对工具结果进行持久化
        AgentContextArtifactRef artifactRef = artifactStore.persist(
                sessionId,
                entry.id(),
                entry.item().content(),
                entry.item().metadata() == null ? Map.of() : entry.item().metadata()
        );
        // 普通大内容保留 preview；超长内容再额外生成模型摘要，避免只截前半段漏掉关键结论。
        String preview = preview(entry.item().content());
        String artifactSummary = summarizeArtifactIfNeeded(entry, artifactRef, fullTokens);
        String contextView = artifactContextView(preview, artifactSummary, artifactRef);
        Map<String, Object> metadata = mergeMetadata(entry.item().metadata(), Map.of(
                "artifactPath", artifactRef.relativePath(),
                "artifactId", artifactRef.artifactId(),
                "artifactized", true,
                "fullBytes", artifactRef.fullBytes(),
                "fullTokens", fullTokens,
                "artifactSummaryGenerated", StringUtils.hasText(artifactSummary)
        ));
        AgentContextItem previewItem = new AgentContextItem(entry.item().sourceType(), entry.item().sourceId(), contextView, metadata);
        return entry.withArtifact(previewItem, artifactRef, bytes(contextView), estimateTokens(contextView));
    }

    private String summarizeArtifactIfNeeded(AgentContextEntry entry, AgentContextArtifactRef artifactRef, int fullTokens) {
        if (artifactRef.fullBytes() < properties.getArtifactSummaryTriggerBytes()) {
            return "";
        }
        String content = entry.item().content();
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String sample = limitBytes(content, properties.getArtifactSummaryInputBytes());
        String systemPrompt = promptTemplateService.render("artifact_summary_system", Map.of());
        String userPrompt = promptTemplateService.render("artifact_summary_user", Map.of(
                "toolName", entry.toolName() == null ? "" : entry.toolName(),
                "sourceId", entry.item().sourceId(),
                "artifactPath", artifactRef.relativePath(),
                "fullBytes", artifactRef.fullBytes(),
                "fullTokens", fullTokens,
                "sample", sample
        ));
        try {
            String summary = streamingChatClientSupport.collect(chatClient, systemPrompt, userPrompt);
            return StringUtils.hasText(summary) ? summary.trim() : "";
        } catch (Exception e) {
            log.warn("artifact summary failed, entryId={}, artifactId={}, error={}", entry.id(), artifactRef.artifactId(), e.getMessage());
            return "";
        }
    }

    private String artifactContextView(String preview, String artifactSummary, AgentContextArtifactRef artifactRef) {
        StringBuilder builder = new StringBuilder();
        if (StringUtils.hasText(artifactSummary)) {
            builder.append("[Artifact summary]\n").append(artifactSummary.trim());
            builder.append("\n\n[Full content stored as artifact: ").append(artifactRef.relativePath()).append("]");
            return builder.toString();
        }
        builder.append("[Artifact preview]\n").append(preview == null ? "" : preview.trim());
        builder.append("\n\n[Full content stored as artifact: ").append(artifactRef.relativePath()).append("]");
        return builder.toString();
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
        // 先存旧的hotCache
        for (AgentContextHotCacheEntry entry : existing) {
            merged.put(cacheKey(entry), entry);
        }
        // 再存新的hotCache，如果key一样会覆盖旧的
        for (AgentContextHotCacheEntry entry : newEntries) {
            merged.put(cacheKey(entry), entry);
        }
        // hotCache最多64条
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
        // 如果没有引用（也就是没持久化到磁盘），就直接返回
        if (entry.artifactRef() == null) {
            return entry.item();
        }
        // 从磁盘恢复上下文
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
        // touch 不是投影选择逻辑；它只在投影完成后，把“本轮真的给模型看过”的内容标记为刚访问过。
        // 后续 hot cache 恢复和投影优先级排序会用 lastAccessedAt 判断哪些上下文仍然活跃。
        Set<String> ids = projectedEntries.stream().map(AgentContextEntry::id).collect(Collectors.toSet());
        // 同一个文件/方法等资源可能对应多条 entry；用 logicalResourceId 同步刷新 hot cache 里的同资源缓存。
        Set<String> logicalResourceIds = collectLogicalResourceIds(projectedEntries);
        long now = System.currentTimeMillis();
        // 给刚刚投影过的条目加上最后访问时间
        List<AgentContextEntry> nextEntries = session.entries().stream()
                .map(entry -> ids.contains(entry.id()) ? entry.touch(now) : entry)
                .toList();
        // 给刚刚投影过的热缓存条目加上最后访问时间
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
        // 只留下Conversion类型的上下文
        List<AgentContextEntry> conversationEntries = entries.stream()
                .filter(entry -> entry.kind() == AgentContextEntryKind.CONVERSATION)
                .toList();
        // 跳过旧记录，只保留新纪录
        return conversationEntries.stream()
                .skip(Math.max(0, conversationEntries.size() - budget))
                .map(AgentContextEntry::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 收集一批上下文已经覆盖过的“逻辑资源”。
     * entry.id() 表示这条上下文记录本身；logicalResourceId 表示它背后的同一个资源。
     *
     * 例子：用户连续两次读取同一个文件，可能产生两条不同 entry：
     * - entry.id = "tool_result_round_3_cat_abc"，logicalResourceId = "file:/repo/src/App.java"
     * - entry.id = "tool_result_round_8_cat_xyz"，logicalResourceId = "file:/repo/src/App.java"
     *
     * 对恢复逻辑来说，这两条都指向同一个文件资源。只要 recentTail 已经保留了其中一条，
     * hot cache 恢复时就不应该再恢复另一条旧结果，否则 prompt 里会重复出现同一份文件内容。
     */
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
        return tokenEstimator.estimate(content);
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

    private String limitBytes(String content, int maxBytes) {
        if (!StringUtils.hasText(content) || maxBytes <= 0) {
            return "";
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return content;
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8) + "\n...(summary input truncated; full content stored as artifact)";
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
        // applied=false代表本轮没有执行压缩
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
