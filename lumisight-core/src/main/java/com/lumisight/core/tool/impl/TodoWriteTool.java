package com.lumisight.core.tool.impl;

import com.lumisight.core.context.AgentToolInvocationContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.TodoTask;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class TodoWriteTool implements PermissionedAgentTool<TodoWriteTool.Args> {

    private static final Logger log = LoggerFactory.getLogger(TodoWriteTool.class);

    public record Args(
            @ToolArg(
                    description = "带状态的任务列表，状态只能是 pending / in_progress / completed",
                    required = true,
                    exampleJson = "[{\"content\":\"梳理现有实现\",\"status\":\"pending\"},{\"content\":\"修改代码\",\"status\":\"in_progress\"},{\"content\":\"跑编译验证\",\"status\":\"completed\"}]"
            )
            List<TodoTask> todos
    ) {
    }

    private final AgentConversationManager conversationManager;

    public TodoWriteTool(AgentConversationManager conversationManager) {
        this.conversationManager = conversationManager;
    }

    @Override
    public String toolName() {
        return "todo_write";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.PLANNING;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.TODO_WRITE;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "维护当前会话的任务清单。请先列出所有步骤，再逐步把状态从 pending 更新到 in_progress 和 completed。";
    }

    @Override
    public List<String> validateArgs(Args args) {
        List<String> errors = new ArrayList<>();
        if (args == null) {
            errors.add("todos 是必填参数");
            return errors;
        }
        if (args.todos() == null) {
            errors.add("todos 是必填参数");
            return errors;
        }
        for (int i = 0; i < args.todos().size(); i++) {
            TodoTask todo = args.todos().get(i);
            if (todo == null) {
                errors.add("todos[" + i + "] 不能为空");
                continue;
            }
            if (todo.content() == null || todo.content().isBlank()) {
                errors.add("todos[" + i + "].content 不能为空");
            }
            if (!TodoTask.isSupportedStatus(todo.status())) {
                errors.add("todos[" + i + "].status 仅支持 pending / in_progress / completed");
            }
        }
        return errors;
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        AgentToolInvocationContext.Context invocationContext = AgentToolInvocationContext.current();
        String sessionId = invocationContext == null || invocationContext.sessionId() == null || invocationContext.sessionId().isBlank()
                ? "__todo_global__"
                : invocationContext.sessionId().trim();
        int round = invocationContext == null ? 0 : invocationContext.round();
        List<TodoTask> todos = args.todos() == null
                ? List.of()
                : args.todos().stream().map(todo -> new TodoTask(todo.content(), todo.status())).toList();
        conversationManager.updateTodos(sessionId, todos, round);
        String rendered = TodoTask.render(todos);
        log.info("todo_write updated, sessionId={}, round={}, tasks=\n{}", sessionId, round, rendered);
        return List.of(new AgentContextItem(
                "todo_write",
                "current_tasks",
                rendered,
                Map.of(
                        "sessionId", sessionId,
                        "round", round,
                        "count", todos.size(),
                        "todos", todos
                )
        ));
    }
}
