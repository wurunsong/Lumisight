package com.lumisight.core.tool.impl.task;

import com.lumisight.core.context.ambient.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.task.TaskBoardView;
import com.lumisight.core.service.task.TaskService;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TaskBoardTool implements PermissionedAgentTool<TaskBoardTool.Args> {

    public record Args() {
    }

    private final TaskService taskService;

    public TaskBoardTool(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public String toolName() {
        return "task_board";
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
        return "查看当前仓库持久化任务的任务看板，按 ready / in_progress / blocked / completed 分组，并给出整体完成判定。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        TaskBoardView board = taskService.board(context.repoRoot());
        return List.of(TaskToolSupport.item(
                "task_board",
                "task_board",
                TaskToolSupport.renderTaskBoard(board),
                Map.of(
                        "total", board.total(),
                        "ready", board.ready(),
                        "inProgress", board.inProgress(),
                        "blocked", board.blocked(),
                        "completed", board.completed(),
                        "allCompleted", board.allCompleted()
                )
        ));
    }
}
