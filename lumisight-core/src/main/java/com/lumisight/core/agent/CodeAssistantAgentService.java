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
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.AgentDecisionParser;
import com.lumisight.core.support.AgentFinalAnswerVerifier;
import com.lumisight.core.support.AgentFlowSupport;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.AgentRequestValidators;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentToolRegistry;
import com.lumisight.hooks.AgentHookContext;
import com.lumisight.hooks.AgentHookDispatcher;
import com.lumisight.hooks.AgentHookPoint;
import com.lumisight.skills.runtime.AgentSkill;
import com.lumisight.skills.runtime.SkillContext;
import com.lumisight.skills.runtime.SkillPlan;
import com.lumisight.skills.runtime.SkillRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CodeAssistantAgentService {

    private static final int MAX_TOOL_ROUNDS = 6;
    private static final int DEFAULT_CONTEXT_LIMIT = 5;

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
            AgentFinalAnswerVerifier finalAnswerVerifier
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
        if (!request.resume() && StringUtils.hasText(request.question()) && resumeState != null && "RUNNING".equalsIgnoreCase(resumeState.status())) {
            if (request.dialogueMode() == AgentDialogueMode.FOLLOW) {
                conversationManager.enqueueFollowQuestion(sessionId, request.question());
                return Flux.just(AgentEvent.state(traceId, sessionId, 0, "QUEUE", "queued", "FOLLOW: 当前问题已排队，待上一条完成后按顺序处理。"));
            }
            if (request.dialogueMode() == AgentDialogueMode.COLLECT) {
                conversationManager.mergeCollectQuestion(sessionId, request.question());
                return Flux.just(AgentEvent.state(traceId, sessionId, 0, "QUEUE", "queued", "COLLECT: 已合并到待处理问题，当前回复完成后统一处理。"));
            }
            if (request.dialogueMode() == AgentDialogueMode.STEER) {
                conversationManager.nextEpoch(sessionId);
                conversationManager.interrupt(sessionId);
            }
        }
        long runEpoch = conversationManager.nextEpoch(sessionId);
        String effectiveQuestion = request.question();
        if (request.resume() && !StringUtils.hasText(effectiveQuestion) && conversationManager.hasQueuedQuestion(sessionId)) {
            effectiveQuestion = conversationManager.pollQueuedQuestion(sessionId);
        }
        String resolvedRepoRoot = agentFlowSupport.resolveRepoRoot(request.repoRoot(), request.skillPath());
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

        try (AgentToolRuntimeContext.Scope ignored = AgentToolRuntimeContext.open(resolvedRepoRoot, limit)) {
            events.add(AgentEvent.state(traceId, sessionId, 0, AgentLoopState.INIT.name(), "ok", "Agent启动"));
            events.add(AgentEvent.dialogueMode(traceId, sessionId, request.dialogueMode().name(), agentFlowSupport.dialogueModeDescription(request.dialogueMode().name())));

            SkillContext skillContext = new SkillContext(
                    request.taskType().name(),
                    request.dialogueMode().name(),
                    request.runMode().name(),
                    effectiveQuestion,
                    agentFlowSupport.buildSkillMetadata(resolvedRepoRoot, request.skillPath())
            );
            AgentSkill skill = skillRegistry.select(skillContext);
            SkillPlan skillPlan = skill.buildPlan(skillContext);
            events.add(AgentEvent.skillSelected(traceId, sessionId, skill.skillName(), skillPlan.summary()));

            Set<AgentToolPermission> enabledPermissions = agentFlowSupport.enabledPermissions(request, skillPlan);

            if (StringUtils.hasText(request.skillPath()) && !skillPlan.executionSteps().isEmpty()) {
                events.add(AgentEvent.plan(traceId, sessionId, "Skill steps: " + String.join(" | ", skillPlan.executionSteps())));
                executeSkillSteps(skillPlan, effectiveQuestion, contexts, limit, events, sessionId, traceId, enabledPermissions);
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
                AgentToolExecutionResult gatedResult = executeToolWithHookContext(
                        resumeState.pendingDecision(),
                        enabledPermissions,
                        limit,
                        sessionId,
                        startRound,
                        effectiveQuestion
                );
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

            OrchestrationResult result = runManualOrchestration(
                    request,
                    effectiveQuestion,
                    contexts,
                    limit,
                    startRound,
                    events,
                    sessionId,
                    traceId,
                    enabledPermissions,
                    runEpoch
            );
            directAnswer = result.directAnswer();
            askUser = result.askUser();
        }

        if (askUser) {
            return Flux.fromIterable(events);
        }
        if (!conversationManager.isActiveEpoch(sessionId, runEpoch)) {
            return Flux.just(AgentEvent.state(traceId, sessionId, 0, "STEER", "interrupted", "当前请求已被新的 STEER 问题抢占并终止。"));
        }

        conversationManager.clear(sessionId);
        if (StringUtils.hasText(directAnswer)) {
            fireHook(AgentHookPoint.BEFORE_FINAL, sessionId, 0, effectiveQuestion, null, Map.of("directAnswer", true));
            events.add(AgentEvent.state(traceId, sessionId, 0, AgentLoopState.FINAL.name(), "ok", "直接输出最终结果"));
            events.add(AgentEvent.finalText(traceId, sessionId, directAnswer));
            return Flux.fromIterable(events);
        }

        AgentRequest finalRequest = agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId);
        String finalPrompt = agentPromptService.buildFinalAnswerPrompt(finalRequest, contexts, limit);
        fireHook(AgentHookPoint.BEFORE_FINAL, sessionId, 0, effectiveQuestion, null, Map.of("directAnswer", false));
        Flux<AgentEvent> stream = llmChatClient.prompt()
                .system(agentPromptService.systemPrompt(request.taskType()))
                .user(finalPrompt)
                .stream()
                .content()
                .takeWhile(content -> conversationManager.isActiveEpoch(sessionId, runEpoch))
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
            String traceId,
            Set<AgentToolPermission> enabledPermissions,
            long runEpoch
    ) {
        for (int round = startRound; round <= MAX_TOOL_ROUNDS; round++) {
            if (!conversationManager.isActiveEpoch(sessionId, runEpoch)) {
                return new OrchestrationResult(null, true);
            }
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
                            agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId),
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
                    boolean shouldVerify = agentFlowSupport.shouldVerifyFinalAnswer(request, effectiveQuestion, decision.finalAnswer(), contexts);
                    AgentFinalAnswerVerifier.VerifyResult verifyResult = shouldVerify
                            ? finalAnswerVerifier.verifyFinalAnswer(agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId), decision.finalAnswer(), contexts)
                            : new AgentFinalAnswerVerifier.VerifyResult(true, "问题不要求精确事实，跳过复核");
                    if (shouldVerify) {
                        events.add(AgentEvent.verifyResult(traceId, sessionId, round, verifyResult.pass(), verifyResult.reason()));
                    }
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

            if (agentFlowSupport.requiresHumanGate(decision) && !request.approveRiskyToolCall()) {
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
            AgentToolExecutionResult toolResult = executeToolWithHookContext(
                    decision,
                    enabledPermissions,
                    limit,
                    sessionId,
                    round,
                    effectiveQuestion
            );
            events.add(AgentEvent.toolResult(traceId, sessionId, round, toolResult));
            if (toolResult.items().isEmpty()) {
                break;
            }
            contexts.addAll(toolResult.items());
            conversationManager.saveRunning(sessionId, effectiveQuestion, contexts, round + 1);
        }
        return new OrchestrationResult(null, false);
    }

    private ToolDecision parseDecision(String raw) {
        try {
            return decisionParser.parseOrFallback(raw);
        } catch (Exception e) {
            fireHook(AgentHookPoint.ON_ERROR, "", 0, "", null, Map.of("stage", "parseDecision", "error", e.getMessage()));
            return decisionParser.fallbackDecision(e.getMessage());
        }
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
            ToolDecision decision = agentFlowSupport.mapSkillStepToDecision(step, effectiveQuestion);
            if (decision == null) {
                continue;
            }
            events.add(AgentEvent.state(traceId, sessionId, round, AgentLoopState.TOOL_CALL.name(), "running", "执行Skill步骤: " + step));
            events.add(AgentEvent.toolCall(traceId, sessionId, round, decision.toolName(), decision.args()));
            AgentToolExecutionResult toolResult = executeToolWithHookContext(
                    decision,
                    enabledPermissions,
                    limit,
                    sessionId,
                    round,
                    effectiveQuestion
            );
            events.add(AgentEvent.toolResult(traceId, sessionId, round, toolResult));
            if (!toolResult.items().isEmpty()) {
                contexts.addAll(toolResult.items());
            }
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

    private void fireHook(AgentHookPoint point, String sessionId, int round, String question, String toolName, Map<String, Object> metadata) {
        agentHookDispatcher.fire(point, new AgentHookContext(
                sessionId,
                round,
                question,
                toolName,
                metadata == null ? Map.of() : metadata
        ));
    }

    private record OrchestrationResult(String directAnswer, boolean askUser) {
    }
}
