package com.lumisight.core.support;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentTaskType;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentToolRegistry;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.skills.runtime.SkillPlan;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

@Component
public class AgentPromptService {

    private final PromptTemplateService promptTemplateService;

    public AgentPromptService(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    public String systemPrompt(AgentTaskType taskType) {
        return promptTemplateService.render(switch (taskType) {
            case BUG_FIX -> "system_bug_fix";
            case CHAT -> "system_chat";
            default -> "system_code_explain";
        }, Map.of());
    }

    public String verifySystemPrompt() {
        return promptTemplateService.render("verify_system", Map.of());
    }

    public String buildFinalAnswerPrompt(AgentRequest request, List<AgentContextItem> contexts, int limit, SkillPlan skillPlan) {
        return promptTemplateService.render("final_answer", Map.of(
                "taskType", String.valueOf(request.taskType()),
                "repoRoot", safeText(request.repoRoot()),
                "contextLimit", limit,
                "question", safeText(request.question()),
                "skillGuidance", skillGuidanceBlock(skillPlan, true),
                "contextBlock", detailedContextBlock(contexts)
        ));
    }

    public String orchestratorSystemPrompt(
            AgentTaskType taskType,
            AgentDialogueMode dialogueMode,
            Set<AgentToolPermission> enabledPermissions,
            AgentToolRegistry registry,
            SkillPlan skillPlan
    ) {
        return promptTemplateService.render("orchestrator_system", Map.of(
                "systemPrompt", systemPrompt(taskType),
                "skillGuidance", skillGuidanceBlock(skillPlan, false),
                "dialogueModeGuidance", dialogueModeGuidance(dialogueMode),
                "enabledToolHints", enabledToolHints(enabledPermissions, registry)
        ));
    }

    public String orchestratorUserPrompt(
            AgentRequest request,
            List<AgentContextItem> contexts,
            int limit,
            int round,
            int maxRounds,
            SkillPlan skillPlan
    ) {
        return promptTemplateService.render("orchestrator_user", Map.of(
                "round", round,
                "maxRounds", maxRounds,
                "question", safeText(request.question()),
                "contextLimit", limit,
                "skillStepsBlock", skillStepsBlock(skillPlan),
                "contextSummary", contextSummary(contexts)
        ));
    }

    public String planPrompt(AgentRequest request, SkillPlan skillPlan) {
        return promptTemplateService.render("plan", Map.of(
                "question", safeText(request.question()),
                "skillGuidance", skillGuidanceBlock(skillPlan, true)
        ));
    }

    public String verifyPrompt(AgentRequest request, String candidateAnswer, List<AgentContextItem> contexts) {
        return promptTemplateService.render("verify_user", Map.of(
                "question", safeText(request.question()),
                "candidateAnswer", safeText(candidateAnswer),
                "contextSummary", contextSummary(contexts)
        ));
    }

    public String relevanceFilterPrompt(String userQuestion, List<AgentContextItem> contexts, int limit, String toolName) {
        return promptTemplateService.render("relevance_filter_user", Map.of(
                "userQuestion", safeText(userQuestion),
                "toolName", safeText(toolName),
                "maxKeep", Math.max(1, limit * 2),
                "contextsBlock", indexedContextBlock(contexts)
        ));
    }

    public String relevanceFilterSystemPrompt() {
        return promptTemplateService.render("relevance_filter_system", Map.of());
    }

    public String skillRouterSystemPrompt() {
        return promptTemplateService.render("skill_router_system", Map.of());
    }

    public String skillRouterUserPrompt(String question, String skillsBlock) {
        return promptTemplateService.render("skill_router_user", Map.of(
                "question", safeText(question),
                "skillsBlock", safeText(skillsBlock)
        ));
    }

    private String enabledToolHints(Set<AgentToolPermission> enabledPermissions, AgentToolRegistry registry) {
        StringJoiner joiner = new StringJoiner("\n");
        List<AgentToolCategory> categories = List.of(
                AgentToolCategory.RAG,
                AgentToolCategory.GRAPH,
                AgentToolCategory.SOURCE,
                AgentToolCategory.MCP,
                AgentToolCategory.LOCAL,
                AgentToolCategory.LSP,
                AgentToolCategory.BUILD,
                AgentToolCategory.GIT
        );
        for (AgentToolCategory category : categories) {
            for (PermissionedAgentTool<?> tool : registry.getByCategory(category)) {
                if (!enabledPermissions.contains(tool.permission())) {
                    continue;
                }
                StringJoiner toolJoiner = new StringJoiner("\n");
                String description = safeText(tool.description()).isBlank()
                        ? category + " 类型工具"
                        : tool.description();
                toolJoiner.add("- " + tool.toolName() + "(...): " + description
                        + " 使用时不要只满足必填参数；应根据当前任务主动补齐能缩小范围、减少噪音、提升精度的非必填参数。"
                        + " [category=" + category + ", permission=" + tool.permission() + "]");
                if (!tool.argumentSpecs().isEmpty()) {
                    toolJoiner.add("  argsSchema: " + argsSchemaJson(tool.argumentSpecs()));
                }
                if (!tool.exampleArgs().isEmpty()) {
                    toolJoiner.add("  exampleArgs: " + toJson(tool.exampleArgs()));
                }
                joiner.add(toolJoiner.toString());
            }
        }
        String text = joiner.toString();
        return text.isBlank() ? "- 无已启用工具" : text;
    }

    private String argsSchemaJson(List<ToolArgumentSpec> specs) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (ToolArgumentSpec spec : specs) {
            StringJoiner item = new StringJoiner(", ", "{", "}");
            item.add("\"type\":\"" + escapeJson(spec.type()) + "\"");
            item.add("\"required\":" + spec.required());
            if (spec.description() != null && !spec.description().isBlank()) {
                item.add("\"description\":\"" + escapeJson(spec.description()) + "\"");
            }
            joiner.add("\"" + escapeJson(spec.name()) + "\":" + item);
        }
        return joiner.toString();
    }

