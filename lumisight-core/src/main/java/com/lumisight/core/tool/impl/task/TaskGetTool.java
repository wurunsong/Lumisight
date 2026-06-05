package com.lumisight.core.tool.impl.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.task.TaskService;
import com.lumisight.core.service.task.TaskView;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
public class TaskGetTool implements PermissionedAgentTool<TaskGetTool.Args> {

    public record Args(
            @ToolArg(description = "任务 ID", required = true, example = "task_20260605113000_ab12cd34")
            String taskId
    ) {
    }

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    public TaskGetTool(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String toolName() {
        return "task_get";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.PLANNING;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.TASK_READ;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "查看单个持久化任务的完整 JSON，包括描述、状态、owner、blockedBy、blocks 和当前是否可开始。";
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
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        TaskView view = taskService.get(context.repoRoot(), args.taskId());
        return List.of(TaskToolSupport.item(
                "task_get",
                view.task().id(),
                TaskToolSupport.renderTaskJson(objectMapper, view),
                Map.of("taskId", view.task().id(), "status", view.task().status().wireValue(), "canStart", view.canStart())
        ));
    }
}
