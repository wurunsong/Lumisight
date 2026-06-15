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
