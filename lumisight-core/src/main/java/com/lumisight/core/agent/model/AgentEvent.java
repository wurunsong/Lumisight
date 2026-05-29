package com.lumisight.core.agent.model;

import java.util.Map;

public record AgentEvent(
        String type,
        String message,
        String toolName,
        Map<String, Object> payload
) {

    public static AgentEvent plan(String message) {
        return new AgentEvent("PLAN", message, null, Map.of());
    }

    public static AgentEvent toolCall(String toolName, Map<String, Object> args) {
        return new AgentEvent("TOOL_CALL", "调用工具", toolName, args == null ? Map.of() : args);
    }

    public static AgentEvent toolResult(String toolName, int size) {
        return new AgentEvent("TOOL_RESULT", "工具返回结果", toolName, Map.of("size", size));
    }

    public static AgentEvent askUser(String question) {
        return new AgentEvent("ASK_USER", question, null, Map.of());
    }

    public static AgentEvent token(String content) {
        return new AgentEvent("TOKEN", content, null, Map.of());
    }

    public static AgentEvent finalText(String content) {
        return new AgentEvent("FINAL", content, null, Map.of());
    }

    public static AgentEvent error(String message) {
        return new AgentEvent("ERROR", message, null, Map.of());
    }
}
