package com.lumisight.core.agent;

import com.lumisight.core.context.AgentToolRuntimeContext;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
            RelevantMemoryService relevantMemoryService
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
            ExecutionContext context = prepareExecutionContext(request, sessionId);
            CompletableFuture<RelevantMemoryContext> pendingRelevantMemory = relevantMemoryService.prefetch(new AgentRequest(
                    request.taskType(),
                    context.resolvedRepoRoot(),
                    context.effectiveQuestion(),
                    request.skillPath(),
                    request.userId(),
                    sessionId,
                    request.approveRiskyToolCall(),
                    request.interrupt(),
                    request.resume(),
                    request.includeRagContext(),
                    request.includeKnowledgeGraphContext(),
                    request.contextLimit(),
                    request.runMode(),
                    request.dialogueMode()
            ));
            try (AgentToolRuntimeContext.Scope ignored = AgentToolRuntimeContext.open(context.resolvedRepoRoot(), context.limit(), request.userId())) {
                publishInitState(request, context, traceId, publisher);
                SkillPlan skillPlan = resolveSkillPlan(request, context, traceId, publisher);
                Set<AgentToolPermission> enabledPermissions = agentFlowSupport.enabledPermissions(request, skillPlan);
                RelevantMemoryContext relevantMemoryContext = joinRelevantMemory(pendingRelevantMemory);
                publishSkillPlan(traceId, sessionId, publisher, skillPlan);

                ResumeHandlingResult resumeHandling = handlePendingResumeDecision(
                        request,
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

                if (request.runMode() == AgentRunMode.PLAN && emitPlanIfNeeded(request, context, skillPlan, traceId, publisher, relevantMemoryContext)) {
                    sink.complete();
                    return;
                }

                AgentLoopOrchestrator.OrchestrationResult result = agentLoopOrchestrator.run(
                        request,
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

                emitFinalAnswer(request, context, skillPlan, result, traceId, publisher, relevantMemoryContext);
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

    private ExecutionContext prepareExecutionContext(AgentRequest request, String sessionId) {
        AgentConversationManager.ConversationState resumeState = conversationManager.get(sessionId);
        long runEpoch = conversationManager.nextEpoch(sessionId);
        String effectiveQuestion = request.question();
        String resolvedRepoRoot = agentFlowSupport.resolveRepoRoot(request.repoRoot(), request.skillPath());
        int limit = request.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : request.contextLimit();
        AgentContextSession contextSession = agentContextManager.restore(sessionId, resumeState);
        int startRound = 1;
        if (request.resume() && resumeState != null) {
            resumeState = normalizeResumeState(resumeState);
            contextSession = agentContextManager.restore(sessionId, resumeState);
            startRound = resumeState.nextRound();
            if (!StringUtils.hasText(effectiveQuestion) && StringUtils.hasText(resumeState.baseQuestion())) {
                effectiveQuestion = resumeState.baseQuestion();
            }
        }
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
