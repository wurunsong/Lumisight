package com.lumisight.core.tool.impl.task;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.task.TaskClaimResult;
import com.lumisight.core.service.task.TaskService;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
public class TaskClaimTool implements PermissionedAgentTool<TaskClaimTool.Args> {

    public record Args(
            @ToolArg(description = "任务 ID", required = true, example = "task_20260605113000_ab12cd34")
            String taskId,
            @ToolArg(description = "认领人，可不填；默认优先使用 userId，再回退到 sessionId", example = "agent-backend")
            String owner
    ) {
    }

    private final TaskService taskService;

    public TaskClaimTool(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String toolName() {
        return "task_claim";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.PLANNING;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.TASK_WRITE;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "认领一个已解锁的持久化任务。只有 pending 且其 blockedBy 全部完成的任务才能 claim。";
    }

    @Override
    public List<String> validateArgs(Args args) {
        if (args == null || !StringUtils.hasText(args.taskId())) {
            return List.of("taskId 是必填参数");
        }
        return List.of();
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        ToolRuntimeScope.Context context = ToolRuntimeScope.required();
        TaskClaimResult result = taskService.claim(context.repoRoot(), args.taskId(), TaskToolSupport.ownerOrDefault(args.owner()));
        return List.of(TaskToolSupport.item(
                "task_claim",
                args.taskId(),
                result.message(),
                Map.of(
                        "taskId", args.taskId(),
                        "claimed", result.claimed(),
                        "blockingDependencies", result.blockingDependencies()
                )
        ));
    }
}
