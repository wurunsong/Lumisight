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
    private final AgentLoopTaskRunner agentLoopTaskRunner;
    private final NoopMultiAgentExecutionStrategy noopMultiAgentExecutionStrategy;

    AgentExecutionTaskDispatcher(
            AgentExecutionPreparationService agentExecutionPreparationService,
            AgentExecutionApplicationCoordinator agentExecutionApplicationCoordinator,
            AgentLoopTaskRunner agentLoopTaskRunner,
            NoopMultiAgentExecutionStrategy noopMultiAgentExecutionStrategy
    ) {
        this.agentExecutionPreparationService = agentExecutionPreparationService;
        this.agentExecutionApplicationCoordinator = agentExecutionApplicationCoordinator;
        this.agentLoopTaskRunner = agentLoopTaskRunner;
        this.noopMultiAgentExecutionStrategy = noopMultiAgentExecutionStrategy;
    }

    AgentExecutionResult dispatch(
            AgentExecutionCommand command,
            AgentEventPublisher publisher,
            AgentMultiAgentExecutionStrategy multiAgentExecutionStrategy
    ) {
        // 1. 获取执行时需要的稳定请求状态，并在 preparation 层完成 single / multi-agent 路由。
        PreparedAgentExecution preparedExecution = agentExecutionPreparationService.prepare(command, publisher);
        // 2. 拼接公共执行材料：上下文、记忆、skill 等。这里不执行 agent loop。
        AgentExecutionApplicationCoordinator.AgentLoopAssemblyContext loopContext = agentExecutionApplicationCoordinator.prepareLoopContext(
                preparedExecution,
                publisher
        );
        // 3. 多 agent 才会让协调 agent 生成子任务计划和 wave 划分；单 agent 这里返回 skipped。
        MultiAgentPlanOutcome multiAgentPlan = agentExecutionApplicationCoordinator.planMultiAgentIfNeeded(
                loopContext,
                publisher,
                multiAgentExecutionStrategy
        );
        // 4. 多 agent 才会把规划好的 wave 投递给 sub-agent，并把 fan-in 结果汇总回 lead 上下文。
        AgentExecutionApplicationCoordinator.AgentLoopAssemblyContext coordinatedContext = agentExecutionApplicationCoordinator.executeMultiAgentPlanIfNeeded(
                loopContext,
                multiAgentPlan,
                publisher,
                multiAgentExecutionStrategy
        );
        // 5. 单 agent 直接准备自己的 loop；多 agent 准备 lead 汇总后的收敛 loop。
        PreparedAgentLoopTask preparedTask = agentExecutionApplicationCoordinator.prepareExecutableAgentLoop(
                coordinatedContext,
                publisher
        );
        if (preparedTask.completedBeforeLoop()) {
            return preparedTask.completedResult();
        }
        // 6. 真正的当前 agent loop 统一由线程池执行：single 是主 loop，multi 是 lead convergence loop。
        CompletedAgentLoopTask completedTask = agentLoopTaskRunner.runBlocking(preparedTask.executableTask());
        return agentExecutionApplicationCoordinator.completeAfterLoop(completedTask);
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
        AgentExecutionApplicationCoordinator.AgentLoopAssemblyContext loopContext = agentExecutionApplicationCoordinator.prepareLoopContext(
                preparedExecution,
                publisher
        );
        MultiAgentPlanOutcome multiAgentPlan = agentExecutionApplicationCoordinator.planMultiAgentIfNeeded(
                loopContext,
                publisher,
                noopMultiAgentExecutionStrategy
        );
        AgentExecutionApplicationCoordinator.AgentLoopAssemblyContext coordinatedContext = agentExecutionApplicationCoordinator.executeMultiAgentPlanIfNeeded(
                loopContext,
                multiAgentPlan,
                publisher,
                noopMultiAgentExecutionStrategy
        );
        return agentExecutionApplicationCoordinator.prepareExecutableAgentLoop(
                coordinatedContext,
                publisher
        );
    }

    public AgentLoopTask<CompletedAgentLoopTask> toAgentLoopTask(PreparedAgentLoopTask preparedTask) {
        return preparedTask.executableTask();
    }

    public List<AgentEvent> completePreparedLoopTask(CompletedAgentLoopTask completedTask) {
        agentExecutionApplicationCoordinator.completeAfterLoop(completedTask);
        return List.copyOf(completedTask.publisher().history());
    }

    public List<AgentEvent> completedEvents(PreparedAgentLoopTask preparedTask) {
        return List.copyOf(preparedTask.publisher().history());
    }
}
