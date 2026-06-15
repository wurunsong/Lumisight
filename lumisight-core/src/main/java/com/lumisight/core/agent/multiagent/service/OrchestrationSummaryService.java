package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.context.ambient.OrchestrationContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 负责编排结果的统一汇总文本，避免规划器或执行器同时承担收口职责。
 */
@Component
public class OrchestrationSummaryService {

    public String summarize(OrchestrationPlan plan, List<SubAgentResult> results, OrchestrationContext context) {
        StringBuilder builder = new StringBuilder();
        builder.append("OrchestrationId: ").append(plan.planId()).append("\n");
        builder.append("Goal: ").append(plan.goal()).append("\n");
        builder.append("OrchestrationMode: ").append(plan.orchestrationMode().name()).append("\n");
        if (plan.metadata() != null && plan.metadata().get("planNarrative") != null) {
            builder.append("Plan: ").append(plan.metadata().get("planNarrative")).append("\n");
        }
        builder.append("Boundaries:\n");
        builder.append("- 只有 lead 负责最终写入、编译验证和最终回答\n");
        builder.append("- worker 只返回局部结论与证据\n");
        builder.append("Results:\n");
        for (SubAgentResult result : results) {
            builder.append("- [").append(result.agentName()).append("] ")
                    .append(result.summary())
                    .append("\n");
        }
        return builder.toString();
    }
}
