package com.lumisight.core.tool.impl.task;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.task.TaskResumeResult;
import com.lumisight.core.service.task.TaskService;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TaskResumeTool implements PermissionedAgentTool<TaskResumeTool.Args> {

    public record Args(
            @ToolArg(description = "续跑人，可不填；默认优先使用 userId，再回退到 sessionId", example = "agent-backend")
            String owner,
            @ToolArg(description = "是否自动认领找到的可执行任务", example = "true")
            Boolean autoClaim,
            @ToolArg(description = "最多返回多少个任务", example = "5")
            Integer limit
    ) {
    }

    private final TaskService taskService;

    public TaskResumeTool(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String toolName() {
        return "task_resume";
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
        return "自动续跑持久化任务系统：先找当前 owner 已在进行中的任务；如果没有，再找已解锁的 pending 任务，并可选择自动 claim。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        ToolRuntimeScope.Context context = ToolRuntimeScope.required();
        int limit = args == null || args.limit() == null ? 5 : args.limit();
        TaskResumeResult result = taskService.resume(
                context.repoRoot(),
                TaskToolSupport.ownerOrDefault(args == null ? null : args.owner()),
                args != null && Boolean.TRUE.equals(args.autoClaim()),
                limit
        );
        return List.of(TaskToolSupport.item(
                "task_resume",
                "task_resume",
                TaskToolSupport.renderTaskResume(result),
                Map.of(
                        "autoClaimed", result.autoClaimed(),
                        "activeCount", result.activeTasks().size(),
                        "readyCount", result.readyTasks().size(),
                        "allCompleted", result.allCompleted()
                )
        ));
    }
}
