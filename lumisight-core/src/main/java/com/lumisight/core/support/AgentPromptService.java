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

import java.util.List;
import java.util.Set;

@Component
public class AgentPromptService {

    public String systemPrompt(AgentTaskType taskType) {
        if (taskType == AgentTaskType.BUG_FIX) {
            return "你是一名资深 Java 工程师。请聚焦根因分析、低风险修复方案和可验证的补丁建议。输出要结构清晰，先给结论，再给依据。";
        }
        if (taskType == AgentTaskType.CHAT) {
            return "你是一名专业且友好的工程助手。请先准确理解用户意图，再给出清晰、简洁、可执行的回答；必要时提出补充问题以避免误解。";
        }
        return "你是一名资深 Java 工程师。请清晰解释代码意图、架构关系、控制流程和关键取舍。解释要贴近工程实践，并尽量给出可落地建议。";
    }

    public String buildFinalAnswerPrompt(AgentRequest request, List<AgentContextItem> contexts, int limit, SkillPlan skillPlan) {
        StringBuilder builder = new StringBuilder();
        builder.append("TaskType: ").append(request.taskType()).append("\\n");
        builder.append("RepoRoot: ").append(request.repoRoot()).append("\\n");
        builder.append("ContextLimit: ").append(limit).append("\\n");
        builder.append("用户问题: ").append(request.question()).append("\\n\\n");
        appendSkillGuidance(builder, skillPlan, true);
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
            AgentToolRegistry registry,
            SkillPlan skillPlan
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append(systemPrompt(taskType)).append("\\n");
        appendSkillGuidance(builder, skillPlan, false);
        builder.append("你在执行手动工具编排。每轮只能输出一个JSON对象，不要输出其他文本。\\n");
        builder.append("JSON结构:\\n");
        builder.append("{\\\"action\\\":\\\"tool|ask_user|final\\\",\\\"toolName\\\":\\\"...\\\",\\\"args\\\":{},\\\"toolCalls\\\":[{\\\"toolName\\\":\\\"...\\\",\\\"args\\\":{}}],\\\"finalAnswer\\\":\\\"...\\\",\\\"askUserQuestion\\\":\\\"...\\\",\\\"reason\\\":\\\"...\\\"}\\n");
        builder.append("规则:\\n");
        builder.append("- 若上下文不足，action=tool，并选择一个或多个已启用工具。\\n");
        builder.append("- 若信息足够，action=final，并在finalAnswer给出最终回答。\\n");
        builder.append("- 若用户问题不要求精确事实（如严格行号/版本号/错误码/可执行补丁），可直接给出结论并结束，不必强行走复核导向。\\n");
        builder.append("- 允许 action=ask_user，当关键信息缺失且无法通过工具补全时使用。\\n");
        builder.append("- 只有当多个工具彼此独立、且属于并发安全的只读检索类调用时，才使用 toolCalls 一次返回多个工具。\\n");
        builder.append("- 若包含写操作、编译、回滚、或存在顺序依赖，请只返回单个工具，不要并发。\\n");
        builder.append("- 生成 args 时必须严格参考每个工具的 argsSchema 与 exampleArgs；不要遗漏完成当前任务所需的关键参数。\\n");
        builder.append("- 可选参数不是一律省略：当它们能明显缩小范围、减少噪音、或提高定位精度时，应主动填写。\\n");
        builder.append("- 对于 cat、grep、gitDiff 这类检索工具，优先提供必要的范围/过滤参数，避免无界读取。\\n");
        builder.append("- 不要依赖服务端默认值来隐式补范围参数；凡是会影响检索范围、返回长度、过滤条件的参数，都要由你显式决定是否填写。\\n");
        builder.append("- 如果一个工具存在可选的范围型参数，你必须先判断当前任务是否需要缩小范围；需要时就显式填写，而不是省略。\\n");
        builder.append("高频工具用参要求:\\n");
        builder.append("- cat: 若你只需要局部内容，必须显式填写 startLine/endLine 或 maxLines；不要默认整文件读取。\\n");
        builder.append("- grep: 若已知文件范围或后缀，应填写 filePattern；若只需要少量命中，应填写 limit。\\n");
        builder.append("- gitDiff/gitBlame: 若只关注单文件，应填写 sourceFile；不要默认全仓库 diff/blame。\\n");
        builder.append("- ls: 若已知目标目录，应填写 path；若目录可能很大，应填写 limit。\\n");
        builder.append("- lint/compile 类工具: 若只验证单文件或局部范围，应填写 sourceFile 或 filePattern，不要默认扩大到整个仓库。\\n");
        builder.append("- 禁止输出Markdown。\\n");
        builder.append("当前对话管理策略:\\n").append(dialogueModeGuidance(dialogueMode)).append("\\n");
        builder.append("已启用工具:\\n").append(enabledToolHints(enabledPermissions, registry));
        return builder.toString();
    }

