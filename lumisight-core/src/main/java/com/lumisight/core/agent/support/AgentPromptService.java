package com.lumisight.core.agent.support;

import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.AgentDialogueMode;
import com.lumisight.core.agent.model.AgentRequest;
import com.lumisight.core.agent.model.AgentTaskType;
import com.lumisight.core.agent.tool.AgentToolCategory;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.AgentToolRegistry;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class AgentPromptService {

    public String systemPrompt(AgentTaskType taskType) {
        if (taskType == AgentTaskType.BUG_FIX) {
            return "你是一名资深 Java 工程师。请聚焦根因分析、低风险修复方案和可验证的补丁建议。输出要结构清晰，先给结论，再给依据。";
        }
        return "你是一名资深 Java 工程师。请清晰解释代码意图、架构关系、控制流程和关键取舍。解释要贴近工程实践，并尽量给出可落地建议。";
    }

    public String buildFinalAnswerPrompt(AgentRequest request, List<AgentContextItem> contexts, int limit) {
        StringBuilder builder = new StringBuilder();
        builder.append("TaskType: ").append(request.taskType()).append("\\n");
        builder.append("RepoRoot: ").append(request.repoRoot()).append("\\n");
        builder.append("ContextLimit: ").append(limit).append("\\n");
        builder.append("用户问题: ").append(request.question()).append("\\n\\n");
        builder.append("已检索上下文:\\n");
        if (contexts.isEmpty()) {
            builder.append("- 无\\n");
        } else {
            for (AgentContextItem context : contexts) {
                builder.append("- [").append(context.sourceType()).append("] ")
                        .append(context.sourceId()).append("\\n")
                        .append(context.content()).append("\\n");
            }
        }
        builder.append("\\n请使用中文回答，并给出可执行的下一步建议。");
        return builder.toString();
    }

    public String orchestratorSystemPrompt(
            AgentTaskType taskType,
            AgentDialogueMode dialogueMode,
            Set<AgentToolPermission> enabledPermissions,
            AgentToolRegistry registry
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append(systemPrompt(taskType)).append("\\n");
        builder.append("你在执行手动工具编排。每轮只能输出一个JSON对象，不要输出其他文本。\\n");
        builder.append("JSON结构:\\n");
        builder.append("{\\\"action\\\":\\\"tool|ask_user|final\\\",\\\"toolName\\\":\\\"...\\\",\\\"args\\\":{},\\\"finalAnswer\\\":\\\"...\\\",\\\"askUserQuestion\\\":\\\"...\\\",\\\"reason\\\":\\\"...\\\"}\\n");
        builder.append("规则:\\n");
        builder.append("- 若上下文不足，action=tool，并选择一个已启用工具。\\n");
        builder.append("- 若信息足够，action=final，并在finalAnswer给出最终回答。\\n");
        builder.append("- 允许 action=ask_user，当关键信息缺失且无法通过工具补全时使用。\\n");
        builder.append("- 禁止输出Markdown。\\n");
        builder.append("当前对话管理策略:\\n").append(dialogueModeGuidance(dialogueMode)).append("\\n");
        builder.append("已启用工具:\\n").append(enabledToolHints(enabledPermissions, registry));
        return builder.toString();
    }

    public String orchestratorUserPrompt(AgentRequest request, List<AgentContextItem> contexts, int limit, int round, int maxRounds) {
        StringBuilder builder = new StringBuilder();
        builder.append("轮次: ").append(round).append("/").append(maxRounds).append("\\n");
        builder.append("用户问题: ").append(request.question()).append("\\n");
        builder.append("上下文上限: ").append(limit).append("\\n\\n");
        builder.append("当前上下文:\\n");
        if (contexts.isEmpty()) {
            builder.append("- 无\\n");
        } else {
            for (AgentContextItem context : contexts) {
                builder.append("- [").append(context.sourceType()).append("] ")
                        .append(context.sourceId()).append("\\n");
            }
        }
        builder.append("\\n请输出本轮JSON决策。");
        return builder.toString();
    }

    public String planPrompt(AgentRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("请先给出执行计划，不要直接回答问题。\\n");
        builder.append("用户问题: ").append(request.question()).append("\\n");
        builder.append("输出要求: 按步骤列出你计划调用的工具、每步目标和预期产出。");
        return builder.toString();
    }

    public String verifyPrompt(AgentRequest request, String candidateAnswer, List<AgentContextItem> contexts) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是答案复核器。请判断候选答案是否真正回答了用户问题，且有上下文依据。\\n");
        builder.append("仅输出JSON，不要输出其他文本。\\n");
        builder.append("格式: {\"pass\":true|false,\"reason\":\"...\"}\\n");
        builder.append("用户问题: ").append(request.question()).append("\\n");
        builder.append("候选答案: ").append(candidateAnswer).append("\\n");
        builder.append("上下文摘要:\\n");
        if (contexts.isEmpty()) {
            builder.append("- 无\\n");
        } else {
            for (AgentContextItem context : contexts) {
                builder.append("- [").append(context.sourceType()).append("] ").append(context.sourceId()).append("\\n");
            }
        }
        return builder.toString();
    }

    private String enabledToolHints(Set<AgentToolPermission> enabledPermissions, AgentToolRegistry registry) {
        StringBuilder builder = new StringBuilder();
        List<AgentToolCategory> categories = List.of(
                AgentToolCategory.RAG,
                AgentToolCategory.GRAPH,
                AgentToolCategory.SOURCE,
                AgentToolCategory.MCP,
                AgentToolCategory.LOCAL,
                AgentToolCategory.LSP
        );
        for (AgentToolCategory category : categories) {
            for (PermissionedAgentTool tool : registry.getByCategory(category)) {
                if (enabledPermissions.contains(tool.permission())) {
                    builder.append("- ").append(tool.toolName()).append("(...): ").append(category).append(" 类型工具");
                    if (!tool.argumentSpecs().isEmpty()) {
                        builder.append("，参数: ");
                        builder.append(tool.argumentSpecs().stream()
                                .map(spec -> spec.name() + ":" + spec.type() + (spec.required() ? "(必填)" : ""))
                                .reduce((a, b) -> a + ", " + b)
                                .orElse(""));
                    }
                    builder.append("\\n");
                }
            }
        }
        return builder.toString();
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
}
