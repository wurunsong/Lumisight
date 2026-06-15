package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.MultiAgentExecutionState;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.context.ambient.OrchestrationContext;
import com.lumisight.core.model.AgentRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 多 agent 编排应用服务。
 * 对上游暴露统一入口，内部再串联规划、执行模式选择和结果汇总。
 */
@Component
public class MultiAgentOrchestrationService {

    private final OrchestrationPlanner orchestrationPlanner;
    private final SubAgentSchedulingService subAgentSchedulingService;
    private final OrchestrationSummaryService orchestrationSummaryService;

    public MultiAgentOrchestrationService(
            OrchestrationPlanner orchestrationPlanner,
            SubAgentSchedulingService subAgentSchedulingService,
            OrchestrationSummaryService orchestrationSummaryService
    ) {
        this.orchestrationPlanner = orchestrationPlanner;
        this.subAgentSchedulingService = subAgentSchedulingService;
        this.orchestrationSummaryService = orchestrationSummaryService;
    }

    public OrchestrationResult orchestrate(AgentRequest request, Map<String, Object> runtimeAttributes) {
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
        // 多 agent 统一走 lead + subagent 调度器。
        // serial / parallel / hybrid 是编排方式；wave 只是调度器把这些方式落到线程池时切出的执行批次。
        SubAgentSchedulingService.ExecutionResult executionResult = subAgentSchedulingService.executePlan(plan, context);
        plan = executionResult.executionState().plan();
        List<SubAgentResult> results = executionResult.results();
        List<String> lifecycleEvents = executionResult.lifecycleEvents();
        MultiAgentExecutionState executionState = executionResult.executionState();
        String summary = orchestrationSummaryService.summarize(plan, results, context);
        return new OrchestrationResult(
                context,
                executionResult.coordinationId(),
                plan,
                results,
                summary,
                "SUB_AGENT",
                lifecycleEvents,
                executionState
        );
    }

    public record OrchestrationResult(
            OrchestrationContext context,
            String coordinationId,
            OrchestrationPlan plan,
            List<SubAgentResult> results,
            String summary,
            String executionMode,
            List<String> lifecycleEvents,
            MultiAgentExecutionState executionState
    ) {
    }
}
