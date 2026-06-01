package com.lumisight.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentLoopState;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.AgentRequestValidators;
import com.lumisight.core.support.ToolSchemaValidator;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentToolRegistry;
import com.lumisight.core.tool.PermissionedAgentTool;
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
import java.util.HashMap;
import java.util.LinkedHashMap;

@Service
public class CodeAssistantAgentService {

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int DEFAULT_CONTEXT_LIMIT = 5;
    private static final int TOOL_MAX_RETRY = 2;
    private static final Set<AgentToolPermission> DEFAULT_RAG_TOOL_PERMISSIONS = EnumSet.of(
            AgentToolPermission.HYBRID_VECTOR_READ,
            AgentToolPermission.MCP_CAPABILITY_CALL,
            AgentToolPermission.LOCAL_FS_READ,
            AgentToolPermission.LOCAL_FS_WRITE,
            AgentToolPermission.LSP_JAVA_READ,
            AgentToolPermission.BUILD_COMPILE,
            AgentToolPermission.GIT_READ
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
                        false,
                        resumeState.pendingDecision()
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
                    buildSkillMetadata(request)
            ));
            SkillPlan skillPlan = skill.buildPlan(new SkillContext(
                    request.taskType().name(),
                    request.dialogueMode().name(),
                    request.runMode().name(),
                    effectiveQuestion,
                    buildSkillMetadata(request)
            ));
            events.add(AgentEvent.skillSelected(traceId, sessionId, skill.skillName(), skillPlan.summary()));
            if (!skillPlan.executionSteps().isEmpty()) {
                events.add(AgentEvent.plan(traceId, sessionId, "Skill steps: " + String.join(" | ", skillPlan.executionSteps())));
                executeSkillSteps(skillPlan, effectiveQuestion, contexts, limit, events, sessionId, traceId, enabledPermissions(request));
            }
            if (request.resume() && resumeState != null && resumeState.pendingDecision() != null) {
                if (!request.approveRiskyToolCall()) {
                    events.add(AgentEvent.humanGate(
                            traceId,
                            sessionId,
                            startRound,
                            resumeState.pendingDecision().toolName(),
                            "检测到待确认写操作，请设置 approveRiskyToolCall=true 后继续。"
                    ));
                    return Flux.fromIterable(events);
                }
                AgentToolExecutionResult gatedResult = executeTool(resumeState.pendingDecision(), enabledPermissions(request), limit);
                events.add(AgentEvent.toolResult(traceId, sessionId, startRound, gatedResult));
                contexts.addAll(gatedResult.items());
                conversationManager.saveRunning(sessionId, effectiveQuestion, contexts, startRound + 1);
                startRound = startRound + 1;
            }
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
                        || "HUMAN_GATE".equals(event.type())
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
                    VerifyResult verifyResult = verifyFinalAnswer(withQuestion(request, effectiveQuestion, sessionId), decision.finalAnswer(), contexts);
                    events.add(AgentEvent.verifyResult(traceId, sessionId, round, verifyResult.pass(), verifyResult.reason()));
                    if (verifyResult.pass()) {
                        events.add(AgentEvent.state(traceId, sessionId, round, AgentLoopState.FINAL.name(), "ok", "决策直接给出最终答案"));
                        return new OrchestrationResult(decision.finalAnswer(), false);
                    }
                    contexts.add(new AgentContextItem(
                            "verifier",
                            "final_answer_check",
                            "复核未通过: " + verifyResult.reason(),
                            Map.of("round", round)
                    ));
                    continue;
                }
                break;
            }
            if (!"tool".equalsIgnoreCase(decision.action())) {
                break;
            }
            if (requiresHumanGate(decision) && !request.approveRiskyToolCall()) {
                conversationManager.saveWaitingForGate(sessionId, effectiveQuestion, contexts, round, decision);
                events.add(AgentEvent.state(traceId, sessionId, round, AgentLoopState.ASK_USER.name(), "waiting_user", "等待人工确认高风险工具调用"));
                events.add(AgentEvent.humanGate(
                        traceId,
                        sessionId,
                        round,
                        decision.toolName(),
                        "即将执行写操作工具 `" + decision.toolName() + "`，请确认后继续（approveRiskyToolCall=true）。"
                ));
                return new OrchestrationResult(null, true);
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
        String requestedToolName = decision.toolName() == null ? "" : decision.toolName().trim();
        String toolName = normalizeToolName(requestedToolName);
        Map<String, Object> args = normalizeArgsForTool(requestedToolName, toolName, decision.args() == null ? Map.of() : decision.args());
        PermissionedAgentTool tool = agentToolRegistry.get(toolName);
        if (tool == null) {
            fireHook(AgentHookPoint.ON_ERROR, "", 0, "", toolName, Map.of("stage", "executeTool", "errorCode", "unknown_tool"));
            return errorToolResult(toolName, "unknown_tool", "未知工具: " + toolName, Map.of("toolName", toolName, "requestedToolName", requestedToolName));
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
        AgentToolExecutionResult primary = invokeWithRetry(tool, args, limit, TOOL_MAX_RETRY);
        if ("ok".equals(primary.status())) {
            return primary;
        }
        AgentToolExecutionResult fallback = tryFallback(toolName, args, limit, enabledPermissions);
        if (fallback != null) {
            return fallback;
        }
        return primary;
    }

    private String normalizeToolName(String requestedToolName) {
        if (!StringUtils.hasText(requestedToolName)) {
            return "";
        }
        return switch (requestedToolName.trim()) {
            case "read_file", "readFile", "open_file", "openFile", "get_file_content" -> "cat";
            case "read_directory", "list_directory", "get_directory_structure", "listDir" -> "ls";
            case "search_files", "search_in_files", "find_in_files" -> "grep";
            default -> requestedToolName.trim();
        };
    }

    private Map<String, Object> normalizeArgsForTool(String requestedToolName, String normalizedToolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        if (!StringUtils.hasText(requestedToolName) || requestedToolName.equals(normalizedToolName)) {
            return args;
        }
        if ("cat".equals(normalizedToolName)) {
            Map<String, Object> mapped = new HashMap<>(args);
            if (!mapped.containsKey("sourceFile")) {
                Object filePath = mapped.get("filePath");
                if (filePath == null) {
                    filePath = mapped.get("path");
                }
                if (filePath != null) {
                    mapped.put("sourceFile", String.valueOf(filePath));
                }
            }
            if (!mapped.containsKey("maxLines") && mapped.get("limit") != null) {
                mapped.put("maxLines", mapped.get("limit"));
            }
            return mapped;
        }
        if ("grep".equals(normalizedToolName)) {
            Map<String, Object> mapped = new HashMap<>(args);
            if (!mapped.containsKey("pattern")) {
                Object query = mapped.get("query");
                if (query == null) {
                    query = mapped.get("keyword");
                }
                if (query != null) {
                    mapped.put("pattern", String.valueOf(query));
                }
            }
            return mapped;
        }
        return args;
    }

    private AgentToolExecutionResult invokeWithRetry(PermissionedAgentTool tool, Map<String, Object> args, int limit, int maxRetry) {
        String toolName = tool.toolName();
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                List<AgentContextItem> items = tool.invoke(args, limit);
                return new AgentToolExecutionResult(
                        toolName,
                        "ok",
                        "工具执行成功",
                        items,
                        Map.of("count", items.size(), "attempt", attempt)
                );
            } catch (Exception e) {
                lastError = e;
            }
        }
        return errorToolResult(toolName, "tool_invoke_failed", "工具调用失败: " + (lastError == null ? "unknown" : lastError.getMessage()), Map.of());
    }

    private AgentToolExecutionResult tryFallback(String toolName, Map<String, Object> args, int limit, Set<AgentToolPermission> enabledPermissions) {
        String fallbackName = fallbackToolName(toolName);
        if (!StringUtils.hasText(fallbackName)) {
            return null;
        }
        PermissionedAgentTool fallback = agentToolRegistry.get(fallbackName);
        if (fallback == null || !enabledPermissions.contains(fallback.permission())) {
            return null;
        }
        Map<String, Object> fallbackArgs = fallbackArgs(toolName, args);
        List<String> schemaErrors = ToolSchemaValidator.validate(fallbackArgs, fallback.argumentSpecs());
        if (!schemaErrors.isEmpty()) {
            return null;
        }
        List<String> errors = fallback.validateArgs(fallbackArgs);
        if (!errors.isEmpty()) {
            return null;
        }
        AgentToolExecutionResult result = invokeWithRetry(fallback, fallbackArgs, limit, 1);
        if ("ok".equals(result.status())) {
            return new AgentToolExecutionResult(
                    result.toolName(),
                    "ok",
                    "主工具失败，已回退到 " + fallbackName,
                    result.items(),
                    new HashMap<>(result.metrics())
            );
        }
        return null;
    }

    private String fallbackToolName(String toolName) {
        return switch (toolName) {
            case "cat" -> "fetchMethodSourceByLocation";
            case "grep", "ls", "pwd" -> "callMcpCapability";
            default -> null;
        };
    }

    private Map<String, Object> fallbackArgs(String toolName, Map<String, Object> args) {
        if ("cat".equals(toolName)) {
            return Map.of(
                    "sourceFile", String.valueOf(args.getOrDefault("sourceFile", "")),
                    "startLine", args.get("startLine") == null ? 1 : args.get("startLine"),
                    "endLine", args.get("endLine") == null ? 200 : args.get("endLine")
            );
        }
        if ("grep".equals(toolName)) {
            return Map.of(
                    "capability", "grep",
                    "args", Map.of(
                            "pattern", String.valueOf(args.getOrDefault("pattern", "")),
                            "filePattern", String.valueOf(args.getOrDefault("filePattern", "")),
                            "limit", args.get("limit") == null ? 50 : args.get("limit")
                    )
            );
        }
        if ("ls".equals(toolName)) {
            return Map.of(
                    "capability", "ls",
                    "args", Map.of(
                            "path", String.valueOf(args.getOrDefault("path", "")),
                            "limit", args.get("limit") == null ? 100 : args.get("limit")
                    )
            );
        }
        if ("pwd".equals(toolName)) {
            return Map.of("capability", "pwd", "args", Map.of());
        }
        return args;
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
                request.skillPath(),
                sessionId,
                request.followUpAnswer(),
                request.approveRiskyToolCall(),
                request.interrupt(),
                request.resume(),
                request.includeRagContext(),
                request.includeKnowledgeGraphContext(),
                request.contextLimit(),
                request.runMode(),
                request.dialogueMode()
        );
    }

    private Map<String, Object> buildSkillMetadata(AgentRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("repoRoot", request.repoRoot());
        metadata.put("skillPath", request.skillPath());
        return metadata;
    }

    private void executeSkillSteps(
            SkillPlan skillPlan,
            String effectiveQuestion,
            List<AgentContextItem> contexts,
            int limit,
            List<AgentEvent> events,
            String sessionId,
            String traceId,
            Set<AgentToolPermission> enabledPermissions
    ) {
        int round = 0;
        for (String step : skillPlan.executionSteps()) {
            round++;
            ToolDecision decision = mapSkillStepToDecision(step, effectiveQuestion);
            if (decision == null) {
                continue;
            }
            events.add(AgentEvent.state(traceId, sessionId, round, AgentLoopState.TOOL_CALL.name(), "running", "执行Skill步骤: " + step));
            events.add(AgentEvent.toolCall(traceId, sessionId, round, decision.toolName(), decision.args()));
            AgentToolExecutionResult toolResult = executeTool(decision, enabledPermissions, limit);
            events.add(AgentEvent.toolResult(traceId, sessionId, round, toolResult));
            if (!toolResult.items().isEmpty()) {
                contexts.addAll(toolResult.items());
            }
        }
    }

    private ToolDecision mapSkillStepToDecision(String step, String question) {
        if (!StringUtils.hasText(step)) {
            return null;
        }
        String s = step.toLowerCase();
        if (s.contains("list") || s.contains("目录")) {
            return new ToolDecision("tool", "ls", Map.of("path", ".", "limit", 200), null, "Skill step list", null);
        }
        if (s.contains("read") || s.contains("读取")) {
            return new ToolDecision("tool", "cat", Map.of("sourceFile", "README.md", "maxLines", 240), null, "Skill step read", null);
        }
        if (s.contains("search") || s.contains("检索") || s.contains("grep")) {
            return new ToolDecision("tool", "grep", Map.of("pattern", question, "limit", 40), null, "Skill step search", null);
        }
        if (s.contains("compile") || s.contains("编译")) {
            return new ToolDecision("tool", "compileJava", Map.of(), null, "Skill step compile", null);
        }
        if (s.contains("diff") || s.contains("git")) {
            return new ToolDecision("tool", "gitDiff", Map.of("path", ".", "maxLines", 300), null, "Skill step git", null);
        }
        return null;
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

    private boolean requiresHumanGate(ToolDecision decision) {
        if (decision == null) {
            return false;
        }
        PermissionedAgentTool tool = agentToolRegistry.get(decision.toolName());
        return tool != null && tool.permission() == AgentToolPermission.LOCAL_FS_WRITE;
    }

    private VerifyResult verifyFinalAnswer(AgentRequest request, String candidateAnswer, List<AgentContextItem> contexts) {
        try {
            String raw = llmChatClient.prompt()
                    .system("你是严谨的答案复核器。")
                    .user(agentPromptService.verifyPrompt(request, candidateAnswer, contexts))
                    .call()
                    .content();
            String json = extractJsonObject(raw);
            Map<?, ?> parsed = objectMapper.readValue(json, Map.class);
            Object passRaw = parsed.get("pass");
            Object reasonRaw = parsed.get("reason");
            boolean pass = Boolean.parseBoolean(String.valueOf(passRaw == null ? false : passRaw));
            String reason = String.valueOf(reasonRaw == null ? "" : reasonRaw);
            return new VerifyResult(pass, reason);
        } catch (Exception e) {
            return new VerifyResult(true, "复核器异常，默认放行");
        }
    }

    private record VerifyResult(boolean pass, String reason) {
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
