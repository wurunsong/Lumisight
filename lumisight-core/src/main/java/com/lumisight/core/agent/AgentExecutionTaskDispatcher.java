package com.lumisight.core.agent;

import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.support.AgentRequestValidators;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 统一的 agent 执行任务分发器。
 * primary 和 sub-agent 都先完成准备，再交给 application 层做策略编排。
 * 真正进入 agent loop 时，才会被封装成 AgentLoopTask 交给线程池。
 */
@Component
public class AgentExecutionTaskDispatcher {

    private final AgentExecutionPreparationService agentExecutionPreparationService;
    private final AgentExecutionApplicationCoordinator agentExecutionApplicationCoordinator;
    private final NoopMultiAgentExecutionStrategy noopMultiAgentExecutionStrategy;

    AgentExecutionTaskDispatcher(
            AgentExecutionPreparationService agentExecutionPreparationService,
            AgentExecutionApplicationCoordinator agentExecutionApplicationCoordinator,
            NoopMultiAgentExecutionStrategy noopMultiAgentExecutionStrategy
    ) {
        this.agentExecutionPreparationService = agentExecutionPreparationService;
        this.agentExecutionApplicationCoordinator = agentExecutionApplicationCoordinator;
        this.noopMultiAgentExecutionStrategy = noopMultiAgentExecutionStrategy;
    }

    AgentExecutionResult dispatch(
            AgentExecutionCommand command,
            AgentEventPublisher publisher,
            AgentMultiAgentExecutionStrategy multiAgentExecutionStrategy
    ) {
        PreparedAgentExecution preparedExecution = agentExecutionPreparationService.prepare(command, publisher);
        AgentLoopPreparationResult preparationResult = agentExecutionApplicationCoordinator.prepareLoop(
                preparedExecution,
                publisher,
                multiAgentExecutionStrategy
        );
        if (preparationResult.completed()) {
            return preparationResult.completedResult();
        }
        AgentExecutionLoop.LoopExecutionResult loopResult = agentExecutionApplicationCoordinator.executeLoopTask(
                preparationResult.preparedLoopExecution(),
                publisher
        );
        return agentExecutionApplicationCoordinator.completeLoop(
                preparationResult.preparedLoopExecution(),
                loopResult,
                publisher
        );
    }

    public List<AgentEvent> dispatchChild(AgentRequest request, AgentExecutionProfile profile) {
        AgentRequestValidators.validate(request);
        AgentEventPublisher publisher = new AgentEventPublisher(event -> { });
        AgentExecutionCommand command = new AgentExecutionCommand(
                request,
                UUID.randomUUID().toString(),
                request.sessionId(),
                profile
        );
        dispatch(command, publisher, noopMultiAgentExecutionStrategy);
        return List.copyOf(publisher.history());
    }

    public PreparedAgentLoopTask prepareChildLoopTask(AgentRequest request, AgentExecutionProfile profile) {
        AgentRequestValidators.validate(request);
        AgentEventPublisher publisher = new AgentEventPublisher(event -> { });
        AgentExecutionCommand command = new AgentExecutionCommand(
                request,
                UUID.randomUUID().toString(),
                request.sessionId(),
                profile
        );
        PreparedAgentExecution preparedExecution = agentExecutionPreparationService.prepare(command, publisher);
        AgentLoopPreparationResult preparationResult = agentExecutionApplicationCoordinator.prepareLoop(
                preparedExecution,
                publisher,
                noopMultiAgentExecutionStrategy
        );
        String taskKind = profile.kind() == AgentExecutionKind.SUB_AGENT ? "subagent-loop" : "primary-loop";
        return new PreparedAgentLoopTask(request.sessionId(), taskKind, publisher, preparationResult);
    }

    public AgentLoopTask<CompletedAgentLoopTask> toAgentLoopTask(PreparedAgentLoopTask preparedTask) {
        if (preparedTask.completedBeforeLoop()) {
            throw new IllegalStateException("prepared task already completed before loop: " + preparedTask.taskId());
        }
        return new AgentLoopTask<>(
                preparedTask.taskId(),
                preparedTask.taskKind(),
                () -> new CompletedAgentLoopTask(
                        preparedTask,
                        agentExecutionApplicationCoordinator.executeKernelLoop(
                                preparedTask.preparationResult().preparedLoopExecution(),
                                preparedTask.publisher()
                        )
                )
        );
    }

    public List<AgentEvent> completePreparedLoopTask(CompletedAgentLoopTask completedTask) {
        PreparedAgentLoopTask preparedTask = completedTask.preparedTask();
        agentExecutionApplicationCoordinator.completeLoop(
                preparedTask.preparationResult().preparedLoopExecution(),
                completedTask.loopResult(),
                preparedTask.publisher()
        );
        return List.copyOf(preparedTask.publisher().history());
    }

    public List<AgentEvent> completedEvents(PreparedAgentLoopTask preparedTask) {
        return List.copyOf(preparedTask.publisher().history());
    }
}
