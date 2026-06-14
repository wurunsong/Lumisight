package com.lumisight.core.agent;

import com.lumisight.core.agent.multiagent.service.MultiAgentCoordinator;
import com.lumisight.core.agent.multiagent.service.MultiAgentModeDecider;
import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.context.AgentExecutionState;
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
import com.lumisight.hooks.dispatcher.AgentHookDispatcher;
import com.lumisight.hooks.enums.AgentHookPoint;
import com.lumisight.hooks.dto.AgentHookContext;
import com.lumisight.memory.dto.RelevantMemoryBundle;
import com.lumisight.skills.dto.SkillContext;
import com.lumisight.skills.dto.SkillPlan;
import com.lumisight.skills.SkillRegistry;
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
            AgentExecutionState executionState = prepareExecutionContext(request, sessionId);
            // 生成真正用于执行的 request，并在这里决定是否切到多 agent 路径
            AgentRequest effectiveRequest = resolveExecutionRequest(request, executionState.effectiveQuestion(), traceId, sessionId, publisher);
            // 获取该repoRoot下的项目长期记忆和根目录下的用户画像记忆
            CompletableFuture<RelevantMemoryBundle> pendingRelevantMemory = relevantMemoryService.prefetch(new AgentRequest(
                    effectiveRequest.taskType(),
                    executionState.resolvedRepoRoot(),
                    executionState.effectiveQuestion(),
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
            // open 会把 repoRoot、limit、userId 挂到当前线程作用域里，供后续工具通过 ToolRuntimeScope.required() 读取。
            // 这里的 ignored 只是为了借助 try-with-resources 在流程结束后自动 close，避免线程上下文泄漏。
            try (ToolRuntimeScope.Scope ignored = ToolRuntimeScope.open(executionState.resolvedRepoRoot(), executionState.limit(), effectiveRequest.userId())) {
                publishInitState(effectiveRequest, executionState, traceId, publisher);
                // 获取skill
                SkillPlan skillPlan = resolveSkillPlan(effectiveRequest, executionState, traceId, publisher);
                Set<AgentToolPermission> enabledPermissions = agentFlowSupport.enabledPermissions(effectiveRequest);
                RelevantMemoryBundle relevantMemoryBundle = joinRelevantMemory(pendingRelevantMemory);
                publishSkillPlan(traceId, sessionId, publisher, skillPlan);
                // 调度多agent
                // todo 这里的多agent调度有很大的问题，teamAgent最终的逻辑执行还是在CodeAssistantAgentService这个类里，会形成递归，
                //  应该把前面的公共部分做一个隔离，具体方案后面再说
                executionState = orchestrateMultiAgentIfNeeded(effectiveRequest, executionState, traceId, publisher);
                // 处理上一轮对话没完成的工具调用
                ResumeHandlingResult resumeHandling = handlePendingResumeDecision(
                        effectiveRequest,
                        executionState,
                        traceId,
                        publisher,
                        enabledPermissions
                );
                if (resumeHandling.completed()) {
                    sink.complete();
                    return;
                }
                executionState = resumeHandling.executionState();

                if (shouldInterruptExecution(executionState.sessionId(), executionState.runEpoch())) {
                    appendInterruptedEvents(traceId, executionState.sessionId(), 0, executionState.effectiveQuestion(), executionState.contextSession(), publisher);
                    sink.complete();
                    return;
                }
                // emitPlanIfNeeded只有在被中断的时候才返回true
                if (effectiveRequest.runMode() == AgentRunMode.PLAN
                        && emitPlanIfNeeded(effectiveRequest, executionState, skillPlan, traceId, publisher, relevantMemoryBundle)) {
                    sink.complete();
                    return;
                }

                AgentLoopOrchestrator.OrchestrationResult result = agentLoopOrchestrator.run(
                        effectiveRequest,
                        executionState.effectiveQuestion(),
                        skillPlan,
                        executionState.contextSession(),
                        executionState.limit(),
                        executionState.startRound(),
                        publisher,
                        sessionId,
                        traceId,
                        enabledPermissions,
                        executionState.runEpoch(),
                        relevantMemoryBundle
                );
                executionState = executionState.withContextSession(result.contextSession());

                if (result.askUser() || result.interrupted()) {
                    sink.complete();
                    return;
                }
                if (!conversationManager.isActiveEpoch(sessionId, executionState.runEpoch())) {
                    publisher.emit(AgentEvent.state(traceId, sessionId, 0, "STEER", "interrupted", "当前请求已被新的 STEER 问题抢占并终止。"));
                    sink.complete();
                    return;
                }

                emitFinalAnswer(effectiveRequest, executionState, skillPlan, result, traceId, publisher, relevantMemoryBundle);
                sink.complete();
            }
        } catch (Throwable t) {
            sink.error(t);
        }
    }

    private RelevantMemoryBundle joinRelevantMemory(CompletableFuture<RelevantMemoryBundle> pendingRelevantMemory) {
        if (pendingRelevantMemory == null) {
            return RelevantMemoryBundle.empty();
        }
        try {
            return pendingRelevantMemory.join();
        } catch (Exception e) {
            return RelevantMemoryBundle.empty();
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
    private AgentRequest resolveExecutionRequest(AgentRequest request, String effectiveQuestion, String traceId, String sessionId, AgentEventPublisher publisher) {
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
     * 准备本轮对话的初始状态，主要包含：当前提问和历史上下文
     * @param request 用户请求
     * @param sessionId 会话 ID
     * @return 当前对话的初始上下文
     */
    private AgentExecutionState prepareExecutionContext(AgentRequest request, String sessionId) {
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
        return new AgentExecutionState(sessionId, runEpoch, resumeState, effectiveQuestion, resolvedRepoRoot, limit, contextSession, startRound);
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

    private void publishInitState(AgentRequest request, AgentExecutionState context, String traceId, AgentEventPublisher publisher) {
        if (request.resume() && context.resumeState() != null) {
            publisher.emit(AgentEvent.resumed(traceId, context.sessionId()));
        }
        publisher.emit(AgentEvent.state(traceId, context.sessionId(), 0, AgentLoopState.INIT.name(), "ok", "Agent启动"));
        publisher.emit(AgentEvent.dialogueMode(traceId, context.sessionId(), request.dialogueMode().name(), "当前对话模式: " + request.dialogueMode().name()));
    }

    private SkillPlan resolveSkillPlan(AgentRequest request, AgentExecutionState context, String traceId, AgentEventPublisher publisher) {
        SkillPlan skillPlan = new SkillPlan("未指定技能，走默认编排", List.of(), "", "");
        String skillRef = request.skillPath();
        boolean shouldResolveSkill = StringUtils.hasText(skillRef);
        // 用户未制定skill，用模型判断下是否有可用的skill
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
        // 不用skill，直接返回
        if (!shouldResolveSkill) {
            return skillPlan;
        }
        // 构建skill上下文
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

    private AgentExecutionState orchestrateMultiAgentIfNeeded(
            AgentRequest request,
            AgentExecutionState context,
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
            AgentExecutionState context,
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
        // 这里是继续执行之前中断的工具调用
        // todo 这怎么又冒出来一个agentLoopOrchestrator？太多了，分工不清晰，后面整合下
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
            AgentExecutionState context,
            SkillPlan skillPlan,
            String traceId,
            AgentEventPublisher publisher,
            RelevantMemoryBundle relevantMemoryContext
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
        // 生成计划
        String plan = streamingChatClientSupport.collect(
                llmChatClient,
                agentPromptService.systemPrompt(request.taskType(), relevantMemoryContext),
                agentPromptService.planPrompt(request, skillPlan),
                () -> !shouldInterruptExecution(context.sessionId(), context.runEpoch()),
                null
        );
        // 把计划展示给用户
        publisher.emit(AgentEvent.plan(traceId, context.sessionId(), plan));
        fireHook(AgentHookPoint.AFTER_PLAN, context.sessionId(), 0, context.effectiveQuestion(), null, Map.of("plan", plan));
        return false;
    }

    private void emitFinalAnswer(
            AgentRequest request,
            AgentExecutionState context,
            SkillPlan skillPlan,
            AgentLoopOrchestrator.OrchestrationResult orchestrationResult,
            String traceId,
            AgentEventPublisher publisher,
            RelevantMemoryBundle relevantMemoryContext
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
        agentHookDispatcher.fire(point, new AgentHookContext(
                sessionId,
                round,
                question,
                toolName,
                metadata == null ? Map.of() : metadata
        ));
    }
    private record ResumeHandlingResult(AgentExecutionState executionState, boolean completed) {
    }
}
