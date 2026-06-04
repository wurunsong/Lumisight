package com.lumisight.core.agent;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentLoopState;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.AgentSessionContextStore;
import com.lumisight.core.support.context.AgentContextAppendOptions;
import com.lumisight.core.support.context.AgentContextManager;
import com.lumisight.core.support.context.AgentContextProjection;
import com.lumisight.core.support.context.AgentContextSession;
import com.lumisight.skills.runtime.SkillPlan;
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

    AgentFinalResponseEmitter(
            ChatClient.Builder chatClientBuilder,
            AgentPromptService agentPromptService,
            AgentSessionContextStore conversationManager,
            AgentContextManager agentContextManager
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.agentPromptService = agentPromptService;
        this.conversationManager = conversationManager;
        this.agentContextManager = agentContextManager;
    }

    void emit(
            AgentRequest request,
            String sessionId,
            long runEpoch,
            String effectiveQuestion,
            int limit,
            SkillPlan skillPlan,
            AgentContextSession contextSession,
            AgentLoopOrchestrator.OrchestrationResult orchestrationResult,
            String traceId,
            AgentEventPublisher publisher,
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
        String finalPrompt = agentPromptService.buildFinalAnswerPrompt(finalRequest, finalProjection.contexts(), limit, skillPlan);
        beforeFinalHook.run();
        if (shouldInterruptExecution.getAsBoolean()) {
            onInterrupted.accept(nextSession);
            return;
        }
        publisher.emit(AgentEvent.state(traceId, sessionId, orchestrationResult.finalRound(), AgentLoopState.FINAL.name(), "running", "开始流式生成最终结果"));
        streamFinalAnswer(request, sessionId, runEpoch, orchestrationResult.finalRound(), finalPrompt, traceId, publisher, shouldInterruptExecution);
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
            int finalRound,
            String finalPrompt,
            String traceId,
            AgentEventPublisher publisher,
            BooleanSupplier shouldInterruptExecution
    ) {
        StringBuilder finalAnswerBuffer = new StringBuilder();
        llmChatClient.prompt()
                .system(agentPromptService.systemPrompt(request.taskType()))
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
            }
        }
    }
}
