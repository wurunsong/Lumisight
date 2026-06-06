package com.lumisight.core.support;

import com.lumisight.core.agent.multiagent.service.MultiAgentExecutionContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.AgentTaskType;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentToolRegistry;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.memory.RelevantMemoryContext;
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
        return systemPrompt(taskType, RelevantMemoryContext.empty());
    }

    public String systemPrompt(AgentTaskType taskType, RelevantMemoryContext memoryContext) {
        String base = promptTemplateService.render(switch (taskType) {
            case BUG_FIX -> "system_bug_fix";
            case CHAT -> "system_chat";
            default -> "system_code_explain";
        }, Map.of());
        String memoryBlock = memoryReminderBlock(memoryContext);
        String multiAgentBlock = multiAgentExecutionBlock();
        String prompt = memoryBlock.isBlank() ? base : base + "\n\n" + memoryBlock;
        return multiAgentBlock.isBlank() ? prompt : prompt + "\n\n" + multiAgentBlock;
    }

    public String verifySystemPrompt() {
        return promptTemplateService.render("verify_system", Map.of());
    }

    public String buildFinalAnswerPrompt(
            AgentRequest request,
            List<AgentContextItem> contexts,
            int limit,
            SkillPlan skillPlan,
            RelevantMemoryContext memoryContext
    ) {
        return promptTemplateService.render("final_answer", Map.of(
                "taskType", String.valueOf(request.taskType()),
                "repoRoot", safeText(request.repoRoot()),
                "contextLimit", limit,
                "question", safeText(request.question()),
                "skillGuidance", skillGuidanceBlock(skillPlan, true),
                "memoryGuidance", memoryReminderBlock(memoryContext),
                "contextBlock", detailedContextBlock(contexts)
        ));
    }

    public String orchestratorSystemPrompt(
            AgentTaskType taskType,
            AgentRunMode runMode,
            AgentDialogueMode dialogueMode,
            Set<AgentToolPermission> enabledPermissions,
            AgentToolRegistry registry,
            SkillPlan skillPlan,
            RelevantMemoryContext memoryContext
    ) {
        String prompt = promptTemplateService.render("orchestrator_system", Map.of(
                "systemPrompt", systemPrompt(taskType),
                "skillGuidance", skillGuidanceBlock(skillPlan, false),
                "dialogueModeGuidance", dialogueModeGuidance(dialogueMode),
                "multiAgentGuidance", multiAgentGuidance(runMode),
                "memoryGuidance", memoryReminderBlock(memoryContext),
                "todoGuidance", todoGuidanceBlock(),
                "enabledToolHints", enabledToolHints(enabledPermissions, registry)
        ));
        return prompt;
    }

    public String orchestratorUserPrompt(
            AgentRequest request,
            List<AgentContextItem> contexts,
            int limit,
            int round,
            int maxRounds,
            SkillPlan skillPlan
    ) {
        String prompt = promptTemplateService.render("orchestrator_user", Map.of(
                "round", round,
                "maxRounds", maxRounds,
                "question", safeText(request.question()),
                "contextLimit", limit,
                "skillStepsBlock", skillStepsBlock(skillPlan),
                "contextBlock", detailedContextBlock(contexts)
        ));
        return prompt;
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
                AgentToolCategory.MEMORY,
                AgentToolCategory.PLANNING,
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

    private String multiAgentGuidance(AgentRunMode runMode) {
        if (runMode == AgentRunMode.MULTI_AGENT) {
            return "- MULTI_AGENT: 先判断是否保持单 Agent；只有局部分析会显著污染上下文时才调用 task_subagent。不要把所有任务都机械拆给子 Agent。";
        }
        return "- NORMAL: 默认优先单 Agent；只有在局部复杂分析明显受益时才考虑 task_subagent。";
    }

    private String todoGuidanceBlock() {
        return "- 会话内 checklist：如果只是当前会话里的多步骤执行，请使用 todo_write 维护任务清单；先列出所有步骤，再把状态从 pending 逐步更新为 in_progress 和 completed。\n"
                + "- 持久化任务系统：如果任务需要跨会话保留、存在 blockedBy 依赖关系，或需要 claim/complete/release/resume/board 语义，请使用 task_create / task_list / task_get / task_claim / task_release / task_complete / task_resume / task_board，而不是只写 todo。\n"
                + "- 提醒机制：系统可能会注入 <reminder>Update your todos.</reminder>，收到后请先刷新会话内 checklist，再继续执行。";
    }

    private String memoryReminderBlock(RelevantMemoryContext memoryContext) {
        RelevantMemoryContext safeContext = memoryContext == null ? RelevantMemoryContext.empty() : memoryContext;
        StringBuilder builder = new StringBuilder();
        builder.append("长期记忆规则:\n");
        builder.append("- 只可依赖四类长期记忆：user / feedback / project / reference。\n");
        builder.append("- 若需要写入长期记忆，只保存稳定的跨会话信息；不要保存代码结构、git 历史、临时任务状态、当前会话上下文，或任何能从当前仓库实时推导的信息。\n");
        builder.append("- feedback 记忆优先写明 Why 和 How to apply；project 记忆若涉及日期，必须使用绝对日期。\n\n");
        builder.append("长期记忆索引:\n");
        builder.append(safeText(safeContext.entrypoint().content()));
        if (safeContext.remindersBlock() != null && !safeContext.remindersBlock().isBlank()) {
            builder.append("\n\n已加载的相关长期记忆:\n");
            builder.append(safeContext.remindersBlock());
        }
        return builder.toString();
    }

    private String multiAgentExecutionBlock() {
        MultiAgentExecutionContext.Context context = MultiAgentExecutionContext.current();
        if (context == null) {
            return "";
        }
        if (context.role() == MultiAgentExecutionContext.Role.SUB_AGENT) {
            return "子 Agent 约束:\n"
                    + "- 你运行在隔离子上下文中，只完成当前子任务。\n"
                    + "- 不要请求用户，不要再委派新的 agent，不要假设自己拥有写仓库权限。\n"
                    + "- 只输出完成当前任务所需的结论、证据和建议下一步。";
        }
        if (context.role() == MultiAgentExecutionContext.Role.TEAM_AGENT) {
            return "Team Agent 约束:\n"
                    + "- 你是长期协作队友，只处理 inbox 分配给你的任务。\n"
                    + "- 不要创建新的 agent，不要直接面向用户给最终答案。\n"
                    + "- 返回结构化结论与证据引用，等待 Lead 汇总。";
        }
        return "";
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
