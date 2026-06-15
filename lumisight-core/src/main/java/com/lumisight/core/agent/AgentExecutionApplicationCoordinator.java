package com.lumisight.core.agent;

import com.lumisight.core.context.AgentExecutionState;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentLoopState;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.AgentFlowSupport;
import com.lumisight.core.support.AgentPermissionProfileResolver;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.AgentSessionContextStore;
import com.lumisight.core.support.RelevantMemoryService;
import com.lumisight.core.support.SkillAutoRouter;
import com.lumisight.core.support.StreamingChatClientSupport;
import com.lumisight.core.support.context.AgentContextAppendOptions;
import com.lumisight.core.support.context.AgentContextManager;
import com.lumisight.core.support.context.AgentContextSession;
import com.lumisight.core.context.ambient.MultiAgentExecutionScope;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.hooks.dispatcher.AgentHookDispatcher;
import com.lumisight.hooks.dto.AgentHookContext;
import com.lumisight.hooks.enums.AgentHookPoint;
import com.lumisight.memory.dto.RelevantMemoryBundle;
import com.lumisight.skills.SkillRegistry;
import com.lumisight.skills.dto.SkillContext;
import com.lumisight.skills.dto.SkillPlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * application 层执行协调器。
 * 负责在进入 kernel 前后完成策略判决、编排、权限、resume、plan 和最终答复等流程。
 */
@Component
class AgentExecutionApplicationCoordinator {

    private final AgentSessionContextStore conversationManager;
    private final SkillRegistry skillRegistry;
    private final AgentFlowSupport agentFlowSupport;
    private final AgentPermissionProfileResolver agentPermissionProfileResolver;
    private final AgentPromptService agentPromptService;
    private final SkillAutoRouter skillAutoRouter;
    private final StreamingChatClientSupport streamingChatClientSupport;
    private final AgentContextManager agentContextManager;
    private final AgentHookDispatcher agentHookDispatcher;
    private final AgentExecutionLoop agentExecutionLoop;
    private final AgentFinalResponseEmitter agentFinalResponseEmitter;
    private final RelevantMemoryService relevantMemoryService;
    private final ChatClient llmChatClient;
    private final AgentExecutionKernel agentExecutionKernel;
    private final AgentLoopTaskRunner agentLoopTaskRunner;

    AgentExecutionApplicationCoordinator(
            AgentSessionContextStore conversationManager,
            SkillRegistry skillRegistry,
            AgentFlowSupport agentFlowSupport,
            AgentPermissionProfileResolver agentPermissionProfileResolver,
            AgentPromptService agentPromptService,
            SkillAutoRouter skillAutoRouter,
            StreamingChatClientSupport streamingChatClientSupport,
            AgentContextManager agentContextManager,
            AgentHookDispatcher agentHookDispatcher,
            AgentExecutionLoop agentExecutionLoop,
            AgentFinalResponseEmitter agentFinalResponseEmitter,
            RelevantMemoryService relevantMemoryService,
            ChatClient.Builder chatClientBuilder,
            AgentExecutionKernel agentExecutionKernel,
            AgentLoopTaskRunner agentLoopTaskRunner
    ) {
        this.conversationManager = conversationManager;
        this.skillRegistry = skillRegistry;
        this.agentFlowSupport = agentFlowSupport;
        this.agentPermissionProfileResolver = agentPermissionProfileResolver;
        this.agentPromptService = agentPromptService;
        this.skillAutoRouter = skillAutoRouter;
        this.streamingChatClientSupport = streamingChatClientSupport;
        this.agentContextManager = agentContextManager;
        this.agentHookDispatcher = agentHookDispatcher;
        this.agentExecutionLoop = agentExecutionLoop;
        this.agentFinalResponseEmitter = agentFinalResponseEmitter;
        this.relevantMemoryService = relevantMemoryService;
        this.llmChatClient = chatClientBuilder.build();
        this.agentExecutionKernel = agentExecutionKernel;
        this.agentLoopTaskRunner = agentLoopTaskRunner;
    }

    AgentExecutionResult executePrepared(
            PreparedAgentExecution preparedExecution,
            AgentEventPublisher publisher,
            AgentMultiAgentExecutionStrategy multiAgentExecutionStrategy
    ) {
        AgentLoopPreparationResult preparationResult = prepareLoop(preparedExecution, publisher, multiAgentExecutionStrategy);
        if (preparationResult.completed()) {
            return preparationResult.completedResult();
        }
        AgentExecutionLoop.LoopExecutionResult loopResult = executeLoopTask(preparationResult.preparedLoopExecution(), publisher);
        return completeLoop(preparationResult.preparedLoopExecution(), loopResult, publisher);
    }

