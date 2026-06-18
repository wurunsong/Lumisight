package com.lumisight.core.agent;

import com.lumisight.core.context.AgentExecutionState;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.AgentFlowSupport;
import com.lumisight.core.support.AgentSessionContextStore;
import com.lumisight.core.support.context.AgentContextManager;
import com.lumisight.core.support.context.AgentContextSession;
import com.lumisight.core.agent.multiagent.service.MultiAgentModeDecider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 执行前准备层。
 * 负责恢复会话态、归一化 effectiveQuestion，并在真正进入 kernel 前决定 single / multi 路径。
 */
@Component
class AgentExecutionPreparationService {

    private static final int DEFAULT_CONTEXT_LIMIT = 5;

    private final AgentSessionContextStore conversationManager;
    private final AgentContextManager agentContextManager;
    private final AgentFlowSupport agentFlowSupport;
    private final MultiAgentModeDecider multiAgentModeDecider;

    AgentExecutionPreparationService(
            AgentSessionContextStore conversationManager,
            AgentContextManager agentContextManager,
            AgentFlowSupport agentFlowSupport,
            MultiAgentModeDecider multiAgentModeDecider
    ) {
        this.conversationManager = conversationManager;
        this.agentContextManager = agentContextManager;
        this.agentFlowSupport = agentFlowSupport;
        this.multiAgentModeDecider = multiAgentModeDecider;
    }

    PreparedAgentExecution prepare(AgentExecutionCommand command, AgentEventPublisher publisher) {
        // 获取会话状态
        AgentExecutionState executionState = prepareExecutionContext(command.request(), command.sessionId());
        // 解析执行请求，决定执行时的agent编排策略
        AgentRequest effectiveRequest = resolveExecutionRequest(command, executionState.effectiveQuestion(), publisher);
        return new PreparedAgentExecution(
                command,
                executionState,
                new AgentRequestContext(
                        effectiveRequest,
                        command.traceId(),
                        command.sessionId(),
                        command.profile()
                )
        );
    }

    private AgentExecutionState prepareExecutionContext(AgentRequest request, String sessionId) {
        AgentConversationManager.ConversationState sessionState = conversationManager.get(sessionId);
        long runEpoch = conversationManager.nextEpoch(sessionId);
        if (sessionState != null && sessionState.interrupted()) {
            conversationManager.saveRunning(
                    sessionId,
                    sessionState.baseQuestion(),
                    sessionState.contexts() == null ? java.util.List.of() : sessionState.contexts(),
                    sessionState.contextSession(),
                    sessionState.nextRound()
            );
            sessionState = conversationManager.get(sessionId);
        }
        String effectiveQuestion = request.question();
        String resolvedRepoRoot = agentFlowSupport.resolveRepoRoot(request.repoRoot(), request.skillPath());
        int limit = request.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : request.contextLimit();
        int startRound = 1;
        if (request.resume() && sessionState != null) {
            sessionState = normalizeResumeState(sessionState);
            startRound = sessionState.nextRound();
            if (!StringUtils.hasText(effectiveQuestion) && StringUtils.hasText(sessionState.baseQuestion())) {
                effectiveQuestion = sessionState.baseQuestion();
            }
        }
        // 同一个 sessionId 的下一轮对话默认继承历史上下文；resume 只额外复用上一轮未完成问题和 nextRound。
        AgentContextSession contextSession = agentContextManager.restore(sessionId, sessionState);
        return new AgentExecutionState(sessionId, runEpoch, sessionState, effectiveQuestion, resolvedRepoRoot, limit, contextSession, startRound);
    }

    private AgentConversationManager.ConversationState normalizeResumeState(AgentConversationManager.ConversationState sessionState) {
        if (sessionState == null || !sessionState.interrupted()) {
            return sessionState;
        }
        return new AgentConversationManager.ConversationState(
                AgentConversationManager.ConversationStatus.RUNNING,
                sessionState.baseQuestion(),
                sessionState.contexts(),
                sessionState.contextSession(),
                sessionState.nextRound(),
                false,
                sessionState.pendingDecision(),
                System.currentTimeMillis()
        );
    }

    private AgentRequest resolveExecutionRequest(
            AgentExecutionCommand command,
            String effectiveQuestion,
            AgentEventPublisher publisher
    ) {
        if (!command.profile().allowRunModeResolution()) {
            return command.request();
        }
        MultiAgentModeDecider.Decision decision = multiAgentModeDecider.decide(command.request(), effectiveQuestion);
        if (decision.multiAgentSelected()) {
            publisher.emit(AgentEvent.multiAgentSelected(
                    command.traceId(),
                    command.sessionId(),
                    0,
                    decision.effectiveRunMode().name(),
                    decision.reason(),
                    Map.of(
                            "confidence", decision.confidence(),
                            "matchedSignals", decision.matchedSignals()
                    )
            ));
        }
        if (decision.effectiveRunMode() == command.request().runMode()) {
            return command.request();
        }
        return new AgentRequest(
                command.request().taskType(),
                command.request().repoRoot(),
                command.request().question(),
                command.request().skillPath(),
                command.request().userId(),
                command.request().sessionId(),
                command.request().approveRiskyToolCall(),
                command.request().interrupt(),
                command.request().resume(),
                command.request().includeRagContext(),
                command.request().includeKnowledgeGraphContext(),
                command.request().contextLimit(),
                decision.effectiveRunMode(),
                command.request().dialogueMode()
        );
    }
}
