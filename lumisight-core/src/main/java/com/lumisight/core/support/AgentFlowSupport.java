package com.lumisight.core.support;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentMcpRegistry;
import com.lumisight.core.tool.PermissionedAgentTool;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Component
public class AgentFlowSupport {

    private final AgentMcpRegistry agentMcpRegistry;

    public AgentFlowSupport(AgentMcpRegistry agentMcpRegistry) {
        this.agentMcpRegistry = agentMcpRegistry;
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

    public boolean requiresHumanGate(ToolDecision decision) {
        if (decision == null) {
            return false;
        }
        PermissionedAgentTool tool = agentMcpRegistry.get(decision.toolName());
        return tool != null
                && (tool.permission() == AgentToolPermission.LOCAL_FS_WRITE
                || (tool.permission() == AgentToolPermission.MCP_CAPABILITY_CALL && !tool.isReadOnly()));
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
