package com.lumisight.core.agent;

import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * 对外流式入口。
 * 这里只负责把用户请求转成事件流，真正的执行主干委托给应用层和执行内核。
 */
@Service
public class AgentExecutionEntryService implements AgentExecutionEngine {

    private final AgentRunApplicationService agentRunApplicationService;

    public AgentExecutionEntryService(AgentRunApplicationService agentRunApplicationService) {
        this.agentRunApplicationService = agentRunApplicationService;
    }

    public Flux<AgentEvent> run(AgentRequest request) {
        return execute(request);
    }

    @Override
    public Flux<AgentEvent> execute(AgentRequest request) {
        return Flux.create(sink -> executeStreaming(request, sink), FluxSink.OverflowStrategy.BUFFER);
    }

    private void executeStreaming(AgentRequest request, FluxSink<AgentEvent> sink) {
        agentRunApplicationService.executePrimary(request, sink);
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
}
