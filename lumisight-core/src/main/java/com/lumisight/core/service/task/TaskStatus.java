package com.lumisight.core.service.task;

import java.util.Locale;

public enum TaskStatus {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    private final String wireValue;

    TaskStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static TaskStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return PENDING;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "pending" -> PENDING;
            case "in_progress", "in-progress" -> IN_PROGRESS;
            case "completed", "complete" -> COMPLETED;
            default -> throw new IllegalArgumentException("task status 仅支持 pending / in_progress / completed");
        };
    }
}
