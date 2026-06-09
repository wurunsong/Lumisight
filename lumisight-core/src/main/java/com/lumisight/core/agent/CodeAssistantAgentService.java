package com.lumisight.core.agent;

import com.lumisight.core.agent.multiagent.service.MultiAgentCoordinator;
import com.lumisight.core.agent.multiagent.service.MultiAgentModeDecider;
import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentLoopState;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.AgentFlowSupport;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.AgentRequestValidators;
import com.lumisight.core.support.AgentSessionContextStore;
import com.lumisight.core.support.RelevantMemoryService;
import com.lumisight.core.support.SkillAutoRouter;
import com.lumisight.core.support.StreamingChatClientSupport;
import com.lumisight.core.support.context.AgentContextManager;
import com.lumisight.core.support.context.AgentContextSession;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.hooks.AgentHookDispatcher;
import com.lumisight.hooks.AgentHookPoint;
import com.lumisight.memory.RelevantMemoryContext;
import com.lumisight.skills.runtime.SkillContext;
import com.lumisight.skills.runtime.SkillPlan;
import com.lumisight.skills.runtime.SkillRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

@Service
public class CodeAssistantAgentService implements AgentExecutionEngine {

    private static final int DEFAULT_CONTEXT_LIMIT = 5;

    private final AgentSessionContextStore conversationManager;
    private final SkillRegistry skillRegistry;
    private final AgentFlowSupport agentFlowSupport;
    private final ChatClient llmChatClient;
    private final AgentPromptService agentPromptService;
    private final SkillAutoRouter skillAutoRouter;
    private final StreamingChatClientSupport streamingChatClientSupport;
    private final AgentContextManager agentContextManager;
    private final AgentHookDispatcher agentHookDispatcher;
    private final AgentLoopOrchestrator agentLoopOrchestrator;
    private final AgentFinalResponseEmitter agentFinalResponseEmitter;
    private final RelevantMemoryService relevantMemoryService;
    private final MultiAgentModeDecider multiAgentModeDecider;
    private final MultiAgentCoordinator multiAgentCoordinator;

