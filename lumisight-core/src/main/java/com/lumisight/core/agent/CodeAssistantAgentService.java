package com.lumisight.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.AgentRequest;
import com.lumisight.core.agent.model.ToolDecision;
import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.support.AgentPromptService;
import com.lumisight.core.agent.support.AgentRequestValidators;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.AgentToolRegistry;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

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

    private final ChatClient llmChatClient;
    private final AgentToolRegistry agentToolRegistry;
    private final AgentPromptService agentPromptService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public CodeAssistantAgentService(
            ChatClient.Builder chatClientBuilder,
            AgentToolRegistry agentToolRegistry,
            AgentPromptService agentPromptService
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.agentToolRegistry = agentToolRegistry;
        this.agentPromptService = agentPromptService;
    }

    public Flux<String> run(AgentRequest request) {
        AgentRequestValidators.validate(request);

        int limit = request.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : request.contextLimit();
        List<AgentContextItem> contexts = new ArrayList<>();
        String directAnswer = null;

        try (AgentToolRuntimeContext.Scope ignored = AgentToolRuntimeContext.open(request.repoRoot(), limit)) {
            directAnswer = runManualOrchestration(request, contexts, limit);
        }
        if (StringUtils.hasText(directAnswer)) {
            return Flux.just(directAnswer);
        }
        String finalPrompt = agentPromptService.buildFinalAnswerPrompt(request, contexts, limit);
        return llmChatClient.prompt()
                .system(agentPromptService.systemPrompt(request.taskType()))
                .user(finalPrompt)
                .stream()
                .content();
    }

    private String runManualOrchestration(AgentRequest request, List<AgentContextItem> contexts, int limit) {
        Set<AgentToolPermission> enabledPermissions = enabledPermissions(request);
        for (int round = 1; round <= MAX_TOOL_ROUNDS; round++) {
            String decisionRaw = llmChatClient.prompt()
                    .system(agentPromptService.orchestratorSystemPrompt(request.taskType(), enabledPermissions, agentToolRegistry))
                    .user(agentPromptService.orchestratorUserPrompt(request, contexts, limit, round, MAX_TOOL_ROUNDS))
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
        return null;
    }

    private Set<AgentToolPermission> enabledPermissions(AgentRequest request) {
        Set<AgentToolPermission> enabledPermissions = EnumSet.noneOf(AgentToolPermission.class);
        if (request.includeRagContext()) {
            enabledPermissions.addAll(DEFAULT_RAG_TOOL_PERMISSIONS);
        }
        return enabledPermissions;
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
}
