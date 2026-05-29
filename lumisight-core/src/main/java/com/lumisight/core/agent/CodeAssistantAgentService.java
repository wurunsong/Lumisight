package com.lumisight.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.agent.model.AgentToolExecutionResult;
import com.lumisight.core.agent.model.AgentEvent;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.AgentLoopState;
import com.lumisight.core.agent.model.AgentRequest;
import com.lumisight.core.agent.model.AgentRunMode;
import com.lumisight.core.agent.model.ToolDecision;
import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.support.AgentConversationManager;
import com.lumisight.core.agent.support.AgentPromptService;
import com.lumisight.core.agent.support.AgentRequestValidators;
import com.lumisight.core.agent.support.ToolSchemaValidator;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.AgentToolRegistry;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import com.lumisight.hooks.AgentHookContext;
import com.lumisight.hooks.AgentHookDispatcher;
import com.lumisight.hooks.AgentHookPoint;
import com.lumisight.skills.runtime.AgentSkill;
import com.lumisight.skills.runtime.SkillContext;
import com.lumisight.skills.runtime.SkillPlan;
import com.lumisight.skills.runtime.SkillRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CodeAssistantAgentService {

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int DEFAULT_CONTEXT_LIMIT = 5;
    private static final Set<AgentToolPermission> DEFAULT_RAG_TOOL_PERMISSIONS = EnumSet.of(
            AgentToolPermission.HYBRID_VECTOR_READ,
            AgentToolPermission.MCP_CAPABILITY_CALL
    );

    private final ChatClient llmChatClient;
    private final AgentToolRegistry agentToolRegistry;
    private final AgentPromptService agentPromptService;
    private final AgentConversationManager conversationManager;
    private final AgentHookDispatcher agentHookDispatcher;
    private final SkillRegistry skillRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public CodeAssistantAgentService(
            ChatClient.Builder chatClientBuilder,
            AgentToolRegistry agentToolRegistry,
            AgentPromptService agentPromptService,
            AgentConversationManager conversationManager,
            AgentHookDispatcher agentHookDispatcher,
            SkillRegistry skillRegistry
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.agentToolRegistry = agentToolRegistry;
        this.agentPromptService = agentPromptService;
        this.conversationManager = conversationManager;
        this.agentHookDispatcher = agentHookDispatcher;
        this.skillRegistry = skillRegistry;
    }

    public Flux<AgentEvent> run(AgentRequest request) {
        AgentRequestValidators.validate(request);

        String traceId = UUID.randomUUID().toString();
        String sessionId = StringUtils.hasText(request.sessionId()) ? request.sessionId() : UUID.randomUUID().toString();
        if (request.interrupt()) {
            conversationManager.interrupt(sessionId);
            return Flux.just(AgentEvent.interrupted(traceId, sessionId, 0));
        }

        AgentConversationManager.ConversationState resumeState = conversationManager.get(sessionId);
        String effectiveQuestion = request.question();
        int limit = request.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : request.contextLimit();
        List<AgentContextItem> contexts = new ArrayList<>();
        List<AgentEvent> events = new ArrayList<>();
        String directAnswer = null;
        boolean askUser = false;
        int startRound = 1;

        if (request.resume() && resumeState != null) {
            if (resumeState.interrupted()) {
                resumeState = new AgentConversationManager.ConversationState(
                        resumeState.status(),
                        resumeState.baseQuestion(),
                        resumeState.contexts(),
                        resumeState.nextRound(),
                        false
                );
            }
            contexts.addAll(resumeState.contexts());
            startRound = resumeState.nextRound();
            if (StringUtils.hasText(request.followUpAnswer())) {
                effectiveQuestion = resumeState.baseQuestion() + "\n用户补充信息: " + request.followUpAnswer();
            } else if (StringUtils.hasText(resumeState.baseQuestion())) {
                effectiveQuestion = resumeState.baseQuestion();
            }
            events.add(AgentEvent.resumed(traceId, sessionId));
        }

        try (AgentToolRuntimeContext.Scope ignored = AgentToolRuntimeContext.open(request.repoRoot(), limit)) {
            events.add(AgentEvent.state(traceId, sessionId, 0, AgentLoopState.INIT.name(), "ok", "Agent启动"));
            events.add(AgentEvent.dialogueMode(traceId, sessionId, request.dialogueMode().name(), dialogueModeDescription(request.dialogueMode().name())));
            AgentSkill skill = skillRegistry.select(new SkillContext(
                    request.taskType().name(),
                    request.dialogueMode().name(),
                    request.runMode().name(),
                    effectiveQuestion,
                    Map.of("repoRoot", request.repoRoot())
            ));
            SkillPlan skillPlan = skill.buildPlan(new SkillContext(
                    request.taskType().name(),
                    request.dialogueMode().name(),
                    request.runMode().name(),
                    effectiveQuestion,
                    Map.of("repoRoot", request.repoRoot())
            ));
            events.add(AgentEvent.skillSelected(traceId, sessionId, skill.skillName(), skillPlan.summary()));
            if (request.runMode() == AgentRunMode.PLAN) {
                events.add(AgentEvent.state(traceId, sessionId, 0, AgentLoopState.PLAN.name(), "running", "开始生成计划"));
                fireHook(AgentHookPoint.BEFORE_PLAN, sessionId, 0, effectiveQuestion, null, Map.of());
                String plan = llmChatClient.prompt()
                        .system(agentPromptService.systemPrompt(request.taskType()))
                        .user(agentPromptService.planPrompt(request))
                        .call()
                        .content();
                events.add(AgentEvent.plan(traceId, sessionId, plan));
                fireHook(AgentHookPoint.AFTER_PLAN, sessionId, 0, effectiveQuestion, null, Map.of("plan", plan));
            }
            OrchestrationResult result = runManualOrchestration(request, effectiveQuestion, contexts, limit, startRound, events, sessionId, traceId);
            directAnswer = result.directAnswer();
            askUser = result.askUser();
        }
        if (askUser) {
            return Flux.fromIterable(events);
        }
        conversationManager.clear(sessionId);
        if (StringUtils.hasText(directAnswer)) {
            fireHook(AgentHookPoint.BEFORE_FINAL, sessionId, 0, effectiveQuestion, null, Map.of("directAnswer", true));
            events.add(AgentEvent.state(traceId, sessionId, 0, AgentLoopState.FINAL.name(), "ok", "直接输出最终结果"));
            events.add(AgentEvent.finalText(traceId, sessionId, directAnswer));
            return Flux.fromIterable(events);
        }
        AgentRequest finalRequest = withQuestion(request, effectiveQuestion, sessionId);
        String finalPrompt = agentPromptService.buildFinalAnswerPrompt(finalRequest, contexts, limit);
        fireHook(AgentHookPoint.BEFORE_FINAL, sessionId, 0, effectiveQuestion, null, Map.of("directAnswer", false));
        Flux<AgentEvent> stream = llmChatClient.prompt()
                .system(agentPromptService.systemPrompt(request.taskType()))
                .user(finalPrompt)
                .stream()
                .content()
                .map(content -> AgentEvent.token(traceId, sessionId, content));
        return Flux.concat(Flux.fromIterable(events), stream);
    }

    public Flux<String> runText(AgentRequest request) {
        return run(request)
                .filter(event -> "TOKEN".equals(event.type())
                        || "FINAL".equals(event.type())
                        || "ASK_USER".equals(event.type())
                        || "INTERRUPTED".equals(event.type())
                        || "RESUMED".equals(event.type()))
                .map(AgentEvent::message);
    }

    private OrchestrationResult runManualOrchestration(
            AgentRequest request,
            String effectiveQuestion,
            List<AgentContextItem> contexts,
            int limit,
            int startRound,
            List<AgentEvent> events,
            String sessionId,
            String traceId
    ) {
        Set<AgentToolPermission> enabledPermissions = enabledPermissions(request);
        for (int round = startRound; round <= MAX_TOOL_ROUNDS; round++) {
            AgentConversationManager.ConversationState state = conversationManager.get(sessionId);
            if (state != null && state.interrupted()) {
                events.add(AgentEvent.state(traceId, sessionId, round, AgentLoopState.INTERRUPTED.name(), "interrupted", "会话中断"));
                events.add(AgentEvent.interrupted(traceId, sessionId, round));
                conversationManager.saveRunning(sessionId, effectiveQuestion, contexts, round);
                return new OrchestrationResult(null, true);
            }
            events.add(AgentEvent.state(traceId, sessionId, round, AgentLoopState.DECIDE.name(), "running", "开始决策"));
            fireHook(AgentHookPoint.BEFORE_DECISION, sessionId, round, effectiveQuestion, null, Map.of("contextSize", contexts.size()));
            String decisionRaw = llmChatClient.prompt()
                    .system(agentPromptService.orchestratorSystemPrompt(
                            request.taskType(),
                            request.dialogueMode(),
                            enabledPermissions,
                            agentToolRegistry
                    ))
                    .user(agentPromptService.orchestratorUserPrompt(
                            withQuestion(request, effectiveQuestion, sessionId),
                            contexts,
                            limit,
                            round,
                            MAX_TOOL_ROUNDS
                    ))
                    .call()
                    .content();
            fireHook(AgentHookPoint.AFTER_DECISION, sessionId, round, effectiveQuestion, null, Map.of("decisionRaw", decisionRaw));
            ToolDecision decision = parseDecision(decisionRaw);
            if ("ask_user".equalsIgnoreCase(decision.action())) {
                String question = StringUtils.hasText(decision.askUserQuestion()) ? decision.askUserQuestion() : "我还需要你补充一些信息，才能继续。";
                conversationManager.saveWaiting(sessionId, effectiveQuestion, contexts, round);
                events.add(AgentEvent.state(traceId, sessionId, round, AgentLoopState.ASK_USER.name(), "waiting_user", "等待用户补充信息"));
                events.add(AgentEvent.askUser(traceId, sessionId, round, question));
                fireHook(AgentHookPoint.ON_ASK_USER, sessionId, round, effectiveQuestion, null, Map.of("askUserQuestion", question));
                return new OrchestrationResult(null, true);
            }
            if ("final".equalsIgnoreCase(decision.action())) {
                if (StringUtils.hasText(decision.finalAnswer())) {
                    events.add(AgentEvent.state(traceId, sessionId, round, AgentLoopState.FINAL.name(), "ok", "决策直接给出最终答案"));
                    return new OrchestrationResult(decision.finalAnswer(), false);
                }
                break;
            }
            if (!"tool".equalsIgnoreCase(decision.action())) {
                break;
            }
            events.add(AgentEvent.state(traceId, sessionId, round, AgentLoopState.TOOL_CALL.name(), "running", "开始工具调用"));
            events.add(AgentEvent.toolCall(traceId, sessionId, round, decision.toolName(), decision.args()));
            fireHook(AgentHookPoint.BEFORE_TOOL_CALL, sessionId, round, effectiveQuestion, decision.toolName(), decision.args() == null ? Map.of() : decision.args());
            AgentToolExecutionResult toolResult = executeTool(decision, enabledPermissions, limit);
            events.add(AgentEvent.toolResult(traceId, sessionId, round, toolResult));
            fireHook(AgentHookPoint.AFTER_TOOL_CALL, sessionId, round, effectiveQuestion, decision.toolName(), Map.of(
                    "status", toolResult.status(),
                    "count", toolResult.items().size()
            ));
            if (toolResult.items().isEmpty()) {
                break;
            }
            contexts.addAll(toolResult.items());
            conversationManager.saveRunning(sessionId, effectiveQuestion, contexts, round + 1);
        }
        return new OrchestrationResult(null, false);
    }

    private Set<AgentToolPermission> enabledPermissions(AgentRequest request) {
        Set<AgentToolPermission> enabledPermissions = EnumSet.noneOf(AgentToolPermission.class);
        if (request.includeRagContext()) {
            enabledPermissions.addAll(DEFAULT_RAG_TOOL_PERMISSIONS);
        }
        return enabledPermissions;
    }

    private ToolDecision parseDecision(String raw) {
        String json = extractJsonObject(raw);
        try {
            return objectMapper.readValue(json, ToolDecision.class);
        } catch (Exception e) {
            fireHook(AgentHookPoint.ON_ERROR, "", 0, "", null, Map.of("stage", "parseDecision", "error", e.getMessage()));
            return new ToolDecision("final", null, Map.of(), "模型决策解析失败，直接给出最终回答。", e.getMessage(), null);
        }
    }

    private String extractJsonObject(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }

    private AgentToolExecutionResult executeTool(ToolDecision decision, Set<AgentToolPermission> enabledPermissions, int limit) {
        String toolName = decision.toolName() == null ? "" : decision.toolName().trim();
        Map<String, Object> args = decision.args() == null ? Map.of() : decision.args();
        PermissionedAgentTool tool = agentToolRegistry.get(toolName);
        if (tool == null) {
            fireHook(AgentHookPoint.ON_ERROR, "", 0, "", toolName, Map.of("stage", "executeTool", "errorCode", "unknown_tool"));
            return errorToolResult(toolName, "unknown_tool", "未知工具: " + toolName, Map.of("toolName", toolName));
        }
        if (!enabledPermissions.contains(tool.permission())) {
            fireHook(AgentHookPoint.ON_ERROR, "", 0, "", toolName, Map.of("stage", "executeTool", "errorCode", "permission_denied"));
            return errorToolResult(toolName, "permission_denied", "工具未启用: " + toolName, Map.of("toolName", toolName));
        }
        List<String> schemaErrors = ToolSchemaValidator.validate(args, tool.argumentSpecs());
        if (!schemaErrors.isEmpty()) {
            fireHook(AgentHookPoint.ON_ERROR, "", 0, "", toolName, Map.of("stage", "executeTool", "errorCode", "schema_invalid", "errors", schemaErrors));
            return errorToolResult(toolName, "schema_invalid", "工具参数结构校验失败", Map.of("errors", schemaErrors));
        }
        List<String> validationErrors = tool.validateArgs(args);
        if (!validationErrors.isEmpty()) {
            fireHook(AgentHookPoint.ON_ERROR, "", 0, "", toolName, Map.of("stage", "executeTool", "errorCode", "invalid_args", "errors", validationErrors));
            return errorToolResult(toolName, "invalid_args", "工具参数校验失败", Map.of("errors", validationErrors));
        }
        List<AgentContextItem> items = tool.invoke(args, limit);
        return new AgentToolExecutionResult(
                toolName,
                "ok",
                "工具执行成功",
                items,
                Map.of("count", items.size())
        );
    }

    private AgentToolExecutionResult errorToolResult(String toolName, String errorCode, String message, Map<String, Object> meta) {
        List<AgentContextItem> items = List.of(new AgentContextItem(
                    "tool_error",
                    errorCode,
                    message,
                    meta
            ));
        return new AgentToolExecutionResult(toolName, "error", message, items, Map.of("errorCode", errorCode));
    }

    private AgentRequest withQuestion(AgentRequest request, String question, String sessionId) {
        return new AgentRequest(
                request.taskType(),
                request.repoRoot(),
                question,
                sessionId,
                request.followUpAnswer(),
                request.interrupt(),
                request.resume(),
                request.includeRagContext(),
                request.includeKnowledgeGraphContext(),
                request.contextLimit(),
                request.runMode(),
                request.dialogueMode()
        );
    }

    private record OrchestrationResult(String directAnswer, boolean askUser) {
    }

    private String dialogueModeDescription(String mode) {
        if ("COLLECT".equalsIgnoreCase(mode)) {
            return "当前对话模式: COLLECT（优先补全信息）";
        }
        if ("STEER".equalsIgnoreCase(mode)) {
            return "当前对话模式: STEER（主动引导收敛）";
        }
        return "当前对话模式: FOLLOW（跟随用户问题）";
    }

    private void fireHook(AgentHookPoint point, String sessionId, int round, String question, String toolName, Map<String, Object> metadata) {
        agentHookDispatcher.fire(point, new AgentHookContext(
                sessionId,
                round,
                question,
                toolName,
                metadata == null ? Map.of() : metadata
        ));
    }
}