    @Autowired
    public CodeAssistantAgentService(
            ChatClient.Builder chatClientBuilder,
            AgentSessionContextStore conversationManager,
            SkillRegistry skillRegistry,
            AgentFlowSupport agentFlowSupport,
            AgentPromptService agentPromptService,
            SkillAutoRouter skillAutoRouter,
            StreamingChatClientSupport streamingChatClientSupport,
            AgentContextManager agentContextManager,
            AgentHookDispatcher agentHookDispatcher,
            AgentLoopOrchestrator agentLoopOrchestrator,
            AgentFinalResponseEmitter agentFinalResponseEmitter,
            RelevantMemoryService relevantMemoryService,
            MultiAgentModeDecider multiAgentModeDecider,
            MultiAgentCoordinator multiAgentCoordinator
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.conversationManager = conversationManager;
        this.skillRegistry = skillRegistry;
        this.agentFlowSupport = agentFlowSupport;
        this.agentPromptService = agentPromptService;
        this.skillAutoRouter = skillAutoRouter;
        this.streamingChatClientSupport = streamingChatClientSupport;
        this.agentContextManager = agentContextManager;
        this.agentHookDispatcher = agentHookDispatcher;
        this.agentLoopOrchestrator = agentLoopOrchestrator;
        this.agentFinalResponseEmitter = agentFinalResponseEmitter;
        this.relevantMemoryService = relevantMemoryService;
        this.multiAgentModeDecider = multiAgentModeDecider;
        this.multiAgentCoordinator = multiAgentCoordinator;
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
        // sink包装类，用于在关键节点处给用户发送事件
        AgentEventPublisher publisher = new AgentEventPublisher(sink);
        if (request.interrupt()) {
            conversationManager.interrupt(sessionId);
            publisher.emit(AgentEvent.interrupted(traceId, sessionId, 0));
            sink.complete();
            return;
        }

        try {
            ExecutionContext context = prepareExecutionContext(request, sessionId);
            AgentRequest effectiveRequest = resolveEffectiveRequest(request, context.effectiveQuestion(), traceId, sessionId, publisher);
            CompletableFuture<RelevantMemoryContext> pendingRelevantMemory = relevantMemoryService.prefetch(new AgentRequest(
                    effectiveRequest.taskType(),
                    context.resolvedRepoRoot(),
                    context.effectiveQuestion(),
                    effectiveRequest.skillPath(),
                    effectiveRequest.userId(),
                    sessionId,
                    effectiveRequest.approveRiskyToolCall(),
                    effectiveRequest.interrupt(),
                    effectiveRequest.resume(),
                    effectiveRequest.includeRagContext(),
                    effectiveRequest.includeKnowledgeGraphContext(),
                    effectiveRequest.contextLimit(),
                    effectiveRequest.runMode(),
                    effectiveRequest.dialogueMode()
            ));
            try (AgentToolRuntimeContext.Scope ignored = AgentToolRuntimeContext.open(context.resolvedRepoRoot(), context.limit(), effectiveRequest.userId())) {
                publishInitState(effectiveRequest, context, traceId, publisher);
                SkillPlan skillPlan = resolveSkillPlan(effectiveRequest, context, traceId, publisher);
                Set<AgentToolPermission> enabledPermissions = agentFlowSupport.enabledPermissions(effectiveRequest, skillPlan);
                RelevantMemoryContext relevantMemoryContext = joinRelevantMemory(pendingRelevantMemory);
                publishSkillPlan(traceId, sessionId, publisher, skillPlan);
                context = orchestrateMultiAgentIfNeeded(effectiveRequest, context, traceId, publisher);

                ResumeHandlingResult resumeHandling = handlePendingResumeDecision(
                        effectiveRequest,
                        context,
                        traceId,
                        publisher,
                        enabledPermissions
                );
                if (resumeHandling.completed()) {
                    sink.complete();
                    return;
                }
                context = resumeHandling.context();

                if (shouldInterruptExecution(context.sessionId(), context.runEpoch())) {
                    appendInterruptedEvents(traceId, context.sessionId(), 0, context.effectiveQuestion(), context.contextSession(), publisher);
                    sink.complete();
                    return;
                }

                if (effectiveRequest.runMode() == AgentRunMode.PLAN && emitPlanIfNeeded(effectiveRequest, context, skillPlan, traceId, publisher, relevantMemoryContext)) {
                    sink.complete();
                    return;
                }

                AgentLoopOrchestrator.OrchestrationResult result = agentLoopOrchestrator.run(
                        effectiveRequest,
                        context.effectiveQuestion(),
                        skillPlan,
                        context.contextSession(),
                        context.limit(),
                        context.startRound(),
                        publisher,
                        sessionId,
                        traceId,
                        enabledPermissions,
                        context.runEpoch(),
                        relevantMemoryContext
                );
                context = context.withContextSession(result.contextSession());

                if (result.askUser() || result.interrupted()) {
                    sink.complete();
                    return;
                }
                if (!conversationManager.isActiveEpoch(sessionId, context.runEpoch())) {
                    publisher.emit(AgentEvent.state(traceId, sessionId, 0, "STEER", "interrupted", "当前请求已被新的 STEER 问题抢占并终止。"));
                    sink.complete();
                    return;
                }

                emitFinalAnswer(effectiveRequest, context, skillPlan, result, traceId, publisher, relevantMemoryContext);
                sink.complete();
            }
        } catch (Throwable t) {
            sink.error(t);
        }
    }

    private RelevantMemoryContext joinRelevantMemory(CompletableFuture<RelevantMemoryContext> pendingRelevantMemory) {
        if (pendingRelevantMemory == null) {
            return RelevantMemoryContext.empty();
        }
        try {
            return pendingRelevantMemory.join();
        } catch (Exception e) {
            return RelevantMemoryContext.empty();
        }
    }

