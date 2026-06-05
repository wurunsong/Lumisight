package com.lumisight.core.tool.impl.task;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.task.TaskProperties;
import com.lumisight.core.service.task.TaskService;
import com.lumisight.core.service.task.TaskView;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TaskListTool implements PermissionedAgentTool<TaskListTool.Args> {

    public record Args(
            @ToolArg(description = "最多返回多少条任务", example = "20")
            Integer limit,
            @ToolArg(description = "按状态过滤：pending / in_progress / completed", example = "pending")
            String status
    ) {
    }

    private final TaskService taskService;
    private final TaskProperties taskProperties;

    public TaskListTool(TaskService taskService, TaskProperties taskProperties) {
        this.taskService = taskService;
        this.taskProperties = taskProperties;
    }

    @Override
    public String toolName() {
        return "task_list";
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
        return "列出当前仓库的持久化任务及其状态、依赖和是否已解锁。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        int requestedLimit = args == null || args.limit() == null ? 20 : args.limit();
        int limit = Math.max(1, Math.min(taskProperties.getMaxListLimit(), requestedLimit));
        List<TaskView> tasks = taskService.list(context.repoRoot()).stream()
                .filter(view -> TaskToolSupport.matchesStatus(view, args == null ? null : args.status()))
                .limit(limit)
                .toList();
        return List.of(TaskToolSupport.item(
                "task_list",
                "task_index",
                TaskToolSupport.renderTaskList(tasks),
                Map.of("count", tasks.size(), "limit", limit)
        ));
    }
}
