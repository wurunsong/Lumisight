package com.lumisight.core.agent.service;

import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.AgentRequest;
import com.lumisight.core.agent.model.AgentResponse;
import com.lumisight.core.agent.model.AgentTaskType;
import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.port.KnowledgeGraphContextProvider;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class CodeAssistantAgentService {

    private static final int DEFAULT_CONTEXT_LIMIT = 5;
    private static final Set<AgentToolPermission> DEFAULT_RAG_TOOL_PERMISSIONS = EnumSet.of(
            AgentToolPermission.CODE_VECTOR_READ,
            AgentToolPermission.COMMENT_VECTOR_READ
    );

    private final ChatClient chatClient;
    private final List<PermissionedAgentTool> permissionedAgentTools;
    private final KnowledgeGraphContextProvider knowledgeGraphContextProvider;

    public CodeAssistantAgentService(
            ChatClient.Builder chatClientBuilder,
            List<PermissionedAgentTool> permissionedAgentTools,
            KnowledgeGraphContextProvider knowledgeGraphContextProvider
    ) {
        this.chatClient = chatClientBuilder.build();
        this.permissionedAgentTools = permissionedAgentTools;
        this.knowledgeGraphContextProvider = knowledgeGraphContextProvider;
    }

    public AgentResponse run(AgentRequest request) {
        validateRequest(request);

        int limit = request.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : request.contextLimit();
        List<AgentContextItem> contexts = new ArrayList<>();

        if (request.includeKnowledgeGraphContext()) {
            contexts.addAll(knowledgeGraphContextProvider.retrieve(request.repoRoot(), request.question(), limit));
        }

        String answer;
        try (AgentToolRuntimeContext.Scope ignored = AgentToolRuntimeContext.open(request.repoRoot(), limit)) {
            answer = buildPrompt(request, limit)
                    .system(systemPrompt(request.taskType()))
                    .user(buildUserPrompt(request, contexts, limit))
                    .call()
                    .content();
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

    private ChatClient.ChatClientRequestSpec buildPrompt(AgentRequest request, int limit) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
        if (request.includeRagContext()) {
            Object[] enabledTools = permissionedAgentTools.stream()
                    .filter(tool -> DEFAULT_RAG_TOOL_PERMISSIONS.contains(tool.permission()))
                    .toArray();
            if (enabledTools.length > 0) {
                spec = spec.tools(enabledTools);
            }
        }
        return spec.advisors(advisorSpec -> advisorSpec.param("repoRoot", request.repoRoot()).param("contextLimit", limit));
    }

    private String buildUserPrompt(
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
}
