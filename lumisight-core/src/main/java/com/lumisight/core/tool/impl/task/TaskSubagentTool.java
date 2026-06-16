package com.lumisight.core.tool.impl.task;

import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.TaskContextEnvelope;
import com.lumisight.core.agent.multiagent.port.SubAgentLauncher;
import com.lumisight.core.context.ambient.ToolInvocationScope;
import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class TaskSubagentTool implements PermissionedAgentTool<TaskSubagentTool.Args> {

    public record Args(
            @ToolArg(description = "子任务描述，明确告诉子 Agent 只需要完成哪一段分析", required = true, example = "定位 OrderService 为什么会触发空指针，并总结根因")
            String description,
            @ToolArg(description = "子 Agent 能力类型", example = "BUG_FIX")
            String capability,
            @ToolArg(description = "补充上下文提示，可选", example = "优先检查 payment / order 相关模块")
            String contextHint,
            @ToolArg(description = "期望输出格式提示，可选", example = "返回根因、证据和建议下一步")
            String expectedOutput
    ) {
    }

    private final SubAgentLauncher subAgentLauncher;

    public TaskSubagentTool(SubAgentLauncher subAgentLauncher) {
        this.subAgentLauncher = subAgentLauncher;
    }

    @Override
    public String toolName() {
        return "task_subagent";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.PLANNING;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.AGENT_SPAWN;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "启动一个干净上下文的只读子 Agent 去完成局部复杂分析。子 Agent 不会继承完整对话历史，也不能递归创建新的 Agent。";
    }

    @Override
    public List<String> validateArgs(Args args) {
        if (args == null || !StringUtils.hasText(args.description())) {
            return List.of("description 是必填参数");
        }
        if (StringUtils.hasText(args.capability())) {
            try {
                parseCapability(args.capability());
            } catch (Exception e) {
                return List.of("capability 非法，需为 " + List.of(SubAgentCapability.values()));
            }
        }
        return List.of();
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        ToolRuntimeScope.Context runtimeContext = ToolRuntimeScope.required();
        ToolInvocationScope.Context invocationContext = ToolInvocationScope.current();
        String parentSessionId = invocationContext == null || invocationContext.sessionId() == null
                ? "lead"
                : invocationContext.sessionId();
        TaskContextEnvelope envelope = new TaskContextEnvelope(
                "subtask-" + UUID.randomUUID().toString().substring(0, 8),
                args.description(),
                mergeInstruction(args.description(), args.contextHint()),
                StringUtils.hasText(args.capability()) ? parseCapability(args.capability()) : defaultCapability(),
                runtimeContext.repoRoot(),
                List.of(),
                Map.of("allowWrite", false, "allowSpawn", false),
                StringUtils.hasText(args.expectedOutput()) ? args.expectedOutput() : "返回结构化结论、证据和建议下一步",
                Map.of("contextLimit", defaultLimit)
        );
        SubAgentResult result = subAgentLauncher.execute(envelope, parentSessionId);
        return List.of(new AgentContextItem(
                "subagent",
                envelope.taskId(),
                result.summary(),
                Map.of(
                        "taskId", result.taskId(),
                        "agentName", result.agentName(),
                        "success", result.success(),
                        "findings", result.findings(),
                        "evidenceRefs", result.evidenceRefs(),
                        "suggestedActions", result.suggestedActions(),
                        "confidence", result.confidence(),
                        "payload", result.payload(),
                        "logicalResourceId", "subagent:" + result.taskId()
                )
        ));
    }

    private SubAgentCapability parseCapability(String raw) {
        return SubAgentCapability.valueOf(raw.trim().toUpperCase());
    }

    private SubAgentCapability defaultCapability() {
        return SubAgentCapability.CODE_EXPLAIN;
    }

    private String mergeInstruction(String description, String contextHint) {
        if (!StringUtils.hasText(contextHint)) {
            return description;
        }
        return description + "\nContext Hint: " + contextHint.trim();
    }
}
