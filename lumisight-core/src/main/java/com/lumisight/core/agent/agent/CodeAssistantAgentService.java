package com.lumisight.core.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.AgentRequest;
import com.lumisight.core.agent.model.AgentResponse;
import com.lumisight.core.agent.model.AgentTaskType;
import com.lumisight.core.agent.context.AgentToolRuntimeContext;
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
import java.util.Map;
import java.util.Set;

@Service
public class CodeAssistantAgentService {

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int DEFAULT_CONTEXT_LIMIT = 5;
    private static final Set<AgentToolPermission> DEFAULT_RAG_TOOL_PERMISSIONS = EnumSet.of(
            AgentToolPermission.HYBRID_VECTOR_READ
    );
    private static final Set<AgentToolPermission> DEFAULT_KG_TOOL_PERMISSIONS = EnumSet.of(
            AgentToolPermission.KG_ONE_HOP_READ,
            AgentToolPermission.METHOD_SOURCE_READ
    );

    private final ChatClient chatClient;
    private final AgentToolRegistry agentToolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public CodeAssistantAgentService(
            ChatClient.Builder chatClientBuilder,
            AgentToolRegistry agentToolRegistry
    ) {
        this.chatClient = chatClientBuilder.build();
        this.agentToolRegistry = agentToolRegistry;
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
            String decisionRaw = chatClient.prompt()
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

        return chatClient.prompt()
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
        if (request.includeKnowledgeGraphContext()) {
            enabledPermissions.addAll(DEFAULT_KG_TOOL_PERMISSIONS);
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
        return tool.invoke(args, limit);
    }

    private List<AgentContextItem> denied(String toolName) {
        return List.of(new AgentContextItem(
                "tool_error",
                "permission_denied",
                "工具未启用: " + toolName,
                Map.of("toolName", toolName)
        ));
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
