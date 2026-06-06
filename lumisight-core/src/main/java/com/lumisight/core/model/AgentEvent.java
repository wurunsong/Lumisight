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

    public static AgentEvent contextCompression(
            String traceId,
            String sessionId,
            Integer round,
            String purpose,
            Map<String, Object> payload
    ) {
        return base(
                "CONTEXT_COMPRESSION",
                "上下文已执行压缩/投影",
                null,
                payload == null ? Map.of("purpose", purpose) : mergePayload(payload, purpose),
                traceId,
                sessionId,
                round,
                "CONTEXT_COMPRESSION",
                "ok"
        );
    }

    public static AgentEvent multiAgentSelected(String traceId, String sessionId, Integer round, String mode, String reason) {
        return multiAgentSelected(traceId, sessionId, round, mode, reason, Map.of());
    }

    public static AgentEvent multiAgentSelected(
            String traceId,
            String sessionId,
            Integer round,
            String mode,
            String reason,
            Map<String, Object> payload
    ) {
        Map<String, Object> nextPayload = new java.util.LinkedHashMap<>();
        nextPayload.put("mode", mode == null ? "" : mode);
        nextPayload.put("reason", reason == null ? "" : reason);
        if (payload != null && !payload.isEmpty()) {
            nextPayload.putAll(payload);
        }
        return base(
                "MULTI_AGENT_SELECTED",
                "多 Agent 路径已选择",
                null,
                Map.copyOf(nextPayload),
                traceId,
                sessionId,
                round,
                "MULTI_AGENT",
                "ok"
        );
    }

    public static AgentEvent orchestrationPlan(String traceId, String sessionId, Integer round, Map<String, Object> payload) {
        return base(
                "ORCHESTRATION_PLAN",
                "已生成多 Agent 任务图",
                null,
                payload == null ? Map.of() : payload,
                traceId,
                sessionId,
                round,
                "MULTI_AGENT",
                "ok"
        );
    }

    public static AgentEvent subagentSpawned(String traceId, String sessionId, Integer round, String taskId, String capability) {
        return base(
                "SUBAGENT_SPAWNED",
                "子 Agent 已启动",
                null,
                Map.of("taskId", taskId == null ? "" : taskId, "capability", capability == null ? "" : capability),
                traceId,
                sessionId,
                round,
                "MULTI_AGENT",
                "running"
        );
    }

    public static AgentEvent subagentResult(String traceId, String sessionId, Integer round, String taskId, boolean success, String summary) {
        return subagentResult(traceId, sessionId, round, taskId, success, summary, Map.of());
    }

    public static AgentEvent subagentResult(
            String traceId,
            String sessionId,
            Integer round,
            String taskId,
            boolean success,
            String summary,
            Map<String, Object> payload
    ) {
        Map<String, Object> nextPayload = new java.util.LinkedHashMap<>();
        nextPayload.put("taskId", taskId == null ? "" : taskId);
        nextPayload.put("success", success);
        if (payload != null && !payload.isEmpty()) {
            nextPayload.putAll(payload);
        }
        return base(
                "SUBAGENT_RESULT",
                summary == null ? "" : summary,
                null,
                Map.copyOf(nextPayload),
                traceId,
                sessionId,
                round,
                "MULTI_AGENT",
                success ? "ok" : "error"
        );
    }

    public static AgentEvent multiAgentTaskStatus(
            String traceId,
            String sessionId,
            Integer round,
            String taskId,
            String status,
            Map<String, Object> payload
    ) {
        return base(
                "MULTI_AGENT_TASK_STATUS",
                "多 Agent 任务状态更新",
                null,
                payload == null ? mergeTaskPayload(Map.of(), taskId, status) : mergeTaskPayload(payload, taskId, status),
                traceId,
                sessionId,
                round,
                "MULTI_AGENT",
                status == null ? "ok" : status.toLowerCase()
        );
    }

    public static AgentEvent teamAgentLifecycle(
            String traceId,
            String sessionId,
            Integer round,
            String agentId,
            String action,
            Map<String, Object> payload
    ) {
        return base(
                "TEAM_AGENT_LIFECYCLE",
                "Team Agent 生命周期事件",
                null,
                payload == null ? mergeLifecyclePayload(Map.of(), agentId, action) : mergeLifecyclePayload(payload, agentId, action),
                traceId,
                sessionId,
                round,
                "MULTI_AGENT",
                "ok"
        );
    }

    public static AgentEvent multiAgentFallback(String traceId, String sessionId, Integer round, String reason) {
        return base(
                "MULTI_AGENT_FALLBACK",
                "多 Agent 已回退到主 Agent",
                null,
                Map.of("reason", reason == null ? "" : reason),
                traceId,
                sessionId,
                round,
                "MULTI_AGENT",
                "fallback"
        );
    }

    public static AgentEvent token(String content) {
        return base("TOKEN", content, null, Map.of(), "", "", 0, "FINAL_STREAM", "streaming");
    }

    public static AgentEvent token(String traceId, String sessionId, String content) {
        return base("TOKEN", content, null, Map.of(), traceId, sessionId, 0, "FINAL_STREAM", "streaming");
    }

    public static AgentEvent token(String traceId, String sessionId, Integer round, String content) {
        return base("TOKEN", content, null, Map.of(), traceId, sessionId, round, "FINAL_STREAM", "streaming");
    }

    public static AgentEvent finalText(String content) {
        return base("FINAL", content, null, Map.of(), "", "", 0, "FINAL", "ok");
    }

    public static AgentEvent finalText(String traceId, String sessionId, String content) {
        return base("FINAL", content, null, Map.of(), traceId, sessionId, 0, "FINAL", "ok");
    }

    public static AgentEvent finalText(String traceId, String sessionId, Integer round, String content) {
        return base("FINAL", content, null, Map.of(), traceId, sessionId, round, "FINAL", "ok");
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

    private static Map<String, Object> mergePayload(Map<String, Object> payload, String purpose) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        merged.put("purpose", purpose);
        merged.putAll(payload);
        return merged;
    }

    private static Map<String, Object> mergeTaskPayload(Map<String, Object> payload, String taskId, String status) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        merged.put("taskId", taskId == null ? "" : taskId);
        merged.put("status", status == null ? "" : status);
        merged.putAll(payload);
        return merged;
    }

    private static Map<String, Object> mergeLifecyclePayload(Map<String, Object> payload, String agentId, String action) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>();
        merged.put("agentId", agentId == null ? "" : agentId);
        merged.put("action", action == null ? "" : action);
        merged.putAll(payload);
        return merged;
    }
}
