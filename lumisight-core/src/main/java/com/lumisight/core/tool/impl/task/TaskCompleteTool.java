package com.lumisight.core.tool.impl.task;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.task.TaskCompleteResult;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import com.lumisight.core.service.task.TaskService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
public class TaskCompleteTool implements PermissionedAgentTool<TaskCompleteTool.Args> {

    public record Args(
            @ToolArg(description = "任务 ID", required = true, example = "task_20260605113000_ab12cd34")
            String taskId
    ) {
    }

    private final TaskService taskService;

    public TaskCompleteTool(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String toolName() {
        return "task_complete";
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
        return "将一个持久化任务标记为 completed，并返回因此被解锁的下游任务。";
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
        TaskCompleteResult result = taskService.complete(context.repoRoot(), args.taskId());
        return List.of(TaskToolSupport.item(
                "task_complete",
                result.task().id(),
                result.message(),
                Map.of(
                        "taskId", result.task().id(),
                        "status", result.task().status().wireValue(),
                        "unblocked", result.unblocked().stream().map(view -> view.task().id()).toList(),
                        "readyTasks", result.readyTasks().stream().map(view -> view.task().id()).toList(),
                        "allCompleted", result.allCompleted()
                )
        ));
    }
}
