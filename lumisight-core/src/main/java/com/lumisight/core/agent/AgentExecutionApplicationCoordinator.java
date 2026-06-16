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
    private final AgentExecutionInterruptService interruptService;

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
            AgentExecutionInterruptService interruptService
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
        this.interruptService = interruptService;
    }

    AgentLoopAssemblyContext prepareLoopContext(
            PreparedAgentExecution preparedExecution,
            AgentEventPublisher publisher
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
        return new AgentLoopAssemblyContext(
                command,
                requestContext,
                executionState,
                skillPlan,
                relevantMemoryBundle,
                null
        );
    }

    MultiAgentPlanOutcome planMultiAgentIfNeeded(
            AgentLoopAssemblyContext loopContext,
            AgentEventPublisher publisher,
            AgentMultiAgentExecutionStrategy multiAgentExecutionStrategy
    ) {
        // 这里只做多 agent 规划：决定子任务、编排方式和 wave 划分，不执行任何 sub-agent loop。
        return multiAgentExecutionStrategy.planIfNeeded(
                loopContext.requestContext(),
                loopContext.executionState(),
                publisher
        );
    }

    AgentLoopAssemblyContext executeMultiAgentPlanIfNeeded(
            AgentLoopAssemblyContext loopContext,
            MultiAgentPlanOutcome planOutcome,
            AgentEventPublisher publisher,
            AgentMultiAgentExecutionStrategy multiAgentExecutionStrategy
    ) {
        // 规划完成后才显式进入执行阶段：sub-agent wave 在这里被投递线程池，fan-in 后写回 lead 收敛上下文。
        MultiAgentOrchestrationOutcome orchestrationOutcome = multiAgentExecutionStrategy.executePlanIfNeeded(
                planOutcome,
                loopContext.requestContext(),
                publisher
        );
        MultiAgentExecutionScope.Context executionScope = orchestrationOutcome.leadConvergenceScope() == null
                ? MultiAgentExecutionScope.current()
                : orchestrationOutcome.leadConvergenceScope();
        return loopContext.withLeadCoordination(orchestrationOutcome.executionState(), executionScope);
    }

    PreparedAgentLoopTask prepareExecutableAgentLoop(
            AgentLoopAssemblyContext loopContext,
            AgentEventPublisher publisher
    ) {
        AgentRequestContext requestContext = loopContext.requestContext();
        AgentExecutionState executionState = loopContext.executionState();
        String taskKind = requestContext.profile().kind() == AgentExecutionKind.SUB_AGENT
                ? "subagent-loop"
                : "primary-loop";
        // 获取工具权限
        Set<AgentToolPermission> enabledPermissions = agentPermissionProfileResolver
                .resolve(requestContext.effectiveRequest())
                .enabledPermissions();
        // 执行上一轮没执行完的工具
        ResumeHandlingResult resumeHandling = handlePendingResumeDecision(
                requestContext.effectiveRequest(),
                executionState,
                requestContext.traceId(),
                publisher,
                enabledPermissions
        );
        if (resumeHandling.completed()) {
            return PreparedAgentLoopTask.completed(
                    requestContext.sessionId(),
                    taskKind,
                    publisher,
                    new AgentExecutionResult(executionState, AgentExecutionStatus.AWAITING_HUMAN_GATE)
            );
        }
        executionState = resumeHandling.executionState();

        AgentExecutionInterruptService.Signal signal = interruptService.currentSignal(executionState.sessionId(), executionState.runEpoch());
        if (signal.stop()) {
            appendInterruptedEvents(
                    requestContext.traceId(),
                    executionState.sessionId(),
                    0,
                    executionState.effectiveQuestion(),
                    executionState.contextSession(),
                    publisher,
                    signal
            );
            return PreparedAgentLoopTask.completed(
                    requestContext.sessionId(),
                    taskKind,
                    publisher,
                    new AgentExecutionResult(executionState, AgentExecutionStatus.INTERRUPTED)
            );
        }
        // 计划模式，先把计划返回给用户评估
        if (requestContext.effectiveRequest().runMode() == AgentRunMode.PLAN
                && emitPlanIfNeeded(requestContext.effectiveRequest(), executionState, loopContext.skillPlan(), requestContext.traceId(), publisher, loopContext.relevantMemoryBundle())) {
            return PreparedAgentLoopTask.completed(
                    requestContext.sessionId(),
                    taskKind,
                    publisher,
                    new AgentExecutionResult(executionState, AgentExecutionStatus.INTERRUPTED)
            );
        }

        PreparedLoopExecution preparedLoopExecution = new PreparedLoopExecution(
                requestContext,
                executionState,
                loopContext.skillPlan(),
                loopContext.relevantMemoryBundle(),
                enabledPermissions,
                loopContext.executionScope() == null ? MultiAgentExecutionScope.current() : loopContext.executionScope()
        );
        // application 层在这里完成 loop task 的封装；下游只需要把 executableTask 透传给线程池。
        return PreparedAgentLoopTask.ready(
                requestContext.sessionId(),
                taskKind,
                publisher,
                new AgentLoopTask<>(
                        requestContext.sessionId(),
                        taskKind,
                        () -> new CompletedAgentLoopTask(
                                requestContext.sessionId(),
                                preparedLoopExecution,
                                publisher,
                                agentExecutionKernel.executeLoop(preparedLoopExecution, publisher)
                        )
                )
        );
    }

    record AgentLoopAssemblyContext(
            AgentExecutionCommand command,
            AgentRequestContext requestContext,
            AgentExecutionState executionState,
            SkillPlan skillPlan,
            RelevantMemoryBundle relevantMemoryBundle,
            MultiAgentExecutionScope.Context executionScope
    ) {
        AgentLoopAssemblyContext withLeadCoordination(
                AgentExecutionState nextExecutionState,
                MultiAgentExecutionScope.Context nextExecutionScope
        ) {
            return new AgentLoopAssemblyContext(
                    command,
                    requestContext,
                    nextExecutionState,
                    skillPlan,
                    relevantMemoryBundle,
                    nextExecutionScope
            );
        }
    }

    AgentExecutionResult completeAfterLoop(CompletedAgentLoopTask completedTask) {
        PreparedLoopExecution preparedLoopExecution = completedTask.preparedLoopExecution();
        AgentExecutionLoop.LoopExecutionResult loopResult = completedTask.loopResult();
        AgentEventPublisher publisher = completedTask.publisher();
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
        if (request.resume() && context.sessionState() != null) {
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
        AgentConversationManager.ConversationState resumeState = context.sessionState();
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
        AgentExecutionInterruptService.Signal signal = interruptService.currentSignal(context.sessionId(), context.runEpoch());
        if (signal.stop()) {
            appendInterruptedEvents(traceId, context.sessionId(), 0, context.effectiveQuestion(), context.contextSession(), publisher, signal);
            return true;
        }
        publisher.emit(AgentEvent.state(traceId, context.sessionId(), 0, AgentLoopState.PLAN.name(), "running", "开始生成计划"));
        fireHook(AgentHookPoint.BEFORE_PLAN, context.sessionId(), 0, context.effectiveQuestion(), null, Map.of());
        String plan = streamingChatClientSupport.collect(
                llmChatClient,
                agentPromptService.systemPrompt(request.taskType(), relevantMemoryContext),
                agentPromptService.planPrompt(request, skillPlan),
                () -> interruptService.shouldContinue(context.sessionId(), context.runEpoch()),
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
                () -> interruptService.shouldStop(context.sessionId(), context.runEpoch()),
                interruptedSession -> appendInterruptedEvents(
                        traceId,
                        context.sessionId(),
                        orchestrationResult.finalRound(),
                        context.effectiveQuestion(),
                        interruptedSession,
                        publisher,
                        interruptService.currentSignal(context.sessionId(), context.runEpoch())
                )
        );
    }

    private void appendInterruptedEvents(
            String traceId,
            String sessionId,
            int round,
            String effectiveQuestion,
            AgentContextSession contextSession,
            AgentEventPublisher publisher,
            AgentExecutionInterruptService.Signal signal
    ) {
        publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.INTERRUPTED.name(), signal.status(), signal.message()));
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
