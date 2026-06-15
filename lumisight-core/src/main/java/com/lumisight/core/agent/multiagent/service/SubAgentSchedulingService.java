package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.MultiAgentExecutionState;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.context.ambient.OrchestrationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 子任务调度应用服务。
 * 调度细节归 SubAgentExecutionScheduler；执行细节归 SubAgentWaveExecutor；这里只串联两者。
 */
@Component
public class SubAgentSchedulingService {

    private final SubAgentExecutionScheduler scheduler;
    private final SubAgentWaveExecutor waveExecutor;

    public SubAgentSchedulingService(
            SubAgentExecutionScheduler scheduler,
            SubAgentWaveExecutor waveExecutor
    ) {
        this.scheduler = scheduler;
        this.waveExecutor = waveExecutor;
    }

    public ExecutionResult executePlan(OrchestrationPlan plan, OrchestrationContext context) {
        SubAgentExecutionScheduler.ScheduleSession session = scheduler.open(plan, context);
        while (scheduler.shouldContinue(session)) {
            SubAgentWavePlan wavePlan = scheduler.nextWave(session).orElse(null);
            if (wavePlan == null) {
                break;
            }
            SubAgentWaveExecutionResult waveResult = waveExecutor.execute(wavePlan);
            scheduler.completeWave(session, wavePlan, waveResult);
        }
        MultiAgentExecutionState state = scheduler.finish(session);
        return new ExecutionResult(session.coordinationId(), state.childSummaries(), session.lifecycleEvents(), state);
    }

    public record ExecutionResult(
            String coordinationId,
            List<SubAgentResult> results,
            List<String> lifecycleEvents,
            MultiAgentExecutionState executionState
    ) {
    }

}
