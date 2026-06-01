package com.lumisight.core.model;

import java.time.Instant;
import java.util.Map;

public record AgentEvent(
        String type,
        String message,
        String toolName,
        Map<String, Object> payload,
        String traceId,
        String sessionId,
        Integer round,
        String step,
        String status,
        Long timestamp
) {

    public static AgentEvent state(String traceId, String sessionId, Integer round, String step, String status, String message) {
        return base("LOOP_STATE", message, null, Map.of(), traceId, sessionId, round, step, status);
    }

    public static AgentEvent skillSelected(String traceId, String sessionId, String skillName, String planSummary) {
        return base("SKILL_SELECTED", "已选择技能: " + skillName, null, Map.of("skill", skillName, "planSummary", planSummary),
                traceId, sessionId, 0, "SKILL_SELECT", "ok");
    }

    public static AgentEvent skillRouted(String traceId, String sessionId, String candidateSkillId, double confidence, boolean accepted, String reason) {
        return base(
                "SKILL_ROUTE",
                accepted ? "自动路由技能: " + candidateSkillId : "自动路由低置信，回退默认流程",
                null,
                Map.of(
                        "candidateSkillId", candidateSkillId == null ? "" : candidateSkillId,
                        "confidence", confidence,
                        "accepted", accepted,
                        "reason", reason == null ? "" : reason
                ),
                traceId,
                sessionId,
                0,
                "SKILL_ROUTE",
                accepted ? "ok" : "fallback"
        );
    }

    public static AgentEvent plan(String message) {
        return base("PLAN", message, null, Map.of(), "", "", 0, "PLAN", "ok");
    }

    public static AgentEvent plan(String traceId, String sessionId, String message) {
        return base("PLAN", message, null, Map.of(), traceId, sessionId, 0, "PLAN", "ok");
    }

    public static AgentEvent dialogueMode(String mode, String description) {
        return base("DIALOGUE_MODE", description, null, Map.of("mode", mode), "", "", 0, "INIT", "ok");
    }

    public static AgentEvent dialogueMode(String traceId, String sessionId, String mode, String description) {
        return base("DIALOGUE_MODE", description, null, Map.of("mode", mode), traceId, sessionId, 0, "INIT", "ok");
    }

    public static AgentEvent toolCall(String toolName, Map<String, Object> args) {
        return base("TOOL_CALL", "调用工具", toolName, args == null ? Map.of() : args, "", "", 0, "TOOL_CALL", "ok");
    }

    public static AgentEvent toolCall(String traceId, String sessionId, Integer round, String toolName, Map<String, Object> args) {
        return base("TOOL_CALL", "调用工具", toolName, args == null ? Map.of() : args, traceId, sessionId, round, "TOOL_CALL", "ok");
    }

    public static AgentEvent toolResult(String toolName, int size) {
        return base("TOOL_RESULT", "工具返回结果", toolName, Map.of("size", size), "", "", 0, "TOOL_RESULT", "ok");
    }

    public static AgentEvent toolResult(AgentToolExecutionResult result) {
        return base(
                "TOOL_RESULT",
                result.message(),
                result.toolName(),
                Map.of(
                        "status", result.status(),
                        "size", result.items() == null ? 0 : result.items().size(),
                        "metrics", result.metrics() == null ? Map.of() : result.metrics()
                ),
                "",
                "",
                0,
                "TOOL_RESULT",
                "ok"
        );
    }

    public static AgentEvent toolResult(String traceId, String sessionId, Integer round, AgentToolExecutionResult result) {
        return base(
                "TOOL_RESULT",
                result.message(),
                result.toolName(),
                Map.of(
                        "status", result.status(),
                        "size", result.items() == null ? 0 : result.items().size(),
                        "metrics", result.metrics() == null ? Map.of() : result.metrics()
                ),
                traceId,
                sessionId,
                round,
                "TOOL_RESULT",
                "ok"
        );
    }

    public static AgentEvent askUser(String question) {
        return base("ASK_USER", question, null, Map.of(), "", "", 0, "ASK_USER", "waiting_user");
    }

    public static AgentEvent askUser(String question, String sessionId) {
        return base("ASK_USER", question, null, Map.of("sessionId", sessionId == null ? "" : sessionId), "", sessionId, 0, "ASK_USER", "waiting_user");
    }

    public static AgentEvent askUser(String traceId, String sessionId, Integer round, String question) {
        return base("ASK_USER", question, null, Map.of("sessionId", sessionId == null ? "" : sessionId), traceId, sessionId, round, "ASK_USER", "waiting_user");
    }

    public static AgentEvent humanGate(String traceId, String sessionId, Integer round, String toolName, String question) {
        return base("HUMAN_GATE", question, toolName, Map.of("sessionId", sessionId == null ? "" : sessionId), traceId, sessionId, round, "HUMAN_GATE", "waiting_user");
    }

    public static AgentEvent verifyResult(String traceId, String sessionId, Integer round, boolean pass, String reason) {
        return base("VERIFY_RESULT", pass ? "复核通过" : "复核未通过", null, Map.of("pass", pass, "reason", reason), traceId, sessionId, round, "VERIFY", pass ? "ok" : "retry");
    }

    public static AgentEvent token(String content) {
        return base("TOKEN", content, null, Map.of(), "", "", 0, "FINAL_STREAM", "streaming");
    }

    public static AgentEvent token(String traceId, String sessionId, String content) {
        return base("TOKEN", content, null, Map.of(), traceId, sessionId, 0, "FINAL_STREAM", "streaming");
    }

    public static AgentEvent finalText(String content) {
        return base("FINAL", content, null, Map.of(), "", "", 0, "FINAL", "ok");
    }

    public static AgentEvent finalText(String traceId, String sessionId, String content) {
        return base("FINAL", content, null, Map.of(), traceId, sessionId, 0, "FINAL", "ok");
    }

    public static AgentEvent error(String message) {
        return base("ERROR", message, null, Map.of(), "", "", 0, "ERROR", "error");
    }

    public static AgentEvent interrupted(String sessionId) {
        return base("INTERRUPTED", "会话已中断，可稍后恢复", null, Map.of("sessionId", sessionId == null ? "" : sessionId), "", sessionId, 0, "INTERRUPT", "interrupted");
    }

    public static AgentEvent interrupted(String traceId, String sessionId, Integer round) {
        return base("INTERRUPTED", "会话已中断，可稍后恢复", null, Map.of("sessionId", sessionId == null ? "" : sessionId), traceId, sessionId, round, "INTERRUPT", "interrupted");
    }

    public static AgentEvent resumed(String sessionId) {
        return base("RESUMED", "会话已恢复，继续执行", null, Map.of("sessionId", sessionId == null ? "" : sessionId), "", sessionId, 0, "RESUME", "ok");
    }

    public static AgentEvent resumed(String traceId, String sessionId) {
        return base("RESUMED", "会话已恢复，继续执行", null, Map.of("sessionId", sessionId == null ? "" : sessionId), traceId, sessionId, 0, "RESUME", "ok");
    }

    private static AgentEvent base(
            String type,
            String message,
            String toolName,
            Map<String, Object> payload,
            String traceId,
            String sessionId,
            Integer round,
            String step,
            String status
    ) {
        return new AgentEvent(type, message, toolName, payload, traceId, sessionId, round, step, status, Instant.now().toEpochMilli());
    }
}