    /**
     * 决定agent编排方式：单agent、多agent
     * @param request 用户请求
     * @param effectiveQuestion 提问
     * @param traceId trace
     * @param sessionId 会话id
     * @param publisher 返回流
     * @return 结构化的agent请求
     */
    private AgentRequest resolveEffectiveRequest(AgentRequest request, String effectiveQuestion, String traceId, String sessionId, AgentEventPublisher publisher) {
        MultiAgentModeDecider.Decision decision = multiAgentModeDecider.decide(request, effectiveQuestion);
        if (decision.multiAgentSelected()) {
            publisher.emit(AgentEvent.multiAgentSelected(
                    traceId,
                    sessionId,
                    0,
                    decision.effectiveRunMode().name(),
                    decision.reason(),
                    Map.of(
                            "confidence", decision.confidence(),
                            "matchedSignals", decision.matchedSignals()
                    )
            ));
        }
        if (decision.effectiveRunMode() == request.runMode()) {
            return request;
        }
        return new AgentRequest(
                request.taskType(),
                request.repoRoot(),
                request.question(),
                request.skillPath(),
                request.userId(),
                request.sessionId(),
                request.approveRiskyToolCall(),
                request.interrupt(),
                request.resume(),
                request.includeRagContext(),
                request.includeKnowledgeGraphContext(),
                request.contextLimit(),
                decision.effectiveRunMode(),
                request.dialogueMode()
        );
    }
    /**
     * 准备本轮对话的初始上下文，主要包含：当前提问和历史上下文
     * @param request 用户请求
     * @param sessionId 会话 ID
     * @return 当前对话的初始上下文
     */
    private ExecutionContext prepareExecutionContext(AgentRequest request, String sessionId) {
        AgentConversationManager.ConversationState resumeState = conversationManager.get(sessionId);
        long runEpoch = conversationManager.nextEpoch(sessionId);
        String effectiveQuestion = request.question();
        String resolvedRepoRoot = agentFlowSupport.resolveRepoRoot(request.repoRoot(), request.skillPath());
        int limit = request.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : request.contextLimit();
        int startRound = 1;
        if (request.resume() && resumeState != null) {
            resumeState = normalizeResumeState(resumeState);
            startRound = resumeState.nextRound();
            if (!StringUtils.hasText(effectiveQuestion) && StringUtils.hasText(resumeState.baseQuestion())) {
                effectiveQuestion = resumeState.baseQuestion();
            }
        }
        // 恢复当前 session 的历史上下文；resume 场景先归一化状态，再恢复一次即可。
        AgentContextSession contextSession = agentContextManager.restore(sessionId, resumeState);
        return new ExecutionContext(sessionId, runEpoch, resumeState, effectiveQuestion, resolvedRepoRoot, limit, contextSession, startRound);
    }

    private AgentConversationManager.ConversationState normalizeResumeState(AgentConversationManager.ConversationState resumeState) {
        if (resumeState == null || !resumeState.interrupted()) {
            return resumeState;
        }
        return new AgentConversationManager.ConversationState(
                AgentConversationManager.ConversationStatus.RUNNING,
                resumeState.baseQuestion(),
                resumeState.contexts(),
                resumeState.contextSession(),
                resumeState.nextRound(),
                false,
                resumeState.pendingDecision(),
                System.currentTimeMillis()
        );
    }

    private void publishInitState(AgentRequest request, ExecutionContext context, String traceId, AgentEventPublisher publisher) {
        if (request.resume() && context.resumeState() != null) {
            publisher.emit(AgentEvent.resumed(traceId, context.sessionId()));
        }
        publisher.emit(AgentEvent.state(traceId, context.sessionId(), 0, AgentLoopState.INIT.name(), "ok", "Agent启动"));
        publisher.emit(AgentEvent.dialogueMode(traceId, context.sessionId(), request.dialogueMode().name(), "当前对话模式: " + request.dialogueMode().name()));
    }

