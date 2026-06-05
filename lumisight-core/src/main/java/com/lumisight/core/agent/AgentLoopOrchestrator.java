package com.lumisight.core.agent;

import com.lumisight.core.agent.multiagent.service.MultiAgentExecutionContext;
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
import com.lumisight.core.support.context.AgentContextEntry;
import com.lumisight.core.support.context.AgentContextManager;
import com.lumisight.core.support.context.AgentContextProjection;
import com.lumisight.core.support.context.AgentContextSession;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.AgentToolRegistry;
import com.lumisight.hooks.AgentHookContext;
import com.lumisight.hooks.AgentHookDispatcher;
import com.lumisight.hooks.AgentHookPoint;
import com.lumisight.memory.RelevantMemoryContext;
import com.lumisight.skills.runtime.SkillPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
class AgentLoopOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopOrchestrator.class);
    private static final int MAX_TOOL_ROUNDS = 6;
    private static final String AUTO_SELF_HEAL_SOURCE_ID = "auto_self_heal";

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
    private final AgentSelfHealProperties selfHealProperties;

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
            AgentContextManager agentContextManager,
            AgentSelfHealProperties selfHealProperties
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
        this.selfHealProperties = selfHealProperties;
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
            long runEpoch,
            RelevantMemoryContext memoryContext
    ) {
        int lastRound = Math.max(0, startRound - 1);
        MultiAgentExecutionContext.Context executionContext = MultiAgentExecutionContext.current();
        int maxRounds = MAX_TOOL_ROUNDS;
        if (executionContext != null && executionContext.maxRounds() > 0) {
            maxRounds = Math.min(MAX_TOOL_ROUNDS, executionContext.maxRounds());
        }
        for (int round = startRound; round <= maxRounds; round++) {
            lastRound = round;
            if (executionContext != null && executionContext.isDeadlineExceeded()) {
                publisher.emit(AgentEvent.state(traceId, sessionId, round, AgentLoopState.INTERRUPTED.name(), "timeout", "达到本次子任务的安全时间上限"));
                return new OrchestrationResult("已达到当前子任务的安全时间上限，请基于已收集证据收敛结论。", false, false, Math.max(0, round - 1), contextSession);
            }
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
            emitContextProjection(traceId, sessionId, round, "DECISION", decisionProjection, publisher);
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
                            request.runMode(),
                            request.dialogueMode(),
                            enabledPermissions,
                            agentToolRegistry,
                            skillPlan,
                            memoryContext
                    ),
                    agentPromptService.orchestratorUserPrompt(
                            agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId),
                            decisionContexts,
                            limit,
                            round,
                            maxRounds,
                            skillPlan
                    ),
                    () -> !shouldInterruptExecution(sessionId, runEpoch)
                            && (executionContext == null || !executionContext.isDeadlineExceeded()),
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
                if ("task_subagent".equals(toolCall.toolName())) {
                    publisher.emit(AgentEvent.subagentSpawned(
                            traceId,
                            sessionId,
                            round,
                            "",
                            String.valueOf(toolCall.args() == null ? "" : toolCall.args().getOrDefault("capability", "CODE_EXPLAIN"))
                    ));
                }
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
                emitMultiAgentToolEvents(traceId, sessionId, round, batchResult, publisher);
                if (!"ok".equals(batchResult.status()) || containsToolError(batchResult.items())) {
                    log.warn("agent_loop tool_result_error, sessionId={}, round={}, toolName={}, status={}, message={}, items={}",
                            sessionId, round, batchResult.toolName(), batchResult.status(), batchResult.message(),
                            summarizeContextItems(batchResult.items()));
                }
                AgentContextManager.ToolAppendResult appendResult = agentContextManager.appendToolResult(sessionId, contextSession, round, batchResult);
                contextSession = appendResult.session();
                producedContext = producedContext || appendResult.producedContext();
            }
            AutoSelfHealOutcome autoSelfHealOutcome = runAutoSelfHealIfNeeded(
                    request,
                    effectiveQuestion,
                    sessionId,
                    traceId,
                    round,
                    contextSession,
                    limit,
                    enabledPermissions,
                    batchResults,
                    publisher
            );
            contextSession = autoSelfHealOutcome.contextSession();
            producedContext = producedContext || autoSelfHealOutcome.producedContext();
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
        if (requiresSelfHealPass(request) && hasPendingSelfHealFailure(contextSession)) {
            String reason = "最近一次代码修改的自动编译/lint 验证尚未通过，请继续修复并再次验证。";
            publisher.emit(AgentEvent.verifyResult(traceId, sessionId, round, false, reason));
            return new FinalDecisionOutcome(null, reason);
        }
        AgentContextProjection verifyProjection = agentContextManager.projectForVerification(
                sessionId,
                contextSession,
                agentFlowSupport.withQuestion(request, effectiveQuestion, sessionId)
        );
        emitContextProjection(traceId, sessionId, round, "VERIFY", verifyProjection, publisher);
        List<AgentContextItem> verifyContexts = verifyProjection.contexts();
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

    private AutoSelfHealOutcome runAutoSelfHealIfNeeded(
            AgentRequest request,
            String effectiveQuestion,
            String sessionId,
            String traceId,
            int round,
            AgentContextSession contextSession,
            int limit,
            Set<AgentToolPermission> enabledPermissions,
            List<AgentToolExecutionResult> batchResults,
            AgentEventPublisher publisher
    ) {
        if (!isBugFixRequest(request) || !selfHealProperties.isEnabled()) {
            return new AutoSelfHealOutcome(contextSession, false);
        }
        List<String> changedJavaFiles = collectChangedJavaFiles(batchResults);
        if (changedJavaFiles.isEmpty()) {
            return new AutoSelfHealOutcome(contextSession, false);
        }
        List<ToolDecision> validationDecisions = buildValidationDecisions(changedJavaFiles);
        if (validationDecisions.isEmpty()) {
            return new AutoSelfHealOutcome(contextSession, false);
        }

        publisher.emit(AgentEvent.state(
                traceId,
                sessionId,
                round,
                "SELF_HEAL",
                "running",
                "检测到代码修改，开始自动执行编译/lint 验证"
        ));
        boolean producedContext = false;
        List<AgentToolExecutionResult> validationResults = new ArrayList<>();
        for (ToolDecision validationDecision : validationDecisions) {
            publisher.emit(AgentEvent.toolCall(traceId, sessionId, round, validationDecision.toolName(), validationDecision.args()));
            AgentToolExecutionResult validationResult = executePendingDecision(
                    validationDecision,
                    enabledPermissions,
                    limit,
                    sessionId,
                    round,
                    effectiveQuestion
            );
            validationResults.add(validationResult);
            publisher.emit(AgentEvent.toolResult(traceId, sessionId, round, validationResult));
            if (!"ok".equals(validationResult.status()) || containsToolError(validationResult.items())) {
                log.warn("agent_loop self_heal_validation_error, sessionId={}, round={}, toolName={}, status={}, message={}, items={}",
                        sessionId, round, validationResult.toolName(), validationResult.status(), validationResult.message(),
                        summarizeContextItems(validationResult.items()));
            }
            AgentContextManager.ToolAppendResult appendResult = agentContextManager.appendToolResult(sessionId, contextSession, round, validationResult);
            contextSession = appendResult.session();
            producedContext = producedContext || appendResult.producedContext();
        }

        List<String> failureReasons = collectValidationFailures(validationResults);
        boolean passed = failureReasons.isEmpty();
        String verifyReason = passed
                ? "自动验证通过，最近代码修改已通过编译/lint 检查。"
                : String.join("；", failureReasons);
        publisher.emit(AgentEvent.verifyResult(traceId, sessionId, round, passed, verifyReason));
        publisher.emit(AgentEvent.state(
                traceId,
                sessionId,
                round,
                "SELF_HEAL",
                passed ? "ok" : "retry",
                passed ? "自动验证通过，允许进入最终收尾" : "自动验证未通过，继续迭代修复"
        ));
        contextSession = agentContextManager.append(sessionId, contextSession, new AgentContextItem(
                "verifier",
                AUTO_SELF_HEAL_SOURCE_ID,
                passed
                        ? "自动验证通过: 最近代码修改已通过编译/lint 检查。"
                        : "自动验证未通过: " + verifyReason + "。请继续修复并再次验证。",
                Map.of(
                        "round", round,
                        "passed", passed,
                        "files", changedJavaFiles,
                        "tools", validationDecisions.stream().map(ToolDecision::toolName).toList()
                )
        ), AgentContextAppendOptions.verifier());
        return new AutoSelfHealOutcome(contextSession, true);
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

    private void emitContextProjection(
            String traceId,
            String sessionId,
            int round,
            String purpose,
            AgentContextProjection projection,
            AgentEventPublisher publisher
    ) {
        if (projection == null || projection.stages() == null || projection.stages().isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stages", projection.stages().stream().map(Enum::name).toList());
        payload.put("estimatedTokens", projection.estimatedTokens());
        payload.put("collapsed", projection.collapsed());
        payload.put("autoCompacted", projection.autoCompacted());
        if (projection.metrics() != null && !projection.metrics().isEmpty()) {
            payload.put("metrics", projection.metrics());
        }
        publisher.emit(AgentEvent.contextCompression(traceId, sessionId, round, purpose, payload));
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

    private void emitMultiAgentToolEvents(
            String traceId,
            String sessionId,
            int round,
            AgentToolExecutionResult batchResult,
            AgentEventPublisher publisher
    ) {
        if (batchResult == null || batchResult.items() == null || !"task_subagent".equals(batchResult.toolName()) || batchResult.items().isEmpty()) {
            return;
        }
        AgentContextItem item = batchResult.items().getFirst();
        Object taskId = item.metadata() == null ? null : item.metadata().get("taskId");
        Object success = item.metadata() == null ? null : item.metadata().get("success");
        publisher.emit(AgentEvent.subagentResult(
                traceId,
                sessionId,
                round,
                taskId == null ? "" : String.valueOf(taskId),
                success instanceof Boolean ok && ok,
                item.content()
        ));
    }

    private boolean isBugFixRequest(AgentRequest request) {
        return request.taskType() != null && "BUG_FIX".equals(request.taskType().name());
    }

    private boolean requiresSelfHealPass(AgentRequest request) {
        return isBugFixRequest(request) && selfHealProperties.isEnabled() && selfHealProperties.isRequireSuccessBeforeFinal();
    }

    private boolean hasPendingSelfHealFailure(AgentContextSession contextSession) {
        if (contextSession == null || contextSession.entries() == null || contextSession.entries().isEmpty()) {
            return false;
        }
        List<AgentContextEntry> entries = contextSession.entries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            AgentContextItem item = entries.get(i).item();
            if (!"verifier".equals(item.sourceType()) || !AUTO_SELF_HEAL_SOURCE_ID.equals(item.sourceId())) {
                continue;
            }
            Object passed = item.metadata() == null ? null : item.metadata().get("passed");
            return !(passed instanceof Boolean ok) || !ok;
        }
        return false;
    }

    private List<String> collectChangedJavaFiles(List<AgentToolExecutionResult> batchResults) {
        if (batchResults == null || batchResults.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> files = new LinkedHashSet<>();
        int maxFiles = Math.max(1, selfHealProperties.getMaxValidationFiles());
        for (AgentToolExecutionResult batchResult : batchResults) {
            if (batchResult.items() == null) {
                continue;
            }
            for (AgentContextItem item : batchResult.items()) {
                if (!"writeRepoFile".equals(item.sourceId()) || item.metadata() == null) {
                    continue;
                }
                Object sourceFile = item.metadata().get("sourceFile");
                if (sourceFile instanceof String path && path.endsWith(".java")) {
                    files.add(path);
                    if (files.size() >= maxFiles) {
                        return List.copyOf(files);
                    }
                }
            }
        }
        return List.copyOf(files);
    }

    private List<ToolDecision> buildValidationDecisions(List<String> changedJavaFiles) {
        if (changedJavaFiles == null || changedJavaFiles.isEmpty()) {
            return List.of();
        }
        List<ToolDecision> decisions = new ArrayList<>();
        int maxFiles = Math.max(1, selfHealProperties.getMaxValidationFiles());
        for (String sourceFile : changedJavaFiles.stream().limit(maxFiles).toList()) {
            if (selfHealProperties.isRunCompile()) {
                decisions.add(new ToolDecision(
                        "tool",
                        "compileJava",
                        Map.of("sourceFile", sourceFile, "maxFiles", 1),
                        List.of(),
                        null,
                        "自动编译验证最近修改的 Java 文件",
                        null
                ));
            }
            if (selfHealProperties.isRunLint()) {
                decisions.add(new ToolDecision(
                        "tool",
                        "lintJavaByJdtls",
                        Map.of("sourceFile", sourceFile, "maxFiles", 1),
                        List.of(),
                        null,
                        "自动 lint 验证最近修改的 Java 文件",
                        null
                ));
            }
        }
        return decisions;
    }

    private List<String> collectValidationFailures(List<AgentToolExecutionResult> validationResults) {
        if (validationResults == null || validationResults.isEmpty()) {
            return List.of("未执行任何自动验证工具");
        }
        List<String> failures = new ArrayList<>();
        for (AgentToolExecutionResult validationResult : validationResults) {
            if (validationResult == null) {
                continue;
            }
            if (!"ok".equals(validationResult.status()) || containsToolError(validationResult.items())) {
                failures.add(validationResult.toolName() + " 执行失败");
                continue;
            }
            for (AgentContextItem item : validationResult.items()) {
                if ("compileJava".equals(item.sourceId()) && isCompileFailure(item)) {
                    failures.add("compileJava 未通过: " + summarizeFirstIssue(item));
                }
                if ("lintJavaByJdtls".equals(item.sourceId()) && isLintFailure(item)) {
                    failures.add("lintJavaByJdtls 发现问题: " + summarizeFirstIssue(item));
                }
            }
        }
        return failures;
    }

    private boolean isCompileFailure(AgentContextItem item) {
        Object success = item.metadata() == null ? null : item.metadata().get("success");
        return success instanceof Boolean ok && !ok;
    }

    private boolean isLintFailure(AgentContextItem item) {
        Object issuesCount = item.metadata() == null ? null : item.metadata().get("issuesCount");
        return issuesCount instanceof Number number && number.intValue() > 0;
    }

    private String summarizeFirstIssue(AgentContextItem item) {
        if (item.metadata() == null) {
            return item.content();
        }
        Object issues = item.metadata().get("issues");
        if (issues instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> first) {
            Object file = first.get("file");
            Object line = first.get("line");
            Object message = first.get("message");
            StringBuilder builder = new StringBuilder();
            if (file != null && StringUtils.hasText(String.valueOf(file))) {
                builder.append(file);
            }
            if (line != null) {
                if (!builder.isEmpty()) {
                    builder.append(":");
                }
                builder.append(line);
            }
            if (message != null && StringUtils.hasText(String.valueOf(message))) {
                if (!builder.isEmpty()) {
                    builder.append(" ");
                }
                builder.append(message);
            }
            if (!builder.isEmpty()) {
                return builder.toString();
            }
        }
        return item.content();
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

    private record AutoSelfHealOutcome(AgentContextSession contextSession, boolean producedContext) {
    }

    private record FinalDecisionOutcome(OrchestrationResult terminalResult, String verifyFailureReason) {
    }
}
