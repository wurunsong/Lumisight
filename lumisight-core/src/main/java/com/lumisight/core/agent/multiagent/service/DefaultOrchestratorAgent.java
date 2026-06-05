package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.model.AgentTaskType;
import com.lumisight.core.agent.multiagent.model.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.model.TopologyType;
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
    private final MultiAgentProperties properties;

    public DefaultOrchestratorAgent(TaskRouter taskRouter, MultiAgentProperties properties) {
        this.taskRouter = taskRouter;
        this.properties = properties;
    }

    @Override
    public OrchestrationPlan createPlan(OrchestrationContext context) {
        List<SubAgentTask> tasks = buildTasks(context);
        TopologyType topology = decideTopology(context, tasks);
        return new OrchestrationPlan(
                context.orchestrationId(),
                "完成用户请求: " + context.request().question(),
                tasks,
                topology,
                "汇总所有子任务结果，仅由 lead 执行最终写入或最终回答",
                Map.of(
                        "mode", topology == TopologyType.SERIAL_DAG ? "single-step-default" : "multi-step-orchestration",
                        "taskCount", tasks.size()
                )
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

    private List<SubAgentTask> buildTasks(OrchestrationContext context) {
        String question = context.request().question() == null ? "" : context.request().question();
        List<SubAgentTask> tasks = new ArrayList<>();
        if (question.contains("前端") && question.contains("后端")) {
            tasks.add(task(context, "frontend-analysis", "前端侧分析", "只分析前端页面、交互和浏览器侧证据", SubAgentCapability.CODE_EXPLAIN, "frontend", List.of()));
            tasks.add(task(context, "backend-analysis", "后端侧分析", "只分析接口、服务和后端日志/代码路径", mapCapability(context), "backend", List.of()));
        } else if (question.contains("测试") && (question.contains("修复") || question.contains("重构"))) {
            SubAgentTask fixTask = task(context, "fix-analysis", "修复方案分析", "先聚焦代码根因、修复面和潜在影响", mapCapability(context), "fix", List.of());
            tasks.add(fixTask);
            tasks.add(task(context, "test-impact", "测试影响分析", "评估需要补哪些测试、验证路径和风险回归点", SubAgentCapability.TEST_ANALYSIS, "verify", List.of(fixTask.taskId())));
        } else if (question.contains("同时") || question.contains("分别") || question.contains("多个模块") || question.contains("多个文件")) {
            SubAgentTask scanTask = task(context, "codebase-scan", "局部并行扫描", "先拆出主问题涉及的不同模块，分别扫描风险和证据", SubAgentCapability.CODE_EXPLAIN, "scan", List.of());
            tasks.add(scanTask);
            tasks.add(task(context, "integration-summary", "汇总收敛", "把不同模块的发现收敛成统一结论和下一步建议", mapCapability(context), "summary", List.of(scanTask.taskId())));
        } else {
            tasks.add(task(context, "primary-analysis", "主任务分解", context.request().question(), mapCapability(context), "lead", List.of()));
        }
        return tasks.stream().limit(Math.max(1, properties.getMaxTasksPerPlan())).toList();
    }

    private TopologyType decideTopology(OrchestrationContext context, List<SubAgentTask> tasks) {
        if (tasks.size() <= 1) {
            return TopologyType.SERIAL_DAG;
        }
        String question = context.request().question() == null ? "" : context.request().question();
        if (question.contains("测试") && (question.contains("修复") || question.contains("重构"))) {
            return TopologyType.HYBRID;
        }
        return TopologyType.FAN_OUT_FAN_IN;
    }

    private SubAgentTask task(
            OrchestrationContext context,
            String taskKey,
            String title,
            String instruction,
            SubAgentCapability capability,
            String parallelGroup,
            List<String> dependsOn
    ) {
        return new SubAgentTask(
                UUID.randomUUID().toString(),
                title,
                instruction,
                capability,
                Map.of("question", context.request().question()),
                "返回结构化分析结论、证据引用和建议下一步",
                dependsOn,
                parallelGroup,
                Map.of("maxRounds", properties.getSubagentMaxRounds()),
                100,
                Map.of(
                        "taskKey", taskKey,
                        "taskType", context.request().taskType().name(),
                        "repoRoot", context.request().repoRoot()
                )
        );
    }
}
