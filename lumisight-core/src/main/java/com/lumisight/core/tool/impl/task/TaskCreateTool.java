package com.lumisight.core.tool.impl.task;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.task.TaskCreateRequest;
import com.lumisight.core.service.task.TaskRecord;
import com.lumisight.core.service.task.TaskService;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TaskCreateTool implements PermissionedAgentTool<TaskCreateTool.Args> {

    public record Args(
            @ToolArg(description = "任务标题，简短说明要做什么", required = true, example = "setup database schema")
            String subject,
            @ToolArg(description = "任务详细说明，可为空", example = "创建 users / sessions 表并落 migration")
            String description,
            @ToolArg(description = "依赖的上游任务 ID 列表", exampleJson = "[\"task_20260605113000_ab12cd34\"]")
            List<String> blockedBy
    ) {
    }

    private final TaskService taskService;

    public TaskCreateTool(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String toolName() {
        return "task_create";
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
        return "创建一个持久化任务，保存到 repoRoot/.tasks 下。适合跨会话、带依赖关系的长期任务，而不是当前会话内的临时 checklist。";
    }

    @Override
    public List<String> validateArgs(Args args) {
        List<String> errors = new ArrayList<>();
        if (args == null) {
            errors.add("task args is required");
            return errors;
        }
        if (!StringUtils.hasText(args.subject())) {
            errors.add("subject 是必填参数");
        }
        return errors;
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        TaskRecord task = taskService.create(
                context.repoRoot(),
                new TaskCreateRequest(
                        args.subject(),
                        args.description(),
                        args.blockedBy(),
                        Map.of("createdByTool", toolName())
                )
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("taskId", task.id());
        metadata.put("status", task.status().wireValue());
        metadata.put("blockedBy", task.blockedBy());
        return List.of(TaskToolSupport.item(
                "task_create",
                task.id(),
                "Created %s (%s)".formatted(task.id(), task.subject()),
                metadata
        ));
    }
}
