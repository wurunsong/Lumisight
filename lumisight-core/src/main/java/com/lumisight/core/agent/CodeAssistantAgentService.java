package com.lumisight.core.agent;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.hooks.runtime.HookedToolExecutor;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentLoopState;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.model.ToolCall;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.AgentDecisionParser;
import com.lumisight.core.support.AgentFinalAnswerVerifier;
import com.lumisight.core.support.AgentFlowSupport;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.AgentRequestValidators;
import com.lumisight.core.support.SkillAutoRouter;
import com.lumisight.core.support.StreamingChatClientSupport;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentToolRegistry;
import com.lumisight.hooks.AgentHookContext;
import com.lumisight.hooks.AgentHookDispatcher;
import com.lumisight.hooks.AgentHookPoint;
import com.lumisight.skills.runtime.SkillContext;
import com.lumisight.skills.runtime.SkillPlan;
import com.lumisight.skills.runtime.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CodeAssistantAgentService implements AgentExecutionEngine {

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int DEFAULT_CONTEXT_LIMIT = 5;
    private static final Logger log = LoggerFactory.getLogger(CodeAssistantAgentService.class);

    private final ChatClient llmChatClient;
    private final AgentToolRegistry agentToolRegistry;
    private final AgentPromptService agentPromptService;
    private final AgentConversationManager conversationManager;
    private final AgentHookDispatcher agentHookDispatcher;
    private final HookedToolExecutor hookedToolExecutor;
    private final SkillRegistry skillRegistry;
    private final AgentFlowSupport agentFlowSupport;
    private final AgentDecisionParser decisionParser;
    private final AgentFinalAnswerVerifier finalAnswerVerifier;
    private final SkillAutoRouter skillAutoRouter;
    private final StreamingChatClientSupport streamingChatClientSupport;

    @Autowired
    public CodeAssistantAgentService(
            ChatClient.Builder chatClientBuilder,
            AgentToolRegistry agentToolRegistry,
            AgentPromptService agentPromptService,
            AgentConversationManager conversationManager,
            AgentHookDispatcher agentHookDispatcher,
            HookedToolExecutor hookedToolExecutor,
            SkillRegistry skillRegistry,
            AgentFlowSupport agentFlowSupport,
            AgentDecisionParser decisionParser,
            AgentFinalAnswerVerifier finalAnswerVerifier,
            SkillAutoRouter skillAutoRouter,
            StreamingChatClientSupport streamingChatClientSupport
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.agentToolRegistry = agentToolRegistry;
        this.agentPromptService = agentPromptService;
        this.conversationManager = conversationManager;
        this.agentHookDispatcher = agentHookDispatcher;
        this.hookedToolExecutor = hookedToolExecutor;
        this.skillRegistry = skillRegistry;
        this.agentFlowSupport = agentFlowSupport;
        this.decisionParser = decisionParser;
        this.finalAnswerVerifier = finalAnswerVerifier;
        this.skillAutoRouter = skillAutoRouter;
        this.streamingChatClientSupport = streamingChatClientSupport;
    }

    public Flux<AgentEvent> run(AgentRequest request) {
        return execute(request);
    }

    @Override
    public Flux<AgentEvent> execute(AgentRequest request) {
        return Flux.create(sink -> executeStreaming(request, sink), FluxSink.OverflowStrategy.BUFFER);
    }

    private void executeStreaming(AgentRequest request, FluxSink<AgentEvent> sink) {
        AgentRequestValidators.validate(request);

        String traceId = UUID.randomUUID().toString();
        String sessionId = StringUtils.hasText(request.sessionId()) ? request.sessionId() : UUID.randomUUID().toString();
        AgentEventPublisher publisher = new AgentEventPublisher(sink);
        if (request.interrupt()) {
            conversationManager.interrupt(sessionId);
            publisher.emit(AgentEvent.interrupted(traceId, sessionId, 0));
            sink.complete();
            return;
        }

        try {
            AgentConversationManager.ConversationState resumeState = conversationManager.get(sessionId);
            long runEpoch = conversationManager.nextEpoch(sessionId);
            String effectiveQuestion = request.question();
            String resolvedRepoRoot = agentFlowSupport.resolveRepoRoot(request.repoRoot(), request.skillPath());
            int limit = request.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : request.contextLimit();
            List<AgentContextItem> contexts = new ArrayList<>();
            String directAnswerDraft = null;
            boolean askUser = false;
            boolean interrupted = false;
            int finalRound = 0;
            int startRound = 1;
            SkillPlan skillPlan = new SkillPlan(
                    "未指定技能，走默认编排",
                    List.of(),
                    "",
                    ""
            );

            if (request.resume() && resumeState != null) {
                if (resumeState.interrupted()) {
                    resumeState = new AgentConversationManager.ConversationState(
                            AgentConversationManager.ConversationStatus.RUNNING,
                            resumeState.baseQuestion(),
                            resumeState.contexts(),
                            resumeState.nextRound(),
                            false,
                            resumeState.pendingDecision(),
                            System.currentTimeMillis()
                    );
                }
                contexts.addAll(resumeState.contexts());
                startRound = resumeState.nextRound();
                if (!StringUtils.hasText(effectiveQuestion) && StringUtils.hasText(resumeState.baseQuestion())) {
                    effectiveQuestion = resumeState.baseQuestion();
                }
                publisher.emit(AgentEvent.resumed(traceId, sessionId));
            }

            try (AgentToolRuntimeContext.Scope ignored = AgentToolRuntimeContext.open(resolvedRepoRoot, limit)) {
            publisher.emit(AgentEvent.state(traceId, sessionId, 0, AgentLoopState.INIT.name(), "ok", "Agent启动"));
            publisher.emit(AgentEvent.dialogueMode(traceId, sessionId, request.dialogueMode().name(), "当前对话模式: " + request.dialogueMode().name()));

            String skillRef = request.skillPath();
            boolean shouldResolveSkill = StringUtils.hasText(skillRef);
            if (!shouldResolveSkill) {
                SkillAutoRouter.RouteResult routeResult = skillAutoRouter.route(effectiveQuestion);
                publisher.emit(AgentEvent.skillRouted(
                        traceId,
                        sessionId,
                        routeResult.candidateSkillId(),
                        routeResult.confidence(),
                        routeResult.accepted(),
                        routeResult.reason()
                ));
                if (routeResult.accepted() && StringUtils.hasText(routeResult.skillId())) {
                    skillRef = routeResult.skillId();
                    shouldResolveSkill = true;
                }
            }

            if (shouldResolveSkill) {
                SkillContext skillContext = new SkillContext(
                        request.taskType().name(),
                        request.dialogueMode().name(),
                        request.runMode().name(),
                        effectiveQuestion,
                        agentFlowSupport.buildSkillMetadata(resolvedRepoRoot, skillRef)
                );
                SkillRegistry.ResolvedSkill resolvedSkill = skillRegistry.resolve(skillContext);
                skillPlan = resolvedSkill.plan();
                publisher.emit(AgentEvent.skillSelected(traceId, sessionId, resolvedSkill.skillName(), skillPlan.summary()));
            }

            Set<AgentToolPermission> enabledPermissions = agentFlowSupport.enabledPermissions(request, skillPlan);

            if (!skillPlan.executionSteps().isEmpty()) {
                publisher.emit(AgentEvent.plan(traceId, sessionId, "Skill steps: " + String.join(" | ", skillPlan.executionSteps())));
            }

            // 会话恢复到 HUMAN_GATE 场景：这里执行“上次被挂起的高风险工具决策（pendingDecision）”。
            // 只有调用方显式传入 approveRiskyToolCall=true 才会继续执行该工具。
                if (request.resume() && resumeState != null && resumeState.pendingDecision() != null) {
                    if (!request.approveRiskyToolCall()) {
                        publisher.emit(AgentEvent.humanGate(
                            traceId,
                            sessionId,
                            startRound,
                            resumeState.pendingDecision().toolName(),
                                "检测到待确认写操作，请设置 approveRiskyToolCall=true 后继续。"
                        ));
                        sink.complete();
                        return;
                    }
                AgentToolExecutionResult gatedResult = executeToolWithHookContext(
                        resumeState.pendingDecision(),
                        enabledPermissions,
                        limit,
                        sessionId,
                        startRound,
                        effectiveQuestion
                );
                publisher.emit(AgentEvent.toolResult(traceId, sessionId, startRound, gatedResult));
                contexts.addAll(gatedResult.items());
                conversationManager.saveRunning(sessionId, effectiveQuestion, contexts, startRound + 1);
                startRound = startRound + 1;
            }

            if (request.runMode() == AgentRunMode.PLAN) {
                if (shouldInterruptExecution(sessionId, runEpoch)) {
                    appendInterruptedEvents(traceId, sessionId, 0, effectiveQuestion, contexts, publisher);
                    sink.complete();
                    return;
                }
                publisher.emit(AgentEvent.state(traceId, sessionId, 0, AgentLoopState.PLAN.name(), "running", "开始生成计划"));
                fireHook(AgentHookPoint.BEFORE_PLAN, sessionId, 0, effectiveQuestion, null, Map.of());
                String plan = streamingChatClientSupport.collect(
                        llmChatClient,
                        agentPromptService.systemPrompt(request.taskType()),
                        agentPromptService.planPrompt(request, skillPlan),
                        () -> !shouldInterruptExecution(sessionId, runEpoch),
                        null
                );
                publisher.emit(AgentEvent.plan(traceId, sessionId, plan));
                fireHook(AgentHookPoint.AFTER_PLAN, sessionId, 0, effectiveQuestion, null, Map.of("plan", plan));
            }

            OrchestrationResult result = runManualOrchestration(
                    request,
                    effectiveQuestion,
                    skillPlan,
                    contexts,
                    limit,
                    startRound,
                    publisher,
                    sessionId,
                    traceId,
                    enabledPermissions,
                    runEpoch
            );
            directAnswerDraft = result.directAnswer();
            askUser = result.askUser();
            interrupted = result.interrupted();
            finalRound = result.finalRound();
        }

        if (askUser) {
                sink.complete();
                return;
        }
        if (interrupted) {
                sink.complete();
                return;
        }
        if (!conversationManager.isActiveEpoch(sessionId, runEpoch)) {
                publisher.emit(AgentEvent.state(traceId, sessionId, 0, "STEER", "interrupted", "当前请求已被新的 STEER 问题抢占并终止。"));
                sink.complete();
                return;
        }

        if (StringUtils.hasText(directAnswerDraft)) {
            contexts.add(new AgentContextItem(
                    "assistant_draft",
                    "final_answer_draft",
                    directAnswerDraft,
                    Map.of("source", "orchestrator_final_decision")
            ));
        }

        AgentRequest finalRequest = agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId);
        String finalPrompt = agentPromptService.buildFinalAnswerPrompt(finalRequest, contexts, limit, skillPlan);
        fireHook(AgentHookPoint.BEFORE_FINAL, sessionId, finalRound, effectiveQuestion, null, Map.of("directAnswer", StringUtils.hasText(directAnswerDraft)));
        if (shouldInterruptExecution(sessionId, runEpoch)) {
            appendInterruptedEvents(traceId, sessionId, finalRound, effectiveQuestion, contexts, publisher);
            sink.complete();
            return;
        }
        publisher.emit(AgentEvent.state(traceId, sessionId, finalRound, AgentLoopState.FINAL.name(), "running", "开始流式生成最终结果"));
        StringBuilder finalAnswerBuffer = new StringBuilder();
        int currentFinalRound = finalRound;
        llmChatClient.prompt()
                .system(agentPromptService.systemPrompt(request.taskType()))
                .user(finalPrompt)
                .stream()
                .content()
                .takeWhile(content -> conversationManager.isActiveEpoch(sessionId, runEpoch))
                .doOnNext(content -> {
                    finalAnswerBuffer.append(content);
                    publisher.emit(AgentEvent.token(traceId, sessionId, currentFinalRound, content));
                })
                .blockLast();
        if (!shouldInterruptExecution(sessionId, runEpoch)) {
            String finalAnswer = finalAnswerBuffer.toString();
            if (!finalAnswer.isBlank()) {
                publisher.emit(AgentEvent.finalText(traceId, sessionId, currentFinalRound, finalAnswer));
            }
        }
        sink.complete();
        } catch (Throwable t) {
            sink.error(t);
        }
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
            SkillPlan skillPlan,
            List<AgentContextItem> contexts,
            int limit,
            int startRound,
            AgentEventPublisher publisher,
            String sessionId,
            String traceId,
            Set<AgentToolPermission> enabledPermissions,
            long runEpoch
    ) {
        int lastRound = Math.max(0, startRound - 1);
        for (int round = startRound; round <= MAX_TOOL_ROUNDS; round++) {
            lastRound = round;
            OrchestrationResult interruptedResult = checkInterrupted(traceId, sessionId, round, effectiveQuestion, contexts, publisher, runEpoch);
            if (interruptedResult != null) {
                return interruptedResult;
            }

            publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.DECIDE.name(), "running", "开始决策"));
            contexts.add(new AgentContextItem(
                    "conversation",
                    "user_prompt_round_" + round,
                    effectiveQuestion,
                    Map.of("round", round, "role", "user")
            ));
            List<AgentContextItem> decisionContexts = new ArrayList<>(contexts);
            int currentRound = round;
            conversationManager.todoReminderContext(sessionId, currentRound).ifPresent(reminder -> {
                decisionContexts.add(reminder);
                publisher.emit(AgentEvent.state(traceId, sessionId, currentRound, "TODO_REMINDER", "reminded", "已注入 todo_write 提醒"));
            });
            log.info("agent_loop decide, sessionId={}, round={}, question={}, contextSummary={}, errorContexts={}",
                    sessionId, round, effectiveQuestion, summarizeContextRefs(contexts), summarizeErrorContexts(contexts));
            fireHook(AgentHookPoint.BEFORE_DECISION, sessionId, round, effectiveQuestion, null, Map.of("contextSize", contexts.size()));
            String decisionRaw = streamingChatClientSupport.collect(
                    llmChatClient,
                    agentPromptService.orchestratorSystemPrompt(
                            request.taskType(),
                            request.dialogueMode(),
                            enabledPermissions,
                            agentToolRegistry,
                            skillPlan
                    ),
                    agentPromptService.orchestratorUserPrompt(
                            agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId),
                            decisionContexts,
                            limit,
                            round,
                            MAX_TOOL_ROUNDS,
                            skillPlan
                    ),
                    () -> !shouldInterruptExecution(sessionId, runEpoch),
                    null
            );
            interruptedResult = checkInterrupted(traceId, sessionId, round, effectiveQuestion, contexts, publisher, runEpoch);
            if (interruptedResult != null) {
                return interruptedResult;
            }
            contexts.add(new AgentContextItem(
                    "conversation",
                    "model_response_round_" + round,
                    decisionRaw,
                    Map.of("round", round, "role", "assistant")
            ));
            fireHook(AgentHookPoint.AFTER_DECISION, sessionId, round, effectiveQuestion, null, Map.of("decisionRaw", decisionRaw));
            log.info("agent_loop decision_raw, sessionId={}, round={}, decisionRaw={}", sessionId, round, trimForLog(decisionRaw));
            ToolDecision decision = parseDecision(decisionRaw);

            if ("ask_user".equalsIgnoreCase(decision.action())) {
                String question = StringUtils.hasText(decision.askUserQuestion()) ? decision.askUserQuestion() : "我还需要你补充一些信息，才能继续。";
                conversationManager.saveWaiting(sessionId, effectiveQuestion, contexts, round);
                publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.ASK_USER.name(), "waiting_user", "等待用户补充信息"));
                publisher.emit(AgentEvent.askUser(traceId, sessionId, round, question));
                fireHook(AgentHookPoint.ON_ASK_USER, sessionId, round, effectiveQuestion, null, Map.of("askUserQuestion", question));
                return new OrchestrationResult(null, true, false, round);
            }

            if ("final".equalsIgnoreCase(decision.action())) {
                if (StringUtils.hasText(decision.finalAnswer())) {
                    boolean shouldVerify = agentFlowSupport.shouldVerifyFinalAnswer(request, effectiveQuestion, decision.finalAnswer(), contexts);
                    AgentFinalAnswerVerifier.VerifyResult verifyResult = shouldVerify
                            ? finalAnswerVerifier.verifyFinalAnswer(agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId), decision.finalAnswer(), contexts)
                            : new AgentFinalAnswerVerifier.VerifyResult(true, "问题不要求精确事实，跳过复核");
                    if (shouldVerify) {
                        publisher.emit(AgentEvent.verifyResult(traceId, sessionId, round, verifyResult.pass(), verifyResult.reason()));
                    }
                    if (verifyResult.pass()) {
                        publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.FINAL.name(), "ok", "决策直接给出最终答案"));
                        return new OrchestrationResult(decision.finalAnswer(), false, false, round);
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

            List<ToolDecision> toolCalls = expandToolCalls(decision);
            if (toolCalls.isEmpty()) {
                break;
            }
            List<List<ToolDecision>> batches = hookedToolExecutor.partitionToolCalls(toolCalls, enabledPermissions);
            boolean producedContext = false;
            for (List<ToolDecision> batch : batches) {
                ToolDecision gatedDecision = findHumanGatedDecision(batch);
                if (gatedDecision != null && !request.approveRiskyToolCall()) {
                    conversationManager.saveWaitingForGate(sessionId, effectiveQuestion, contexts, round, gatedDecision);
                    publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.ASK_USER.name(), "waiting_user", "等待人工确认高风险工具调用"));
                    publisher.emit(AgentEvent.humanGate(
                            traceId,
                            sessionId,
                            round,
                            gatedDecision.toolName(),
                            "即将执行写操作工具 `" + gatedDecision.toolName() + "`，请确认后继续（approveRiskyToolCall=true）。"
                    ));
                    return new OrchestrationResult(null, true, false, round);
                }

                publisher.emit(AgentEvent.state(
                        traceId,
                        sessionId,
                        round,
                        AgentLoopState.TOOL_CALL.name(),
                        "running",
                        batch.size() > 1 ? "开始并发工具调用" : "开始工具调用"
                ));
                for (ToolDecision toolCall : batch) {
                    publisher.emit(AgentEvent.toolCall(traceId, sessionId, round, toolCall.toolName(), toolCall.args()));
                }
                List<AgentToolExecutionResult> batchResults = hookedToolExecutor.executeBatch(
                        batch,
                        enabledPermissions,
                        limit,
                        sessionId,
                        round,
                        effectiveQuestion
                );
                interruptedResult = checkInterrupted(traceId, sessionId, round, effectiveQuestion, contexts, publisher, runEpoch);
                if (interruptedResult != null) {
                    return interruptedResult;
                }
                for (AgentToolExecutionResult batchResult : batchResults) {
                    publisher.emit(AgentEvent.toolResult(traceId, sessionId, round, batchResult));
                    contexts.add(new AgentContextItem(
                            "conversation",
                            "tool_result_round_" + round + "_" + batchResult.toolName(),
                            renderToolResultContext(batchResult),
                            Map.of(
                                    "round", round,
                                    "toolName", batchResult.toolName(),
                                    "status", batchResult.status()
                            )
                    ));
                    if (!"ok".equals(batchResult.status()) || containsToolError(batchResult.items())) {
                        log.warn("agent_loop tool_result_error, sessionId={}, round={}, toolName={}, status={}, message={}, items={}",
                                sessionId, round, batchResult.toolName(), batchResult.status(), batchResult.message(),
                                summarizeContextItems(batchResult.items()));
                    }
                    if (!batchResult.items().isEmpty()) {
                        producedContext = true;
                        contexts.addAll(batchResult.items());
                    }
                }
                conversationManager.saveRunning(sessionId, effectiveQuestion, contexts, round + 1);
            }
            if (!producedContext) {
                break;
            }
        }
        return new OrchestrationResult(null, false, false, Math.max(0, lastRound));
    }

    private OrchestrationResult checkInterrupted(
            String traceId,
            String sessionId,
            int round,
            String effectiveQuestion,
            List<AgentContextItem> contexts,
            AgentEventPublisher publisher,
            long runEpoch
    ) {
        if (!shouldInterruptExecution(sessionId, runEpoch)) {
            return null;
        }
        appendInterruptedEvents(traceId, sessionId, round, effectiveQuestion, contexts, publisher);
        return new OrchestrationResult(null, false, true, round);
    }

    private void appendInterruptedEvents(
            String traceId,
            String sessionId,
            int round,
            String effectiveQuestion,
            List<AgentContextItem> contexts,
            AgentEventPublisher publisher
    ) {
        publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.INTERRUPTED.name(), "interrupted", "会话中断"));
        publisher.emit(AgentEvent.interrupted(traceId, sessionId, round));
        conversationManager.saveRunning(sessionId, effectiveQuestion, contexts, round);
    }

    private boolean shouldInterruptExecution(String sessionId, long runEpoch) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        if (!conversationManager.isActiveEpoch(sessionId, runEpoch)) {
            return true;
        }
        AgentConversationManager.ConversationState state = conversationManager.get(sessionId);
        return state != null && state.interrupted();
    }

    private ToolDecision parseDecision(String raw) {
        try {
            return decisionParser.parseOrFallback(raw);
        } catch (Exception e) {
            fireHook(AgentHookPoint.ON_ERROR, "", 0, "", null, Map.of("stage", "parseDecision", "error", e.getMessage()));
            return decisionParser.fallbackDecision(e.getMessage());
        }
    }

    private AgentToolExecutionResult executeToolWithHookContext(
            ToolDecision decision,
            Set<AgentToolPermission> enabledPermissions,
            int limit,
            String sessionId,
            int round,
            String question
    ) {
        return hookedToolExecutor.execute(decision, enabledPermissions, limit, sessionId, round, question);
    }

    private List<ToolDecision> expandToolCalls(ToolDecision decision) {
        if (decision.toolCalls() != null && !decision.toolCalls().isEmpty()) {
            return decision.toolCalls().stream()
                    .map(toolCall -> new ToolDecision(
                            "tool",
                            toolCall.toolName(),
                            toolCall.args() == null ? Map.of() : toolCall.args(),
                            List.of(),
                            null,
                            decision.reason(),
                            null
                    ))
                    .toList();
        }
        if (!StringUtils.hasText(decision.toolName())) {
            return List.of();
        }
        return List.of(new ToolDecision(
                "tool",
                decision.toolName(),
                decision.args() == null ? Map.of() : decision.args(),
                List.of(),
                null,
                decision.reason(),
                null
        ));
    }

    private ToolDecision findHumanGatedDecision(List<ToolDecision> batch) {
        for (ToolDecision toolDecision : batch) {
            if (agentFlowSupport.requiresHumanGate(toolDecision)) {
                return toolDecision;
            }
        }
        return null;
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

    private boolean containsToolError(List<AgentContextItem> items) {
        return items != null && items.stream().anyMatch(item -> "tool_error".equals(item.sourceType()));
    }

    private String summarizeContextRefs(List<AgentContextItem> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "[]";
        }
        return contexts.stream()
                .map(item -> "[" + item.sourceType() + "]" + item.sourceId())
                .reduce((a, b) -> a + ", " + b)
                .map(text -> "[" + text + "]")
                .orElse("[]");
    }

    private String summarizeErrorContexts(List<AgentContextItem> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "[]";
        }
        return contexts.stream()
                .filter(item -> "tool_error".equals(item.sourceType()) || "verifier".equals(item.sourceType()))
                .map(item -> "{sourceId=" + item.sourceId() + ", content=" + trimForLog(item.content()) + ", metadata=" + item.metadata() + "}")
                .reduce((a, b) -> a + ", " + b)
                .map(text -> "[" + text + "]")
                .orElse("[]");
    }

    private String summarizeContextItems(List<AgentContextItem> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        return items.stream()
                .map(item -> "{sourceType=" + item.sourceType() + ", sourceId=" + item.sourceId() + ", content=" + trimForLog(item.content()) + ", metadata=" + item.metadata() + "}")
                .reduce((a, b) -> a + ", " + b)
                .map(text -> "[" + text + "]")
                .orElse("[]");
    }

    private String trimForLog(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.trim();
        int maxChars = 1000;
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "...(truncated)";
    }

    private String renderToolResultContext(AgentToolExecutionResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("toolName: ").append(result.toolName()).append("\n");
        builder.append("status: ").append(result.status()).append("\n");
        builder.append("message: ").append(result.message()).append("\n");
        builder.append("metrics: ").append(result.metrics() == null ? Map.of() : result.metrics()).append("\n");
        builder.append("items:\n");
        if (result.items() == null || result.items().isEmpty()) {
            builder.append("- 无\n");
        } else {
            for (AgentContextItem item : result.items()) {
                builder.append("- [").append(item.sourceType()).append("] ")
                        .append(item.sourceId()).append("\n")
                        .append(item.content()).append("\n")
                        .append("  metadata: ").append(item.metadata() == null ? Map.of() : item.metadata()).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private record OrchestrationResult(String directAnswer, boolean askUser, boolean interrupted, int finalRound) {
    }
}