    public String orchestratorUserPrompt(AgentRequest request, List<AgentContextItem> contexts, int limit, int round, int maxRounds, SkillPlan skillPlan) {
        StringBuilder builder = new StringBuilder();
        builder.append("轮次: ").append(round).append("/").append(maxRounds).append("\\n");
        builder.append("用户问题: ").append(request.question()).append("\\n");
        builder.append("上下文上限: ").append(limit).append("\\n\\n");
        if (skillPlan != null && skillPlan.executionSteps() != null && !skillPlan.executionSteps().isEmpty()) {
            builder.append("当前技能建议执行步骤:\\n");
            for (String step : skillPlan.executionSteps()) {
                builder.append("- ").append(step).append("\\n");
            }
            builder.append("\\n");
        }
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

    public String planPrompt(AgentRequest request, SkillPlan skillPlan) {
        StringBuilder builder = new StringBuilder();
        builder.append("请先给出执行计划，不要直接回答问题。\\n");
        builder.append("用户问题: ").append(request.question()).append("\\n");
        appendSkillGuidance(builder, skillPlan, true);
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
                AgentToolCategory.LSP,
                AgentToolCategory.BUILD,
                AgentToolCategory.GIT
        );
        for (AgentToolCategory category : categories) {
            for (PermissionedAgentTool<?> tool : registry.getByCategory(category)) {
                if (enabledPermissions.contains(tool.permission())) {
                    builder.append("- ").append(tool.toolName()).append("(...): ");
                    if (tool.description() != null && !tool.description().isBlank()) {
                        builder.append(tool.description());
                    } else {
                        builder.append(category).append(" 类型工具");
                    }
                    builder.append(" 使用时不要只满足必填参数；应根据当前任务主动补齐能缩小范围、减少噪音、提升精度的非必填参数。");
                    builder.append(" [category=").append(category).append(", permission=").append(tool.permission()).append("]");
                    if (!tool.argumentSpecs().isEmpty()) {
                        builder.append("\\n  argsSchema: {");
                        builder.append(tool.argumentSpecs().stream()
                                .map((ToolArgumentSpec spec) -> "\"" + spec.name() + "\":{"
                                        + "\"type\":\"" + spec.type() + "\""
                                        + ",\"required\":" + spec.required()
                                        + (spec.description() != null && !spec.description().isBlank()
                                        ? ",\"description\":\"" + escapeJson(spec.description()) + "\""
                                        : "")
                                        + "}")
                                .reduce((a, b) -> a + ", " + b)
                                .orElse(""));
                        builder.append("}");
                    }
                    if (!tool.exampleArgs().isEmpty()) {
                        builder.append("\\n  exampleArgs: ").append(toJson(tool.exampleArgs()));
                    }
                    builder.append("\\n");
                }
            }
        }
        return builder.toString();
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
        if (value instanceof java.util.Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> "\"" + escapeJson(String.valueOf(entry.getKey())) + "\":" + toJson(entry.getValue()))
                    .reduce((a, b) -> a + ", " + b)
                    .map(body -> "{" + body + "}")
                    .orElse("{}");
        }
        if (value instanceof java.util.List<?> list) {
            return list.stream()
                    .map(this::toJson)
                    .reduce((a, b) -> a + ", " + b)
                    .map(body -> "[" + body + "]")
                    .orElse("[]");
        }
        return "\"" + escapeJson(String.valueOf(value)) + "\"";
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private void appendSkillGuidance(StringBuilder builder, SkillPlan skillPlan, boolean includeOutputContract) {
        if (skillPlan == null) {
            return;
        }
        if (skillPlan.summary() != null && !skillPlan.summary().isBlank()) {
            builder.append("当前技能摘要: ").append(skillPlan.summary()).append("\\n");
        }
        if (skillPlan.executionSteps() != null && !skillPlan.executionSteps().isEmpty()) {
            builder.append("技能执行提示:\\n");
            for (String step : skillPlan.executionSteps()) {
                builder.append("- ").append(step).append("\\n");
            }
        }
        if (includeOutputContract && skillPlan.outputContract() != null && !skillPlan.outputContract().isBlank()) {
            builder.append("技能输出约束: ").append(skillPlan.outputContract()).append("\\n");
        }
        if (skillPlan.rawSkillContent() != null && !skillPlan.rawSkillContent().isBlank()) {
            builder.append("技能原文参考:\\n");
            builder.append(trimSkillContent(skillPlan.rawSkillContent())).append("\\n");
        }
        if (builder.length() > 0) {
            builder.append("\\n");
        }
    }

    private String trimSkillContent(String rawSkillContent) {
        String normalized = rawSkillContent.trim();
        int maxChars = 4000;
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "\\n...(技能内容已截断)";
    }
}
