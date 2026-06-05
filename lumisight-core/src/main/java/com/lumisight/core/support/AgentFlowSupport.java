package com.lumisight.core.support;

import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.service.ChildAgentPermissionPolicy;
import com.lumisight.core.agent.multiagent.service.MultiAgentExecutionContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentToolRegistry;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.skills.runtime.SkillPlan;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

@Component
public class AgentFlowSupport {

    private static final Set<AgentToolPermission> BASE_TOOL_PERMISSIONS = EnumSet.of(
            AgentToolPermission.LOCAL_FS_READ,
            AgentToolPermission.GIT_READ,
            AgentToolPermission.MEMORY_READ,
            AgentToolPermission.MEMORY_WRITE,
            AgentToolPermission.TODO_WRITE,
            AgentToolPermission.AGENT_SPAWN
    );

    private final AgentToolRegistry agentToolRegistry;
    private final ChildAgentPermissionPolicy childAgentPermissionPolicy;

    public AgentFlowSupport(AgentToolRegistry agentToolRegistry, ChildAgentPermissionPolicy childAgentPermissionPolicy) {
        this.agentToolRegistry = agentToolRegistry;
        this.childAgentPermissionPolicy = childAgentPermissionPolicy;
    }

    public String resolveRepoRoot(String repoRoot, String skillPath) {
        if (StringUtils.hasText(repoRoot)) {
            return repoRoot.trim();
        }
        return Path.of("").toAbsolutePath().normalize().toString();
    }

    public Map<String, Object> buildSkillMetadata(String repoRoot, String skillPath) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("repoRoot", repoRoot);
        metadata.put("skillPath", skillPath);
        return metadata;
    }

    public AgentRequest withQuestion(AgentRequest request, String question, String sessionId) {
        return new AgentRequest(
                request.taskType(),
                request.repoRoot(),
                question,
                request.skillPath(),
                request.userId(),
                sessionId,
                request.approveRiskyToolCall(),
                request.interrupt(),
                request.resume(),
                request.includeRagContext(),
                request.includeKnowledgeGraphContext(),
                request.contextLimit(),
                request.runMode(),
                request.dialogueMode()
        );
    }

    public Set<AgentToolPermission> enabledPermissions(AgentRequest request, SkillPlan skillPlan) {
        MultiAgentExecutionContext.Context executionContext = MultiAgentExecutionContext.current();
        if (executionContext != null && executionContext.role() != MultiAgentExecutionContext.Role.LEAD_AGENT) {
            return childAgentPermissionPolicy.permissionsFor(
                    executionContext.role(),
                    capabilityFromTaskType(request)
            );
        }
        Set<AgentToolPermission> enabledPermissions = EnumSet.noneOf(AgentToolPermission.class);
        enabledPermissions.addAll(BASE_TOOL_PERMISSIONS);
        if (request.includeRagContext()) {
            enabledPermissions.add(AgentToolPermission.HYBRID_VECTOR_READ);
        }
        if (request.includeKnowledgeGraphContext()) {
            enabledPermissions.add(AgentToolPermission.KG_ONE_HOP_READ);
            enabledPermissions.add(AgentToolPermission.METHOD_SOURCE_READ);
        }
        if (request.taskType() != null && request.taskType().name().equals("BUG_FIX")) {
            enabledPermissions.add(AgentToolPermission.LSP_JAVA_READ);
            enabledPermissions.add(AgentToolPermission.BUILD_COMPILE);
        }
        return enabledPermissions;
    }

    private SubAgentCapability capabilityFromTaskType(AgentRequest request) {
        if (request == null || request.taskType() == null) {
            return SubAgentCapability.CODE_EXPLAIN;
        }
        return switch (request.taskType()) {
            case BUG_FIX -> SubAgentCapability.BUG_FIX;
            case CHAT, CODE_EXPLAIN -> SubAgentCapability.CODE_EXPLAIN;
        };
    }

    public boolean requiresHumanGate(ToolDecision decision) {
        if (decision == null) {
            return false;
        }
        PermissionedAgentTool tool = agentToolRegistry.get(decision.toolName());
        return tool != null && tool.permission() == AgentToolPermission.LOCAL_FS_WRITE;
    }

    public boolean shouldVerifyFinalAnswer(
            AgentRequest request,
            String question,
            String candidateAnswer,
            List<AgentContextItem> contexts
    ) {
        if (request.taskType() != null && "BUG_FIX".equals(request.taskType().name())) {
            return true;
        }
        String text = (question == null ? "" : question) + "\n" + (candidateAnswer == null ? "" : candidateAnswer);
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("行号")
                || lower.contains("stacktrace")
                || lower.contains("堆栈")
                || lower.contains("错误码")
                || lower.contains("版本")
                || lower.contains("精确")
                || lower.contains("patch")
                || lower.contains("diff")) {
            return true;
        }
        return contexts != null && contexts.size() >= 8;
    }
}
