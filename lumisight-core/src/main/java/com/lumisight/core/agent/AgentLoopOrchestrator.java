package com.lumisight.core.agent;

import com.lumisight.core.hooks.runtime.HookedToolExecutor;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentLoopState;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.AgentDecisionParser;
import com.lumisight.core.support.AgentFinalAnswerVerifier;
import com.lumisight.core.support.AgentFlowSupport;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.AgentSessionContextStore;
import com.lumisight.core.support.StreamingChatClientSupport;
import com.lumisight.core.support.context.AgentContextAppendOptions;
import com.lumisight.core.support.context.AgentContextManager;
import com.lumisight.core.support.context.AgentContextProjection;
import com.lumisight.core.support.context.AgentContextSession;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentToolRegistry;
import com.lumisight.hooks.AgentHookContext;
import com.lumisight.hooks.AgentHookDispatcher;
import com.lumisight.hooks.AgentHookPoint;
import com.lumisight.skills.runtime.SkillPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
class AgentLoopOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopOrchestrator.class);
    private static final int MAX_TOOL_ROUNDS = 6;

    private final ChatClient llmChatClient;
    private final AgentToolRegistry agentToolRegistry;
    private final AgentPromptService agentPromptService;
    private final AgentSessionContextStore conversationManager;
    private final AgentHookDispatcher agentHookDispatcher;
    private final HookedToolExecutor hookedToolExecutor;
    private final AgentFlowSupport agentFlowSupport;
    private final AgentDecisionParser decisionParser;
    private final AgentFinalAnswerVerifier finalAnswerVerifier;
    private final StreamingChatClientSupport streamingChatClientSupport;
    private final AgentContextManager agentContextManager;

    AgentLoopOrchestrator(
            ChatClient.Builder chatClientBuilder,
            AgentToolRegistry agentToolRegistry,
            AgentPromptService agentPromptService,
            AgentSessionContextStore conversationManager,
            AgentHookDispatcher agentHookDispatcher,
            HookedToolExecutor hookedToolExecutor,
            AgentFlowSupport agentFlowSupport,
            AgentDecisionParser decisionParser,
            AgentFinalAnswerVerifier finalAnswerVerifier,
            StreamingChatClientSupport streamingChatClientSupport,
            AgentContextManager agentContextManager
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.agentToolRegistry = agentToolRegistry;
        this.agentPromptService = agentPromptService;
        this.conversationManager = conversationManager;
        this.agentHookDispatcher = agentHookDispatcher;
        this.hookedToolExecutor = hookedToolExecutor;
        this.agentFlowSupport = agentFlowSupport;
        this.decisionParser = decisionParser;
        this.finalAnswerVerifier = finalAnswerVerifier;
        this.streamingChatClientSupport = streamingChatClientSupport;
        this.agentContextManager = agentContextManager;
    }

    OrchestrationResult run(
            AgentRequest request,
            String effectiveQuestion,
            SkillPlan skillPlan,
            AgentContextSession contextSession,
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
            OrchestrationResult interruptedResult = checkInterrupted(traceId, sessionId, round, effectiveQuestion, contextSession, publisher, runEpoch);
            if (interruptedResult != null) {
                return interruptedResult;
            }

            publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.DECIDE.name(), "running", "开始决策"));
            contextSession = agentContextManager.append(sessionId, contextSession, new AgentContextItem(
                    "conversation",
                    "user_prompt_round_" + round,
                    effectiveQuestion,
                    Map.of("round", round, "role", "user")
            ), AgentContextAppendOptions.conversation());
            AgentContextProjection decisionProjection = agentContextManager.projectForDecision(
                    sessionId,
                    contextSession,
                    agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId),
                    skillPlan
            );
            contextSession = decisionProjection.session();
            List<AgentContextItem> decisionContexts = new ArrayList<>(decisionProjection.contexts());
            int currentRound = round;
            conversationManager.todoReminderContext(sessionId, currentRound).ifPresent(reminder -> {
                decisionContexts.add(reminder);
                publisher.emit(AgentEvent.state(traceId, sessionId, currentRound, "TODO_REMINDER", "reminded", "已注入 todo_write 提醒"));
            });
            log.info("agent_loop decide, sessionId={}, round={}, question={}, contextSummary={}, errorContexts={}",
                    sessionId, round, effectiveQuestion, summarizeContextRefs(decisionProjection.contexts()), summarizeErrorContexts(decisionProjection.contexts()));
            fireHook(AgentHookPoint.BEFORE_DECISION, sessionId, round, effectiveQuestion, null, Map.of("contextSize", decisionProjection.contexts().size()));
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
            interruptedResult = checkInterrupted(traceId, sessionId, round, effectiveQuestion, contextSession, publisher, runEpoch);
            if (interruptedResult != null) {
                return interruptedResult;
            }
            contextSession = agentContextManager.append(sessionId, contextSession, new AgentContextItem(
                    "conversation",
                    "model_response_round_" + round,
                    decisionRaw,
                    Map.of("round", round, "role", "assistant")
            ), AgentContextAppendOptions.conversation());
            fireHook(AgentHookPoint.AFTER_DECISION, sessionId, round, effectiveQuestion, null, Map.of("decisionRaw", decisionRaw));
            log.info("agent_loop decision_raw, sessionId={}, round={}, decisionRaw={}", sessionId, round, trimForLog(decisionRaw));
            ToolDecision decision = parseDecision(decisionRaw);

            FinalDecisionOutcome finalDecisionOutcome = evaluateFinalDecision(
                    request,
                    effectiveQuestion,
                    sessionId,
                    traceId,
                    round,
                    decision,
                    contextSession,
                    publisher
            );
            if (finalDecisionOutcome.terminalResult() != null) {
                return finalDecisionOutcome.terminalResult();
            }
            if ("final".equalsIgnoreCase(decision.action())) {
                contextSession = appendVerifyFailureIfNeeded(sessionId, round, contextSession, finalDecisionOutcome.verifyFailureReason());
                continue;
            }
            if (!"tool".equalsIgnoreCase(decision.action())) {
                break;
            }

            ToolBatchOutcome batchOutcome = executeToolBatches(
                    request,
                    effectiveQuestion,
                    sessionId,
                    traceId,
                    round,
                    contextSession,
                    limit,
                    enabledPermissions,
                    runEpoch,
                    decision,
                    publisher
            );
            if (batchOutcome.terminalResult() != null) {
                return batchOutcome.terminalResult();
            }
            contextSession = batchOutcome.contextSession();
            if (!batchOutcome.producedContext()) {
                break;
            }
        }
        return new OrchestrationResult(null, false, false, Math.max(0, lastRound), contextSession);
    }

    AgentToolExecutionResult executePendingDecision(
            ToolDecision decision,
            Set<AgentToolPermission> enabledPermissions,
            int limit,
            String sessionId,
            int round,
            String question
    ) {
        return hookedToolExecutor.execute(decision, enabledPermissions, limit, sessionId, round, question);
    }

    private ToolBatchOutcome executeToolBatches(
            AgentRequest request,
            String effectiveQuestion,
            String sessionId,
            String traceId,
            int round,
            AgentContextSession contextSession,
            int limit,
            Set<AgentToolPermission> enabledPermissions,
            long runEpoch,
            ToolDecision decision,
            AgentEventPublisher publisher
    ) {
        List<ToolDecision> toolCalls = expandToolCalls(decision);
        if (toolCalls.isEmpty()) {
            return new ToolBatchOutcome(contextSession, false, null);
        }
        List<List<ToolDecision>> batches = hookedToolExecutor.partitionToolCalls(toolCalls, enabledPermissions);
        boolean producedContext = false;
        for (List<ToolDecision> batch : batches) {
            ToolDecision gatedDecision = findHumanGatedDecision(batch);
            if (gatedDecision != null && !request.approveRiskyToolCall()) {
                conversationManager.saveWaitingForGate(
                        sessionId,
                        effectiveQuestion,
                        agentContextManager.snapshotContexts(contextSession),
                        contextSession,
                        round,
                        gatedDecision
                );
                publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.ASK_USER.name(), "waiting_user", "等待人工确认高风险工具调用"));
                publisher.emit(AgentEvent.humanGate(
                        traceId,
                        sessionId,
                        round,
                        gatedDecision.toolName(),
                        "即将执行写操作工具 `" + gatedDecision.toolName() + "`，请确认后继续（approveRiskyToolCall=true）。"
                ));
                return new ToolBatchOutcome(contextSession, false, new OrchestrationResult(null, true, false, round, contextSession));
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
            OrchestrationResult interruptedResult = checkInterrupted(traceId, sessionId, round, effectiveQuestion, contextSession, publisher, runEpoch);
            if (interruptedResult != null) {
                return new ToolBatchOutcome(contextSession, false, interruptedResult);
            }
            for (AgentToolExecutionResult batchResult : batchResults) {
                publisher.emit(AgentEvent.toolResult(traceId, sessionId, round, batchResult));
                if (!"ok".equals(batchResult.status()) || containsToolError(batchResult.items())) {
                    log.warn("agent_loop tool_result_error, sessionId={}, round={}, toolName={}, status={}, message={}, items={}",
                            sessionId, round, batchResult.toolName(), batchResult.status(), batchResult.message(),
                            summarizeContextItems(batchResult.items()));
                }
                AgentContextManager.ToolAppendResult appendResult = agentContextManager.appendToolResult(sessionId, contextSession, round, batchResult);
                contextSession = appendResult.session();
                producedContext = producedContext || appendResult.producedContext();
            }
            saveRunningState(sessionId, effectiveQuestion, contextSession, round + 1);
        }
        return new ToolBatchOutcome(contextSession, producedContext, null);
    }

    private FinalDecisionOutcome evaluateFinalDecision(
            AgentRequest request,
            String effectiveQuestion,
            String sessionId,
            String traceId,
            int round,
            ToolDecision decision,
            AgentContextSession contextSession,
            AgentEventPublisher publisher
    ) {
        if ("ask_user".equalsIgnoreCase(decision.action())) {
            String question = StringUtils.hasText(decision.askUserQuestion()) ? decision.askUserQuestion() : "我还需要你补充一些信息，才能继续。";
            conversationManager.saveWaiting(
                    sessionId,
                    effectiveQuestion,
                    agentContextManager.snapshotContexts(contextSession),
                    contextSession,
                    round
            );
            publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.ASK_USER.name(), "waiting_user", "等待用户补充信息"));
            publisher.emit(AgentEvent.askUser(traceId, sessionId, round, question));
            fireHook(AgentHookPoint.ON_ASK_USER, sessionId, round, effectiveQuestion, null, Map.of("askUserQuestion", question));
            return new FinalDecisionOutcome(new OrchestrationResult(null, true, false, round, contextSession), null);
        }
        if (!"final".equalsIgnoreCase(decision.action()) || !StringUtils.hasText(decision.finalAnswer())) {
            return new FinalDecisionOutcome(null, null);
        }
        List<AgentContextItem> verifyContexts = agentContextManager.projectForVerification(
                sessionId,
                contextSession,
                agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId)
        ).contexts();
        boolean shouldVerify = agentFlowSupport.shouldVerifyFinalAnswer(request, effectiveQuestion, decision.finalAnswer(), verifyContexts);
        AgentFinalAnswerVerifier.VerifyResult verifyResult = shouldVerify
                ? finalAnswerVerifier.verifyFinalAnswer(agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId), decision.finalAnswer(), verifyContexts)
                : new AgentFinalAnswerVerifier.VerifyResult(true, "问题不要求精确事实，跳过复核");
        if (shouldVerify) {
            publisher.emit(AgentEvent.verifyResult(traceId, sessionId, round, verifyResult.pass(), verifyResult.reason()));
        }
        if (verifyResult.pass()) {
            publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.FINAL.name(), "ok", "决策直接给出最终答案"));
            return new FinalDecisionOutcome(new OrchestrationResult(decision.finalAnswer(), false, false, round, contextSession), null);
        }
        return new FinalDecisionOutcome(null, verifyResult.reason());
    }

    private AgentContextSession appendVerifyFailureIfNeeded(
            String sessionId,
            int round,
            AgentContextSession contextSession,
            String verifyFailureReason
    ) {
        if (!StringUtils.hasText(verifyFailureReason)) {
            return contextSession;
        }
        return agentContextManager.append(sessionId, contextSession, new AgentContextItem(
                "verifier",
                "final_answer_check",
                "复核未通过: " + verifyFailureReason,
                Map.of("round", round)
        ), AgentContextAppendOptions.verifier());
    }

    private OrchestrationResult checkInterrupted(
            String traceId,
            String sessionId,
            int round,
            String effectiveQuestion,
            AgentContextSession contextSession,
            AgentEventPublisher publisher,
            long runEpoch
    ) {
        if (!shouldInterruptExecution(sessionId, runEpoch)) {
            return null;
        }
        appendInterruptedEvents(traceId, sessionId, round, effectiveQuestion, contextSession, publisher);
        return new OrchestrationResult(null, false, true, round, contextSession);
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

    private void saveRunningState(String sessionId, String effectiveQuestion, AgentContextSession contextSession, int nextRound) {
        conversationManager.saveRunning(
                sessionId,
                effectiveQuestion,
                agentContextManager.snapshotContexts(contextSession),
                contextSession,
                nextRound
        );
    }

    record OrchestrationResult(String directAnswer, boolean askUser, boolean interrupted, int finalRound, AgentContextSession contextSession) {
    }

    private record ToolBatchOutcome(AgentContextSession contextSession, boolean producedContext, OrchestrationResult terminalResult) {
    }

    private record FinalDecisionOutcome(OrchestrationResult terminalResult, String verifyFailureReason) {
    }
}
