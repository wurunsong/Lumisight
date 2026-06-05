package com.lumisight.core.tool.impl.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.context.AgentToolInvocationContext;
import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.task.TaskRecord;
import com.lumisight.core.service.task.TaskStatus;
import com.lumisight.core.service.task.TaskView;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

final class TaskToolSupport {

    private TaskToolSupport() {
    }

    static String ownerOrDefault(String requestedOwner) {
        if (StringUtils.hasText(requestedOwner)) {
            return requestedOwner.trim();
        }
        AgentToolRuntimeContext.Context runtime = AgentToolRuntimeContext.current();
        if (runtime != null && StringUtils.hasText(runtime.userId())) {
            return runtime.userId().trim();
        }
        AgentToolInvocationContext.Context invocation = AgentToolInvocationContext.current();
        if (invocation != null && StringUtils.hasText(invocation.sessionId())) {
            return invocation.sessionId().trim();
        }
        return "agent";
    }

    static AgentContextItem item(String sourceType, String sourceId, String content, Map<String, Object> metadata) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        merged.putIfAbsent("logicalResourceId", "task:" + sourceId);
        return new AgentContextItem(sourceType, sourceId, content, Map.copyOf(merged));
    }

    static String renderTaskList(List<TaskView> views) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("## Tasks");
        if (views == null || views.isEmpty()) {
            joiner.add("- 无");
            return joiner.toString();
        }
        for (TaskView view : views) {
            TaskRecord task = view.task();
            String statusIcon = switch (task.status()) {
                case IN_PROGRESS -> "▸";
                case COMPLETED -> "✓";
                case PENDING -> " ";
            };
            String owner = task.owner() == null ? "-" : task.owner();
            String blocked = view.blockingDependencies().isEmpty()
                    ? (view.canStart() ? "ready" : "blocked")
                    : "blocked by " + view.blockingDependencies();
            joiner.add("- [%s] %s %s owner=%s %s".formatted(statusIcon, task.id(), task.subject(), owner, blocked));
        }
        return joiner.toString();
    }

    static String renderTaskJson(ObjectMapper objectMapper, TaskView view) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", view.task());
        payload.put("canStart", view.canStart());
        payload.put("blockingDependencies", view.blockingDependencies());
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to render task json", e);
        }
    }

    static boolean matchesStatus(TaskView view, String status) {
        if (!StringUtils.hasText(status)) {
            return true;
        }
        return view.task().status() == TaskStatus.parse(status);
    }
}
