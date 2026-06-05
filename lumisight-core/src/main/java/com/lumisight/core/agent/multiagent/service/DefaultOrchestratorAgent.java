package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.model.AgentTaskType;
import com.lumisight.core.agent.multiagent.model.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.port.OrchestratorAgent;
import com.lumisight.core.agent.multiagent.port.SubAgent;
import com.lumisight.core.agent.multiagent.port.TaskRouter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class DefaultOrchestratorAgent implements OrchestratorAgent {

    private final TaskRouter taskRouter;

    public DefaultOrchestratorAgent(TaskRouter taskRouter) {
        this.taskRouter = taskRouter;
    }

    @Override
    public OrchestrationPlan createPlan(OrchestrationContext context) {
        SubAgentCapability capability = mapCapability(context);
        SubAgentTask task = new SubAgentTask(
                UUID.randomUUID().toString(),
                "主任务分解",
                context.request().question(),
                capability,
                Map.of("question", context.request().question()),
                "返回结构化分析结论、证据引用和建议下一步",
                List.of(),
                "lead",
                Map.of("maxRounds", 6),
                100,
                Map.of("taskType", context.request().taskType().name())
        );
        return new OrchestrationPlan(
                context.orchestrationId(),
                "完成用户请求: " + context.request().question(),
                List.of(task),
                com.lumisight.core.agent.multiagent.model.TopologyType.SERIAL_DAG,
                "汇总所有子任务结果，仅由 lead 执行最终写入或最终回答",
                Map.of("mode", "single-step-default")
        );
    }

    @Override
    public List<SubAgentResult> executePlan(OrchestrationPlan plan, OrchestrationContext context) {
        List<SubAgentResult> results = new ArrayList<>();
        for (SubAgentTask task : plan.tasks()) {
            SubAgent agent = taskRouter.route(task);
            results.add(agent.execute(task, context));
        }
        return results;
    }

    @Override
    public String summarize(OrchestrationPlan plan, List<SubAgentResult> results, OrchestrationContext context) {
        StringBuilder builder = new StringBuilder();
        builder.append("OrchestrationId: ").append(plan.planId()).append("\n");
        builder.append("Goal: ").append(plan.goal()).append("\n");
        builder.append("Results:\n");
        for (SubAgentResult result : results) {
            builder.append("- [").append(result.agentName()).append("] ")
                    .append(result.summary())
                    .append("\n");
        }
        return builder.toString();
    }

    private SubAgentCapability mapCapability(OrchestrationContext context) {
        AgentTaskType type = context.request().taskType();
        if (type == AgentTaskType.BUG_FIX) {
            return SubAgentCapability.BUG_FIX;
        }
        return SubAgentCapability.CODE_EXPLAIN;
    }
}
