package com.lumisight.core.agent;

import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.support.AgentRequestValidators;
import com.lumisight.core.support.AgentSessionContextStore;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.FluxSink;

import java.util.UUID;
/**
 * Agent 对外入口服务。
 * 这里只处理外部请求入口控制，真正执行统一交给 AgentExecutionTaskDispatcher。
 */
@Service
public class AgentRunApplicationService {

    private final AgentSessionContextStore conversationManager;
    private final AgentExecutionTaskDispatcher agentExecutionTaskDispatcher;
    private final PrimaryMultiAgentExecutionStrategy primaryMultiAgentExecutionStrategy;

    AgentRunApplicationService(
            AgentSessionContextStore conversationManager,
            AgentExecutionTaskDispatcher agentExecutionTaskDispatcher,
            PrimaryMultiAgentExecutionStrategy primaryMultiAgentExecutionStrategy
    ) {
        this.conversationManager = conversationManager;
        this.agentExecutionTaskDispatcher = agentExecutionTaskDispatcher;
        this.primaryMultiAgentExecutionStrategy = primaryMultiAgentExecutionStrategy;
    }

    public void executePrimary(AgentRequest request, FluxSink<AgentEvent> sink) {
        AgentRequestValidators.validate(request);
        String traceId = UUID.randomUUID().toString();
        String sessionId = StringUtils.hasText(request.sessionId()) ? request.sessionId() : UUID.randomUUID().toString();
        AgentEventPublisher publisher = AgentEventPublisher.streaming(sink);
        if (request.interrupt()) {
            conversationManager.interrupt(sessionId);
            publisher.emit(AgentEvent.interrupted(traceId, sessionId, 0));
            sink.complete();
            return;
        }
        try {
            AgentExecutionCommand command = new AgentExecutionCommand(
                    request,
                    traceId,
                    sessionId,
                    AgentExecutionProfile.primary()
            );
            agentExecutionTaskDispatcher.dispatch(command, publisher, primaryMultiAgentExecutionStrategy);
            sink.complete();
        } catch (Exception t) {
            sink.error(t);
        }
    }
}
