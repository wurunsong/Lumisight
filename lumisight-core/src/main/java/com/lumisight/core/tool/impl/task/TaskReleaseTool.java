package com.lumisight.core.tool.impl.task;

import com.lumisight.core.context.ambient.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.task.TaskReleaseResult;
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
public class TaskReleaseTool implements PermissionedAgentTool<TaskReleaseTool.Args> {

    public record Args(
            @ToolArg(description = "任务 ID", required = true, example = "task_20260605113000_ab12cd34")
            String taskId,
            @ToolArg(description = "释放任务的 owner，可不填；默认优先使用 userId，再回退到 sessionId", example = "agent-backend")
            String owner
    ) {
    }

    private final TaskService taskService;

    public TaskReleaseTool(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String toolName() {
        return "task_release";
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
        return "释放一个已认领的持久化任务，把它从 in_progress 回退到 pending。适合会话中断、需要换人接手或暂时撤回 claim 的场景。";
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
        TaskReleaseResult result = taskService.release(context.repoRoot(), args.taskId(), TaskToolSupport.ownerOrDefault(args.owner()));
        return List.of(TaskToolSupport.item(
                "task_release",
                args.taskId(),
                result.message(),
                Map.of(
                        "taskId", args.taskId(),
                        "released", result.released(),
                        "status", result.view() == null ? "unknown" : result.view().task().status().wireValue()
                )
        ));
    }
}
