package com.lumisight.core.support;

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
            AgentToolPermission.GIT_READ
    );

    private final AgentToolRegistry agentToolRegistry;

    public AgentFlowSupport(AgentToolRegistry agentToolRegistry) {
        this.agentToolRegistry = agentToolRegistry;
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
        if (skillPlan != null && skillPlan.preferredTools() != null) {
            for (String toolName : skillPlan.preferredTools()) {
                PermissionedAgentTool tool = agentToolRegistry.get(normalizeToolName(toolName));
                if (tool != null) {
                    enabledPermissions.add(tool.permission());
                }
            }
        }
        return enabledPermissions;
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

    private String normalizeToolName(String requestedToolName) {
        if (!StringUtils.hasText(requestedToolName)) {
            return "";
        }
        return switch (requestedToolName.trim()) {
            case "read_file", "readFile", "open_file", "openFile", "get_file_content" -> "cat";
            case "read_directory", "list_directory", "get_directory_structure", "listDir" -> "ls";
            case "search_files", "search_in_files", "find_in_files" -> "grep";
            default -> requestedToolName.trim();
        };
    }
}
