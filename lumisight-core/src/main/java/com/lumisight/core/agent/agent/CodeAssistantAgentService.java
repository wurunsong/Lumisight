package com.lumisight.core.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.common.util.ValueParsers;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.AgentRequest;
import com.lumisight.core.agent.model.AgentResponse;
import com.lumisight.core.agent.model.AgentTaskType;
import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.port.KnowledgeGraphOneHopProvider;
import com.lumisight.core.agent.port.SourceCodeLookupProvider;
import com.lumisight.core.agent.tool.AgentToolCategory;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.AgentToolRegistry;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class CodeAssistantAgentService {

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int DEFAULT_CONTEXT_LIMIT = 5;
    private static final Set<AgentToolPermission> DEFAULT_RAG_TOOL_PERMISSIONS = EnumSet.of(
            AgentToolPermission.HYBRID_VECTOR_READ
    );

    private final ChatClient llmChatClient;
    private final AgentToolRegistry agentToolRegistry;
    private final KnowledgeGraphOneHopProvider knowledgeGraphOneHopProvider;
    private final SourceCodeLookupProvider sourceCodeLookupProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public CodeAssistantAgentService(
            ChatClient.Builder chatClientBuilder,
            AgentToolRegistry agentToolRegistry,
            KnowledgeGraphOneHopProvider knowledgeGraphOneHopProvider,
            SourceCodeLookupProvider sourceCodeLookupProvider
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.agentToolRegistry = agentToolRegistry;
        this.knowledgeGraphOneHopProvider = knowledgeGraphOneHopProvider;
        this.sourceCodeLookupProvider = sourceCodeLookupProvider;
    }

    public AgentResponse run(AgentRequest request) {
        validateRequest(request);

        int limit = request.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : request.contextLimit();
        List<AgentContextItem> contexts = new ArrayList<>();

        String answer;
        try (AgentToolRuntimeContext.Scope ignored = AgentToolRuntimeContext.open(request.repoRoot(), limit)) {
            answer = runManualOrchestration(request, contexts, limit);
        }

        return new AgentResponse(answer, contexts);
    }

    private void validateRequest(AgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (!StringUtils.hasText(request.repoRoot())) {
            throw new IllegalArgumentException("repoRoot must not be blank");
        }
        if (!StringUtils.hasText(request.question())) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (request.taskType() == null) {
            throw new IllegalArgumentException("taskType must not be null");
        }
    }

    private String systemPrompt(AgentTaskType taskType) {
        if (taskType == AgentTaskType.BUG_FIX) {
            return "你是一名资深 Java 工程师。请聚焦根因分析、低风险修复方案和可验证的补丁建议。"
                    + "输出要结构清晰，先给结论，再给依据。";
        }
        return "你是一名资深 Java 工程师。请清晰解释代码意图、架构关系、控制流程和关键取舍。"
                + "解释要贴近工程实践，并尽量给出可落地建议。";
    }

    private String runManualOrchestration(AgentRequest request, List<AgentContextItem> contexts, int limit) {
        Set<AgentToolPermission> enabledPermissions = enabledPermissions(request);
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            String decisionRaw = llmChatClient.prompt()
                    .system(orchestratorSystemPrompt(request.taskType(), enabledPermissions))
                    .user(orchestratorUserPrompt(request, contexts, limit, round))
                    .call()
                    .content();
            ToolDecision decision = parseDecision(decisionRaw);
            if ("final".equalsIgnoreCase(decision.action())) {
                if (StringUtils.hasText(decision.finalAnswer())) {
                    return decision.finalAnswer();
                }
                break;
            }
            if (!"tool".equalsIgnoreCase(decision.action())) {
                break;
            }
            List<AgentContextItem> toolResult = executeTool(decision, enabledPermissions, limit);
            if (toolResult.isEmpty()) {
                break;
            }
            contexts.addAll(toolResult);
        }

        return llmChatClient.prompt()
                .system(systemPrompt(request.taskType()))
                .user(buildFinalAnswerPrompt(request, contexts, limit))
                .call()
                .content();
    }

    private Set<AgentToolPermission> enabledPermissions(AgentRequest request) {
        Set<AgentToolPermission> enabledPermissions = EnumSet.noneOf(AgentToolPermission.class);
        if (request.includeRagContext()) {
            enabledPermissions.addAll(DEFAULT_RAG_TOOL_PERMISSIONS);
        }
        return enabledPermissions;
    }

    private String buildFinalAnswerPrompt(
            AgentRequest request,
            List<AgentContextItem> contexts,
            int limit
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("TaskType: ").append(request.taskType()).append("\n");
        builder.append("RepoRoot: ").append(request.repoRoot()).append("\n");
        builder.append("ContextLimit: ").append(limit).append("\n");
        builder.append("用户问题: ").append(request.question()).append("\n\n");
        builder.append("已检索上下文:\n");
        if (contexts.isEmpty()) {
            builder.append("- 无\n");
        } else {
            for (AgentContextItem context : contexts) {
                builder.append("- [")
                        .append(context.sourceType())
                        .append("] ")
                        .append(context.sourceId())
                        .append("\n")
                        .append(context.content())
                        .append("\n");
            }
        }
        builder.append("\n请使用中文回答，并给出可执行的下一步建议。");
        return builder.toString();
    }

    private String orchestratorSystemPrompt(AgentTaskType taskType, Set<AgentToolPermission> enabledPermissions) {
        StringBuilder builder = new StringBuilder();
        builder.append(systemPrompt(taskType)).append("\n");
        builder.append("你在执行手动工具编排。每轮只能输出一个JSON对象，不要输出其他文本。\n");
        builder.append("JSON结构:\n");
        builder.append("{\"action\":\"tool|final\",\"toolName\":\"...\",\"args\":{},\"finalAnswer\":\"...\",\"reason\":\"...\"}\n");
        builder.append("规则:\n");
        builder.append("- 若上下文不足，action=tool，并选择一个已启用工具。\n");
        builder.append("- 若信息足够，action=final，并在finalAnswer给出最终回答。\n");
        builder.append("- 禁止输出Markdown。\n");
        builder.append("已启用工具:\n").append(enabledToolHints(enabledPermissions));
        return builder.toString();
    }

    private String orchestratorUserPrompt(AgentRequest request, List<AgentContextItem> contexts, int limit, int round) {
        StringBuilder builder = new StringBuilder();
        builder.append("轮次: ").append(round).append("/").append(MAX_TOOL_ROUNDS).append("\n");
        builder.append("用户问题: ").append(request.question()).append("\n");
        builder.append("上下文上限: ").append(limit).append("\n\n");
        builder.append("当前上下文:\n");
        if (contexts.isEmpty()) {
            builder.append("- 无\n");
        } else {
            for (AgentContextItem context : contexts) {
                builder.append("- [").append(context.sourceType()).append("] ")
                        .append(context.sourceId()).append("\n");
            }
        }
        builder.append("\n请输出本轮JSON决策。");
        return builder.toString();
    }

    private String enabledToolHints(Set<AgentToolPermission> enabledPermissions) {
        StringBuilder builder = new StringBuilder();
        List<AgentToolCategory> categories = List.of(AgentToolCategory.RAG, AgentToolCategory.GRAPH, AgentToolCategory.SOURCE);
        for (AgentToolCategory category : categories) {
            for (PermissionedAgentTool tool : agentToolRegistry.getByCategory(category)) {
                if (enabledPermissions.contains(tool.permission())) {
                    builder.append("- ").append(tool.toolName()).append("(...): ").append(category).append(" 类型工具\n");
                }
            }
        }
        return builder.toString();
    }

    private ToolDecision parseDecision(String raw) {
        String json = extractJsonObject(raw);
        try {
            return objectMapper.readValue(json, ToolDecision.class);
        } catch (Exception e) {
            return new ToolDecision("final", null, Map.of(), "模型决策解析失败，直接给出最终回答。", e.getMessage());
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private List<AgentContextItem> executeTool(ToolDecision decision, Set<AgentToolPermission> enabledPermissions, int limit) {
        String toolName = decision.toolName() == null ? "" : decision.toolName().trim();
        Map<String, Object> args = decision.args() == null ? Map.of() : decision.args();
        PermissionedAgentTool tool = agentToolRegistry.get(toolName);
        if (tool == null) {
            return List.of(new AgentContextItem(
                    "tool_error",
                    "unknown_tool",
                    "未知工具: " + toolName,
                    Map.of("toolName", toolName)
            ));
        }
        if (!enabledPermissions.contains(tool.permission())) {
            return denied(toolName);
        }
        List<AgentContextItem> results = tool.invoke(args, limit);
        if ("searchHybridVector".equals(toolName)) {
            results = enrichGraphAndSource(results, limit, decision, toolName);
        }
        return results;
    }

    private List<AgentContextItem> enrichGraphAndSource(
            List<AgentContextItem> vectorResults,
            int limit,
            ToolDecision decision,
            String toolName
    ) {
        List<AgentContextItem> enriched = new ArrayList<>(vectorResults);
        for (AgentContextItem item : vectorResults) {
            Object kgNodeIdValue = item.metadata() == null ? null : item.metadata().get("kg_node_id");
            if (!(kgNodeIdValue instanceof String kgNodeId) || kgNodeId.isBlank()) {
                continue;
            }
            List<AgentContextItem> oneHop = knowledgeGraphOneHopProvider.retrieveByNodeId(
                    AgentToolRuntimeContext.required().repoRoot(),
                    kgNodeId,
                    limit
            );
            enriched.addAll(oneHop);
            List<Map<String, Object>> methodNodes = knowledgeGraphOneHopProvider.retrieveMethodNodeLocationsByNodeId(
                    AgentToolRuntimeContext.required().repoRoot(),
                    kgNodeId,
                    limit
            );
            for (Map<String, Object> methodNode : methodNodes) {
                String sourceFile = ValueParsers.asString(methodNode.get("sourceFile"));
                Integer startLine = ValueParsers.asInteger(methodNode.get("startLine"));
                Integer endLine = ValueParsers.asInteger(methodNode.get("endLine"));
                enriched.addAll(sourceCodeLookupProvider.lookupMethodSource(
                        AgentToolRuntimeContext.required().repoRoot(),
                        sourceFile,
                        startLine,
                        endLine
                ));
            }
        }
        String userQuestion = ValueParsers.asString(decision.args() == null ? null : decision.args().get("naturalLanguageQuery"));
        if (!StringUtils.hasText(userQuestion)) {
            userQuestion = ValueParsers.asString(decision.args() == null ? null : decision.args().get("codeQuery"));
        }
        return filterRelevantContexts(userQuestion, enriched, limit, toolName);
    }

    private List<AgentContextItem> denied(String toolName) {
        return List.of(new AgentContextItem(
                "tool_error",
                "permission_denied",
                "工具未启用: " + toolName,
                Map.of("toolName", toolName)
        ));
    }

    private List<AgentContextItem> filterRelevantContexts(
            String userQuestion,
            List<AgentContextItem> contexts,
            int limit,
            String toolName
    ) {
        if (!StringUtils.hasText(userQuestion) || contexts.isEmpty()) {
            return contexts;
        }
        String prompt = buildRelevanceFilterPrompt(userQuestion, contexts, limit, toolName);
        String raw = llmChatClient.prompt()
                .system("你是检索重排序器。只输出JSON，不输出其他文本。")
                .user(prompt)
                .call()
                .content();
        try {
            List<Map<String, Object>> scoredItems = objectMapper.readValue(
                    extractJsonArray(raw),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );
            if (scoredItems == null || scoredItems.isEmpty()) {
                return contexts;
            }
            scoredItems.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));
            Set<Integer> selected = new LinkedHashSet<>();
            int maxKeep = Math.max(1, limit * 2);
            for (Map<String, Object> item : scoredItems) {
                Integer idx = ValueParsers.asInteger(item.get("index"));
                if (idx != null) {
                    selected.add(idx);
                }
                if (selected.size() >= maxKeep) {
                    break;
                }
            }
            List<AgentContextItem> filtered = new ArrayList<>();
            for (Integer index : selected) {
                if (index == null || index < 0 || index >= contexts.size()) {
                    continue;
                }
                filtered.add(contexts.get(index));
            }
            return filtered.isEmpty() ? contexts : filtered;
        } catch (Exception ignored) {
            return contexts;
        }
    }

    private String buildRelevanceFilterPrompt(String userQuestion, List<AgentContextItem> contexts, int limit, String toolName) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户问题: ").append(userQuestion).append("\n");
        builder.append("来源工具: ").append(toolName).append("\n");
        builder.append("最多保留条数: ").append(Math.max(1, limit * 2)).append("\n\n");
        builder.append("候选上下文(按数组下标):\n");
        for (int i = 0; i < contexts.size(); i++) {
            AgentContextItem item = contexts.get(i);
            builder.append("[").append(i).append("] ")
                    .append(item.sourceType()).append(" / ").append(item.sourceId()).append("\n")
                    .append(item.content()).append("\n");
        }
        builder.append("\n请返回 JSON 数组，每项包含 index 和 score，例如：");
        builder.append("[{\"index\":0,\"score\":0.95},{\"index\":3,\"score\":0.80}]。");
        builder.append("按相关性从高到低返回，score 范围 0-1。");
        return builder.toString();
    }

    private String extractJsonArray(String raw) {
        if (raw == null) {
            return "[]";
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return "[]";
    }

    private double scoreOf(Map<String, Object> row) {
        Object score = row.get("score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        if (score == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(String.valueOf(score));
        } catch (Exception ignored) {
            return 0D;
        }
    }

    private record ToolDecision(
            String action,
            String toolName,
            Map<String, Object> args,
            String finalAnswer,
            String reason
    ) {
    }
}
