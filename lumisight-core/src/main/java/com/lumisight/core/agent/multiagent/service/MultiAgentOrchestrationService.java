package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.MultiAgentExecutionState;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.context.ambient.OrchestrationContext;
import com.lumisight.core.model.AgentRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多 agent 编排应用服务。
 * 对上游暴露明确的 plan / execute 两阶段入口，避免规划阶段隐式执行 sub-agent。
 */
@Component
public class MultiAgentOrchestrationService {

    private final OrchestrationPlanner orchestrationPlanner;
    private final SubAgentWavePlanner wavePlanner;
    private final SubAgentSchedulingService subAgentSchedulingService;
    private final OrchestrationSummaryService orchestrationSummaryService;

    MultiAgentOrchestrationService(
            OrchestrationPlanner orchestrationPlanner,
            SubAgentWavePlanner wavePlanner,
            SubAgentSchedulingService subAgentSchedulingService,
            OrchestrationSummaryService orchestrationSummaryService
    ) {
        this.orchestrationPlanner = orchestrationPlanner;
        this.wavePlanner = wavePlanner;
        this.subAgentSchedulingService = subAgentSchedulingService;
        this.orchestrationSummaryService = orchestrationSummaryService;
    }

    public PlannedOrchestration plan(AgentRequest request, Map<String, Object> runtimeAttributes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        if (runtimeAttributes != null && !runtimeAttributes.isEmpty()) {
            attributes.putAll(runtimeAttributes);
        }
        attributes.put("source", "multi-agent-orchestration-service");
        OrchestrationContext context = new OrchestrationContext(
                UUID.randomUUID().toString(),
                request,
                Map.copyOf(attributes)
        );
        OrchestrationPlan plan = orchestrationPlanner.createPlan(context);
        List<List<SubAgentTask>> executionWaves = buildExecutionWaves(plan);
        return new PlannedOrchestration(context, plan, executionWaves, describeWaves(plan, executionWaves));
    }

    public OrchestrationResult execute(PlannedOrchestration plannedOrchestration) {
        // 多 agent 统一走 lead + subagent 调度器。
        // serial / parallel / hybrid 是编排方式；wave 只是调度器把这些方式落到线程池时切出的执行批次。
        SubAgentSchedulingService.ExecutionResult executionResult = subAgentSchedulingService.executePlan(
                plannedOrchestration.plan(),
                plannedOrchestration.context(),
                plannedOrchestration.executionWaves()
        );
        OrchestrationPlan plan = plannedOrchestration.plan();
        plan = executionResult.executionState().plan();
        List<SubAgentResult> results = executionResult.results();
        List<String> lifecycleEvents = executionResult.lifecycleEvents();
        MultiAgentExecutionState executionState = executionResult.executionState();
        String summary = orchestrationSummaryService.summarize(plan, results, plannedOrchestration.context());
        return new OrchestrationResult(
                plannedOrchestration.context(),
                executionResult.coordinationId(),
                plan,
                plannedOrchestration.waves(),
                results,
                summary,
                "SUB_AGENT",
                lifecycleEvents,
                executionState
        );
    }

    private List<List<SubAgentTask>> buildExecutionWaves(OrchestrationPlan plan) {
        return wavePlanner.buildExecutionWaves(
                plan.tasks() == null ? List.of() : plan.tasks(),
                plan.orchestrationMode()
        );
    }

    private List<WavePlanBrief> describeWaves(OrchestrationPlan plan, List<List<SubAgentTask>> waves) {
        List<WavePlanBrief> briefs = new ArrayList<>(waves.size());
        for (int i = 0; i < waves.size(); i++) {
            List<SubAgentTask> wave = waves.get(i);
            int waveNumber = i + 1;
            briefs.add(new WavePlanBrief(
                    waveNumber,
                    wavePlanner.decideParallelism(plan.orchestrationMode(), waveNumber, wave.size()),
                    wave.stream().map(SubAgentTask::taskId).toList()
            ));
        }
        return List.copyOf(briefs);
    }

    public record PlannedOrchestration(
            OrchestrationContext context,
            OrchestrationPlan plan,
            List<List<SubAgentTask>> executionWaves,
            List<WavePlanBrief> waves
    ) {
    }

    public record WavePlanBrief(
            int waveNumber,
            int parallelism,
            List<String> taskIds
    ) {
    }

    public record OrchestrationResult(
            OrchestrationContext context,
            String coordinationId,
            OrchestrationPlan plan,
            List<WavePlanBrief> waves,
            List<SubAgentResult> results,
            String summary,
            String executionMode,
            List<String> lifecycleEvents,
            MultiAgentExecutionState executionState
    ) {
    }
}