    private SkillPlan resolveSkillPlan(AgentRequest request, ExecutionContext context, String traceId, AgentEventPublisher publisher) {
        SkillPlan skillPlan = new SkillPlan("未指定技能，走默认编排", List.of(), "", "");
        String skillRef = request.skillPath();
        boolean shouldResolveSkill = StringUtils.hasText(skillRef);
        if (!shouldResolveSkill) {
            SkillAutoRouter.RouteResult routeResult = skillAutoRouter.route(context.effectiveQuestion());
            publisher.emit(AgentEvent.skillRouted(
                    traceId,
                    context.sessionId(),
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
        if (!shouldResolveSkill) {
            return skillPlan;
        }
        SkillContext skillContext = new SkillContext(
                request.taskType().name(),
                request.dialogueMode().name(),
                request.runMode().name(),
                context.effectiveQuestion(),
                agentFlowSupport.buildSkillMetadata(context.resolvedRepoRoot(), skillRef)
        );
        SkillRegistry.ResolvedSkill resolvedSkill = skillRegistry.resolve(skillContext);
        publisher.emit(AgentEvent.skillSelected(traceId, context.sessionId(), resolvedSkill.skillName(), resolvedSkill.plan().summary()));
        return resolvedSkill.plan();
    }

    private void publishSkillPlan(String traceId, String sessionId, AgentEventPublisher publisher, SkillPlan skillPlan) {
        if (!skillPlan.executionSteps().isEmpty()) {
            publisher.emit(AgentEvent.plan(traceId, sessionId, "Skill steps: " + String.join(" | ", skillPlan.executionSteps())));
        }
    }

    private ExecutionContext orchestrateMultiAgentIfNeeded(
            AgentRequest request,
            ExecutionContext context,
            String traceId,
            AgentEventPublisher publisher
    ) {
        if (request.runMode() != AgentRunMode.MULTI_AGENT) {
            return context;
        }
        AgentRequest orchestrationRequest = new AgentRequest(
                request.taskType(),
                context.resolvedRepoRoot(),
                context.effectiveQuestion(),
                request.skillPath(),
                request.userId(),
                context.sessionId(),
                request.approveRiskyToolCall(),
                request.interrupt(),
                request.resume(),
                request.includeRagContext(),
                request.includeKnowledgeGraphContext(),
                request.contextLimit(),
                request.runMode(),
                request.dialogueMode()
        );
        MultiAgentCoordinator.CoordinationResult coordinationResult = multiAgentCoordinator.coordinate(
                orchestrationRequest,
                Map.of("shouldStop", (BooleanSupplier) () -> shouldInterruptExecution(context.sessionId(), context.runEpoch()))
        );
        Map<String, Object> orchestrationPayload = new LinkedHashMap<>();
        orchestrationPayload.put("planId", coordinationResult.plan().planId());
        orchestrationPayload.put("topology", coordinationResult.plan().topology().name());
        orchestrationPayload.put("taskCount", coordinationResult.plan().tasks() == null ? 0 : coordinationResult.plan().tasks().size());
        orchestrationPayload.put("executionMode", coordinationResult.executionMode());
        orchestrationPayload.put("planNarrative", coordinationResult.plan().metadata().getOrDefault("planNarrative", ""));
        orchestrationPayload.put("boundaryNotes", coordinationResult.plan().metadata().getOrDefault("boundaryNotes", List.of()));
        orchestrationPayload.put("taskBriefs", coordinationResult.plan().metadata().getOrDefault("taskBriefs", List.of()));
        orchestrationPayload.put("lifecycleEvents", coordinationResult.lifecycleEvents());
        orchestrationPayload.put("taskStates", coordinationResult.executionState().taskStates());
        orchestrationPayload.put("inboxOffsets", coordinationResult.executionState().inboxOffsets());
        orchestrationPayload.put("currentRound", coordinationResult.executionState().currentRound());
        publisher.emit(AgentEvent.orchestrationPlan(
                traceId,
                context.sessionId(),
                0,
                orchestrationPayload
        ));
        AgentContextSession nextSession = agentContextManager.append(
                context.sessionId(),
                context.contextSession(),
                new AgentContextItem(
                        "multi_agent",
                        "orchestration_plan_" + coordinationResult.plan().planId(),
                        coordinationResult.summary(),
                        Map.of(
                                "planId", coordinationResult.plan().planId(),
                                "topology", coordinationResult.plan().topology().name(),
                                "executionMode", coordinationResult.executionMode(),
                                "taskStates", coordinationResult.executionState().taskStates(),
                                "inboxOffsets", coordinationResult.executionState().inboxOffsets(),
                                "fallbackState", coordinationResult.executionState().fallbackState()
                        )
                ),
                com.lumisight.core.support.context.AgentContextAppendOptions.system()
        );
        coordinationResult.executionState().taskStates().forEach((taskId, status) -> publisher.emit(
                AgentEvent.multiAgentTaskStatus(
                        traceId,
                        context.sessionId(),
                        0,
                        taskId,
                        status.name(),
                        Map.of("executionMode", coordinationResult.executionMode())
                )
        ));
        coordinationResult.lifecycleEvents().forEach(event -> {
            String[] parts = event.split(":", 3);
            String agentId = parts.length > 0 ? parts[0] : "";
            String action = parts.length > 1 ? parts[1] : event;
            String taskId = parts.length > 2 ? parts[2] : "";
            publisher.emit(AgentEvent.teamAgentLifecycle(
                    traceId,
                    context.sessionId(),
                    0,
                    agentId,
                    action,
                    taskId.isBlank() ? Map.of("raw", event) : Map.of("raw", event, "taskId", taskId)
            ));
        });
        for (var result : coordinationResult.results()) {
            publisher.emit(AgentEvent.subagentResult(
                    traceId,
                    context.sessionId(),
                    0,
                    result.taskId(),
                    result.success(),
                    result.summary(),
                    Map.of(
                            "agentName", result.agentName(),
                            "confidence", result.confidence(),
                            "suggestedActions", result.suggestedActions()
                    )
            ));
            nextSession = agentContextManager.append(
                    context.sessionId(),
                    nextSession,
                    new AgentContextItem(
                            "subagent",
                            result.taskId(),
                            result.summary(),
                            Map.of(
                                    "agentName", result.agentName(),
                                    "success", result.success(),
                                    "findings", result.findings(),
                                    "evidenceRefs", result.evidenceRefs(),
                                    "suggestedActions", result.suggestedActions(),
                                    "confidence", result.confidence(),
                                    "payload", result.payload()
                            )
                    ),
                    com.lumisight.core.support.context.AgentContextAppendOptions.system()
            );
        }
        if (coordinationResult.results().isEmpty()) {
            publisher.emit(AgentEvent.multiAgentFallback(traceId, context.sessionId(), 0, "未获得可用的子任务结果，回退主 Agent 直跑"));
        }
        return context.withContextSession(nextSession);
    }

    private ResumeHandlingResult handlePendingResumeDecision(
            AgentRequest request,
            ExecutionContext context,
            String traceId,
            AgentEventPublisher publisher,
            Set<AgentToolPermission> enabledPermissions
    ) {
        AgentConversationManager.ConversationState resumeState = context.resumeState();
        if (!request.resume() || resumeState == null || resumeState.pendingDecision() == null) {
            return new ResumeHandlingResult(context, false);
        }
        if (!request.approveRiskyToolCall()) {
            publisher.emit(AgentEvent.humanGate(
                    traceId,
                    context.sessionId(),
                    context.startRound(),
                    resumeState.pendingDecision().toolName(),
                    "检测到待确认写操作，请设置 approveRiskyToolCall=true 后继续。"
            ));
            return new ResumeHandlingResult(context, true);
        }
        AgentToolExecutionResult gatedResult = agentLoopOrchestrator.executePendingDecision(
                resumeState.pendingDecision(),
                enabledPermissions,
                context.limit(),
                context.sessionId(),
                context.startRound(),
                context.effectiveQuestion()
        );
        publisher.emit(AgentEvent.toolResult(traceId, context.sessionId(), context.startRound(), gatedResult));
        AgentContextSession nextSession = agentContextManager.appendToolResult(
                context.sessionId(),
                context.contextSession(),
                context.startRound(),
                gatedResult
        ).session();
        saveRunningState(context.sessionId(), context.effectiveQuestion(), nextSession, context.startRound() + 1);
        return new ResumeHandlingResult(context.withContextSession(nextSession).withStartRound(context.startRound() + 1), false);
    }

    private boolean emitPlanIfNeeded(
            AgentRequest request,
            ExecutionContext context,
            SkillPlan skillPlan,
            String traceId,
            AgentEventPublisher publisher,
            RelevantMemoryContext relevantMemoryContext
    ) {
        if (request.runMode() != AgentRunMode.PLAN) {
            return false;
        }
        if (shouldInterruptExecution(context.sessionId(), context.runEpoch())) {
            appendInterruptedEvents(traceId, context.sessionId(), 0, context.effectiveQuestion(), context.contextSession(), publisher);
            return true;
        }
        publisher.emit(AgentEvent.state(traceId, context.sessionId(), 0, AgentLoopState.PLAN.name(), "running", "开始生成计划"));
        fireHook(AgentHookPoint.BEFORE_PLAN, context.sessionId(), 0, context.effectiveQuestion(), null, Map.of());
        String plan = streamingChatClientSupport.collect(
                llmChatClient,
                agentPromptService.systemPrompt(request.taskType(), relevantMemoryContext),
                agentPromptService.planPrompt(request, skillPlan),
                () -> !shouldInterruptExecution(context.sessionId(), context.runEpoch()),
                null
        );
        publisher.emit(AgentEvent.plan(traceId, context.sessionId(), plan));
        fireHook(AgentHookPoint.AFTER_PLAN, context.sessionId(), 0, context.effectiveQuestion(), null, Map.of("plan", plan));
        return false;
    }

    private void emitFinalAnswer(
            AgentRequest request,
            ExecutionContext context,
            SkillPlan skillPlan,
            AgentLoopOrchestrator.OrchestrationResult orchestrationResult,
            String traceId,
            AgentEventPublisher publisher,
            RelevantMemoryContext relevantMemoryContext
    ) {
        agentFinalResponseEmitter.emit(
                request,
                context.sessionId(),
                context.runEpoch(),
                context.effectiveQuestion(),
                context.limit(),
                skillPlan,
                context.contextSession(),
                orchestrationResult,
                traceId,
                publisher,
                relevantMemoryContext,
                () -> fireHook(AgentHookPoint.BEFORE_FINAL, context.sessionId(), orchestrationResult.finalRound(), context.effectiveQuestion(), null, Map.of("directAnswer", StringUtils.hasText(orchestrationResult.directAnswer()))),
                () -> shouldInterruptExecution(context.sessionId(), context.runEpoch()),
                interruptedSession -> appendInterruptedEvents(traceId, context.sessionId(), orchestrationResult.finalRound(), context.effectiveQuestion(), interruptedSession, publisher)
        );
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

    private void appendInterruptedEvents(
            String traceId,
            String sessionId,
            int round,
            String effectiveQuestion,
            AgentContextSession contextSession,
            AgentEventPublisher publisher
    ) {
        publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.INTERRUPTED.name(), "interrupted", "会话中断"));
        publisher.emit(AgentEvent.interrupted(traceId, sessionId, round));
        saveRunningState(sessionId, effectiveQuestion, contextSession, round);
    }

    private void saveRunningState(String sessionId, String effectiveQuestion, AgentContextSession contextSession, int nextRound) {
        conversationManager.saveRunning(
                sessionId,
                effectiveQuestion,
                agentContextManager.snapshotContexts(contextSession),
                contextSession,
                nextRound
        );
    }

    private void fireHook(AgentHookPoint point, String sessionId, int round, String question, String toolName, Map<String, Object> metadata) {
        agentHookDispatcher.fire(point, new com.lumisight.hooks.AgentHookContext(
                sessionId,
                round,
                question,
                toolName,
                metadata == null ? Map.of() : metadata
        ));
    }

    private record ExecutionContext(
            String sessionId,
            long runEpoch,
            AgentConversationManager.ConversationState resumeState,
            String effectiveQuestion,
            String resolvedRepoRoot,
            int limit,
            AgentContextSession contextSession,
            int startRound
    ) {
        private ExecutionContext withContextSession(AgentContextSession nextContextSession) {
            return new ExecutionContext(sessionId, runEpoch, resumeState, effectiveQuestion, resolvedRepoRoot, limit, nextContextSession, startRound);
        }

        private ExecutionContext withStartRound(int nextStartRound) {
            return new ExecutionContext(sessionId, runEpoch, resumeState, effectiveQuestion, resolvedRepoRoot, limit, contextSession, nextStartRound);
        }
    }

    private record ResumeHandlingResult(ExecutionContext context, boolean completed) {
    }
}
