package com.lumisight.core.model;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

public record TodoTask(
        String content,
        String status
) {

    public static final String PENDING = "pending";
    public static final String IN_PROGRESS = "in_progress";
    public static final String COMPLETED = "completed";

    public TodoTask {
        content = content == null ? "" : content.trim();
        status = normalizeStatus(status);
    }

    public static boolean isSupportedStatus(String status) {
        String normalized = normalizeStatus(status);
        return PENDING.equals(normalized) || IN_PROGRESS.equals(normalized) || COMPLETED.equals(normalized);
    }

    public static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return PENDING;
        }
        return status.trim().toLowerCase(Locale.ROOT);
    }

    public static String render(List<TodoTask> todos) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("## Current Tasks");
        if (todos == null || todos.isEmpty()) {
            joiner.add("  [ ] 无");
            return joiner.toString();
        }
        for (TodoTask todo : todos) {
            String icon = switch (todo.status()) {
                case IN_PROGRESS -> "▸";
                case COMPLETED -> "✓";
                default -> " ";
            };
            joiner.add("  [" + icon + "] " + safeContent(todo.content()));
        }
        return joiner.toString();
    }

    private static String safeContent(String content) {
        return content == null ? "" : content.trim();
    }
}