    private String detailedContextBlock(List<AgentContextItem> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "已检索上下文:\n- 无";
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (AgentContextItem context : contexts) {
            joiner.add("- [" + context.sourceType() + "] " + context.sourceId() + "\n" + safeText(context.content()));
        }
        return "已检索上下文:\n" + joiner;
    }

    private String contextSummary(List<AgentContextItem> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "- 无";
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (AgentContextItem context : contexts) {
            joiner.add("- [" + context.sourceType() + "] " + context.sourceId());
        }
        return joiner.toString();
    }

    private String indexedContextBlock(List<AgentContextItem> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "- 无";
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (int i = 0; i < contexts.size(); i++) {
            AgentContextItem item = contexts.get(i);
            joiner.add("[" + i + "] " + item.sourceType() + " / " + item.sourceId() + "\n" + safeText(item.content()));
        }
        return joiner.toString();
    }

    private String skillStepsBlock(SkillPlan skillPlan) {
        if (skillPlan == null || skillPlan.executionSteps() == null || skillPlan.executionSteps().isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (String step : skillPlan.executionSteps()) {
            joiner.add("- " + step);
        }
        return "当前技能建议执行步骤:\n" + joiner + "\n\n";
    }

    private String skillGuidanceBlock(SkillPlan skillPlan, boolean includeOutputContract) {
        if (skillPlan == null) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("\n");
        if (skillPlan.summary() != null && !skillPlan.summary().isBlank()) {
            joiner.add("当前技能摘要: " + skillPlan.summary());
        }
        if (skillPlan.executionSteps() != null && !skillPlan.executionSteps().isEmpty()) {
            StringJoiner steps = new StringJoiner("\n");
            for (String step : skillPlan.executionSteps()) {
                steps.add("- " + step);
            }
            joiner.add("技能执行提示:\n" + steps);
        }
        if (includeOutputContract && skillPlan.outputContract() != null && !skillPlan.outputContract().isBlank()) {
            joiner.add("技能输出约束: " + skillPlan.outputContract());
        }
        if (skillPlan.rawSkillContent() != null && !skillPlan.rawSkillContent().isBlank()) {
            joiner.add("技能原文参考:\n" + trimSkillContent(skillPlan.rawSkillContent()));
        }
        String text = joiner.toString();
        return text.isBlank() ? "" : text + "\n\n";
    }

    private String dialogueModeGuidance(AgentDialogueMode dialogueMode) {
        if (dialogueMode == AgentDialogueMode.COLLECT) {
            return "- COLLECT: 优先收集信息与证据；在回答前尽量补齐上下文。信息不足时优先 ask_user。";
        }
        if (dialogueMode == AgentDialogueMode.STEER) {
            return "- STEER: 主动引导用户收敛问题；当范围过大时先提出拆解路径，再执行关键工具。";
        }
        return "- FOLLOW: 严格跟随用户当前问题，最短路径完成回答。";
    }

    private String trimSkillContent(String rawSkillContent) {
        String normalized = rawSkillContent.trim();
        int maxChars = 4000;
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "\n...(技能内容已截断)";
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return "\"" + escapeJson(text) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringJoiner joiner = new StringJoiner(", ", "{", "}");
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                joiner.add("\"" + escapeJson(String.valueOf(entry.getKey())) + "\":" + toJson(entry.getValue()));
            }
            return joiner.toString();
        }
        if (value instanceof List<?> list) {
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            for (Object item : list) {
                joiner.add(toJson(item));
            }
            return joiner.toString();
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private String escapeJson(String text) {
        return safeText(text).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }
}
