package com.lumisight.core.agent;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentLoopState;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.support.AgentMemoryAsyncService;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.AgentSessionContextStore;
import com.lumisight.core.support.context.AgentContextAppendOptions;
import com.lumisight.core.support.context.AgentContextManager;
import com.lumisight.core.support.context.AgentContextProjection;
import com.lumisight.core.support.context.AgentContextSession;
import com.lumisight.memory.dto.RelevantMemoryBundle;
import com.lumisight.skills.dto.SkillPlan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Component
class AgentFinalResponseEmitter {

    private final ChatClient llmChatClient;
    private final AgentPromptService agentPromptService;
    private final AgentSessionContextStore conversationManager;
    private final AgentContextManager agentContextManager;
    private final AgentMemoryAsyncService agentMemoryAsyncService;

    AgentFinalResponseEmitter(
            ChatClient.Builder chatClientBuilder,
            AgentPromptService agentPromptService,
            AgentSessionContextStore conversationManager,
            AgentContextManager agentContextManager,
            AgentMemoryAsyncService agentMemoryAsyncService
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.agentPromptService = agentPromptService;
        this.conversationManager = conversationManager;
        this.agentContextManager = agentContextManager;
        this.agentMemoryAsyncService = agentMemoryAsyncService;
    }

    void emit(
            AgentRequest request,
            String sessionId,
            long runEpoch,
            String effectiveQuestion,
            int limit,
            SkillPlan skillPlan,
            AgentContextSession contextSession,
            AgentExecutionLoop.LoopExecutionResult orchestrationResult,
            String traceId,
            AgentEventPublisher publisher,
            RelevantMemoryBundle memoryContext,
            Runnable beforeFinalHook,
            BooleanSupplier shouldInterruptExecution,
            Consumer<AgentContextSession> onInterrupted
    ) {
        AgentContextSession nextSession = appendDirectAnswerDraftIfNeeded(
                sessionId,
                contextSession,
                orchestrationResult.directAnswer()
        );
        AgentRequest finalRequest = new AgentRequest(
                request.taskType(),
                request.repoRoot(),
                effectiveQuestion,
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
        );
        AgentContextProjection finalProjection = agentContextManager.projectForFinal(sessionId, nextSession, finalRequest, skillPlan);
        nextSession = finalProjection.session();
        emitContextProjection(traceId, sessionId, orchestrationResult.finalRound(), "FINAL", finalProjection, publisher);
        String finalPrompt = agentPromptService.buildFinalAnswerPrompt(finalRequest, finalProjection.contexts(), limit, skillPlan, memoryContext);
        beforeFinalHook.run();
        if (shouldInterruptExecution.getAsBoolean()) {
            onInterrupted.accept(nextSession);
            return;
        }
        publisher.emit(AgentEvent.state(traceId, sessionId, orchestrationResult.finalRound(), AgentLoopState.FINAL.name(), "running", "开始流式生成最终结果"));
        streamFinalAnswer(request, sessionId, runEpoch, effectiveQuestion, orchestrationResult.finalRound(), finalPrompt, traceId, publisher, nextSession, memoryContext, shouldInterruptExecution);
    }

    private AgentContextSession appendDirectAnswerDraftIfNeeded(String sessionId, AgentContextSession contextSession, String directAnswerDraft) {
        if (!StringUtils.hasText(directAnswerDraft)) {
            return contextSession;
        }
        return agentContextManager.append(sessionId, contextSession, new AgentContextItem(
                "assistant_draft",
                "final_answer_draft",
                directAnswerDraft,
                Map.of("source", "orchestrator_final_decision")
        ), AgentContextAppendOptions.conversation());
    }

    private void streamFinalAnswer(
            AgentRequest request,
            String sessionId,
            long runEpoch,
            String effectiveQuestion,
            int finalRound,
            String finalPrompt,
            String traceId,
            AgentEventPublisher publisher,
            AgentContextSession contextSession,
            RelevantMemoryBundle memoryContext,
            BooleanSupplier shouldInterruptExecution
    ) {
        StringBuilder finalAnswerBuffer = new StringBuilder();
        llmChatClient.prompt()
                .system(agentPromptService.systemPrompt(request.taskType(), memoryContext))
                .user(finalPrompt)
                .stream()
                .content()
                .takeWhile(content -> conversationManager.isActiveEpoch(sessionId, runEpoch))
                .doOnNext(content -> {
                    finalAnswerBuffer.append(content);
                    publisher.emit(AgentEvent.token(traceId, sessionId, finalRound, content));
                })
                .blockLast();
        if (!shouldInterruptExecution.getAsBoolean()) {
            String finalAnswer = finalAnswerBuffer.toString();
            if (!finalAnswer.isBlank()) {
                publisher.emit(AgentEvent.finalText(traceId, sessionId, finalRound, finalAnswer));
                AgentContextSession nextSession = agentContextManager.append(sessionId, contextSession, new AgentContextItem(
                        "conversation",
                        "final_answer_round_" + finalRound,
                        finalAnswer,
                        Map.of("round", finalRound, "role", "assistant", "source", "final_answer")
                ), AgentContextAppendOptions.conversation());
                conversationManager.saveRunning(
                        sessionId,
                        effectiveQuestion,
                        agentContextManager.snapshotContexts(nextSession),
                        nextSession,
                        finalRound + 1
                );
                // 这里只投递记忆信号；模型反思和落盘都在专用后台队列里串行执行。
                agentMemoryAsyncService.enqueueFinalAnswer(
                        request,
                        effectiveQuestion,
                        finalAnswer,
                        memoryContext
                );
            }
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
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("stages", projection.stages().stream().map(Enum::name).toList());
        payload.put("estimatedTokens", projection.estimatedTokens());
        payload.put("collapsed", projection.collapsed());
        payload.put("autoCompacted", projection.autoCompacted());
        if (projection.metrics() != null && !projection.metrics().isEmpty()) {
            payload.put("metrics", projection.metrics());
        }
        publisher.emit(AgentEvent.contextCompression(traceId, sessionId, round, purpose, payload));
    }
}