    AgentLoopPreparationResult prepareLoop(
            PreparedAgentExecution preparedExecution,
            AgentEventPublisher publisher,
            AgentMultiAgentExecutionStrategy multiAgentExecutionStrategy
    ) {
        AgentExecutionCommand command = preparedExecution.command();
        AgentExecutionState executionState = preparedExecution.executionState();
        AgentRequestContext requestContext = preparedExecution.requestContext();
        AgentRequest effectiveRequest = requestContext.effectiveRequest();
        // 预取记忆
        CompletableFuture<RelevantMemoryBundle> pendingRelevantMemory = relevantMemoryService.prefetch(new AgentRequest(
                effectiveRequest.taskType(),
                executionState.resolvedRepoRoot(),
                executionState.effectiveQuestion(),
                effectiveRequest.skillPath(),
                effectiveRequest.userId(),
                command.sessionId(),
                effectiveRequest.approveRiskyToolCall(),
                effectiveRequest.interrupt(),
                effectiveRequest.resume(),
                effectiveRequest.includeRagContext(),
                effectiveRequest.includeKnowledgeGraphContext(),
                effectiveRequest.contextLimit(),
                effectiveRequest.runMode(),
                effectiveRequest.dialogueMode()
        ));

        publishInitState(effectiveRequest, executionState, command.traceId(), publisher);
        // 获取相关的skill
        SkillPlan skillPlan = resolveSkillPlan(effectiveRequest, executionState, command.traceId(), publisher);
        RelevantMemoryBundle relevantMemoryBundle = joinRelevantMemory(pendingRelevantMemory);
        publishSkillPlan(command.traceId(), command.sessionId(), publisher, skillPlan);
        // 获取编排调度信息
        MultiAgentOrchestrationOutcome orchestrationOutcome = multiAgentExecutionStrategy.orchestrateIfNeeded(
                requestContext,
                executionState,
                publisher
        );
        executionState = orchestrationOutcome.executionState();

        Set<AgentToolPermission> enabledPermissions = agentPermissionProfileResolver
                .resolve(requestContext.effectiveRequest())
                .enabledPermissions();
        ResumeHandlingResult resumeHandling = handlePendingResumeDecision(
                requestContext.effectiveRequest(),
                executionState,
                requestContext.traceId(),
                publisher,
                enabledPermissions
        );
        if (resumeHandling.completed()) {
            return AgentLoopPreparationResult.completed(new AgentExecutionResult(executionState, AgentExecutionStatus.AWAITING_HUMAN_GATE));
        }
        executionState = resumeHandling.executionState();

        if (shouldInterruptExecution(executionState.sessionId(), executionState.runEpoch())) {
            appendInterruptedEvents(
                    requestContext.traceId(),
                    executionState.sessionId(),
                    0,
                    executionState.effectiveQuestion(),
                    executionState.contextSession(),
                    publisher
            );
            return AgentLoopPreparationResult.completed(new AgentExecutionResult(executionState, AgentExecutionStatus.INTERRUPTED));
        }
        if (requestContext.effectiveRequest().runMode() == AgentRunMode.PLAN
                && emitPlanIfNeeded(requestContext.effectiveRequest(), executionState, skillPlan, requestContext.traceId(), publisher, relevantMemoryBundle)) {
            return AgentLoopPreparationResult.completed(new AgentExecutionResult(executionState, AgentExecutionStatus.INTERRUPTED));
        }

        PreparedLoopExecution preparedLoopExecution = new PreparedLoopExecution(
                requestContext,
                executionState,
                skillPlan,
                relevantMemoryBundle,
                enabledPermissions,
                orchestrationOutcome.leadConvergenceScope() == null
                        ? MultiAgentExecutionScope.current()
                        : orchestrationOutcome.leadConvergenceScope()
        );
        return AgentLoopPreparationResult.ready(preparedLoopExecution);
    }

    AgentExecutionResult completeLoop(
            PreparedLoopExecution preparedLoopExecution,
            AgentExecutionLoop.LoopExecutionResult loopResult,
            AgentEventPublisher publisher
    ) {
        AgentRequestContext requestContext = preparedLoopExecution.requestContext();
        AgentExecutionState executionState = preparedLoopExecution.executionState();
        executionState = executionState.withContextSession(loopResult.contextSession());
        if (loopResult.askUser()) {
            return new AgentExecutionResult(executionState, AgentExecutionStatus.ASK_USER);
        }
        if (loopResult.interrupted()) {
            return new AgentExecutionResult(executionState, AgentExecutionStatus.INTERRUPTED);
        }
        if (!conversationManager.isActiveEpoch(requestContext.sessionId(), executionState.runEpoch())) {
            publisher.emit(AgentEvent.state(requestContext.traceId(), requestContext.sessionId(), 0, "STEER", "interrupted", "当前请求已被新的 STEER 问题抢占并终止。"));
            return new AgentExecutionResult(executionState, AgentExecutionStatus.INTERRUPTED);
        }
        emitFinalAnswer(
                requestContext.effectiveRequest(),
                executionState,
                preparedLoopExecution.skillPlan(),
                loopResult,
                requestContext.traceId(),
                publisher,
                preparedLoopExecution.relevantMemoryBundle()
        );
        return new AgentExecutionResult(executionState, AgentExecutionStatus.COMPLETED);
    }

    AgentExecutionLoop.LoopExecutionResult executeLoopTask(
            PreparedLoopExecution preparedLoopExecution,
            AgentEventPublisher publisher
    ) {
        AgentRequestContext requestContext = preparedLoopExecution.requestContext();
        String taskKind = requestContext.profile().kind() == AgentExecutionKind.SUB_AGENT
                ? "subagent-loop"
                : "primary-loop";
        // 到这里 application 层已经完成记忆、skill、编排、权限和 resume 判断。
        // 线程池里只跑真正的 agent loop，避免把策略判决伪装成 loop task。
        return agentLoopTaskRunner.runBlocking(new AgentLoopTask<>(
                requestContext.sessionId(),
                taskKind,
                () -> executeKernelLoop(preparedLoopExecution, publisher)
        ));
    }

    AgentExecutionLoop.LoopExecutionResult executeKernelLoop(
            PreparedLoopExecution preparedLoopExecution,
            AgentEventPublisher publisher
    ) {
        return agentExecutionKernel.executeLoop(preparedLoopExecution, publisher);
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
        AgentToolExecutionResult gatedResult = agentExecutionLoop.executePendingDecision(
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
            AgentExecutionState context,
            SkillPlan skillPlan,
            AgentExecutionLoop.LoopExecutionResult orchestrationResult,
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

    record ResumeHandlingResult(AgentExecutionState executionState, boolean completed) {
    }
}
