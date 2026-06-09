package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.model.AgentTaskType;
import com.lumisight.core.context.ambient.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.model.TopologyType;
import com.lumisight.core.agent.multiagent.port.OrchestratorAgent;
import com.lumisight.core.agent.multiagent.port.SubAgent;
import com.lumisight.core.agent.multiagent.port.TaskRouter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * todo 这里面都没有调用模型，怎么能叫做调度agent？
 */
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
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", topology == TopologyType.SERIAL_DAG ? "single-step-default" : "multi-step-orchestration");
        metadata.put("taskCount", tasks.size());
        metadata.put("planNarrative", buildPlanNarrative(context, topology, tasks));
        metadata.put("boundaryNotes", List.of(
                "主 Agent / Lead 负责最终写仓库、编译验证和最终回答",
                "team worker 只返回局部结论和证据，不直接面向用户收口",
                "当前多 Agent 是请求内协作系统，不是长期自治团队"
        ));
        metadata.put("taskBriefs", tasks.stream().map(this::taskBrief).toList());
        return new OrchestrationPlan(
                context.orchestrationId(),
                "完成用户请求: " + context.request().question(),
                tasks,
                topology,
                "汇总所有子任务结果，仅由 lead 执行最终写入或最终回答",
                Map.copyOf(metadata)
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
        builder.append("Topology: ").append(plan.topology().name()).append("\n");
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

    private SubAgentCapability mapCapability(OrchestrationContext context) {
        AgentTaskType type = context.request().taskType();
        if (type == AgentTaskType.BUG_FIX) {
            return SubAgentCapability.BUG_FIX;
        }
        return SubAgentCapability.CODE_EXPLAIN;
    }

    /**
     * todo 这个buildTask太粗糙了，可以用关键词正则粗筛，但还是需要调用模型的
     * @param context
     * @return
     */
    private List<SubAgentTask> buildTasks(OrchestrationContext context) {
        String question = context.request().question() == null ? "" : context.request().question();
        List<SubAgentTask> tasks = new ArrayList<>();
        if (question.contains("前端") && question.contains("后端")) {
            tasks.add(task(context, "frontend-analysis", "前端侧分析", "只分析前端页面、交互和浏览器侧证据", SubAgentCapability.CODE_EXPLAIN, "frontend", List.of()));
            tasks.add(task(context, "backend-analysis", "后端侧分析", "只分析接口、服务和后端日志/代码路径", mapCapability(context), "backend", List.of()));
            tasks.add(task(context, "integration-summary", "前后端收敛", "把前端和后端发现收敛成统一结论、依赖关系和下一步建议", mapCapability(context), "summary", List.of(tasks.get(0).taskId(), tasks.get(1).taskId())));
        } else if (question.contains("测试") && (question.contains("修复") || question.contains("重构"))) {
            SubAgentTask fixTask = task(context, "fix-analysis", "修复方案分析", "先聚焦代码根因、修复面和潜在影响", mapCapability(context), "fix", List.of());
            tasks.add(fixTask);
            tasks.add(task(context, "test-impact", "测试影响分析", "评估需要补哪些测试、验证路径和风险回归点", SubAgentCapability.TEST_ANALYSIS, "verify", List.of(fixTask.taskId())));
        } else if (question.contains("同时") || question.contains("分别") || question.contains("多个模块") || question.contains("多个文件")) {
            tasks.add(task(context, "evidence-scan", "证据并行扫描", "优先扫描涉及模块里的核心证据、入口和可疑代码路径", SubAgentCapability.CODE_EXPLAIN, "scan-a", List.of()));
            tasks.add(task(context, "risk-scan", "风险并行扫描", "并行检查风险点、边界条件和可能的副作用", mapCapability(context), "scan-b", List.of()));
            tasks.add(task(context, "integration-summary", "汇总收敛", "把不同扫描结果收敛成统一结论和下一步建议", mapCapability(context), "summary", List.of(tasks.get(0).taskId(), tasks.get(1).taskId())));
        } else {
            tasks.add(task(context, "primary-analysis", "主任务分解", context.request().question(), mapCapability(context), "lead", List.of()));
        }
        return tasks.stream().limit(Math.max(1, properties.getMaxTasksPerPlan())).toList();
    }

    /**
     * agent 的拓扑
     * todo 这个decideTopology太粗糙了，可以用关键词正则粗筛，但还是需要调用模型的
     * @param context
     * @param tasks
     * @return
     */
    private TopologyType decideTopology(OrchestrationContext context, List<SubAgentTask> tasks) {
        // 串行
        if (tasks.size() <= 1) {
            return TopologyType.SERIAL_DAG;
        }
        String question = context.request().question() == null ? "" : context.request().question();
        // 串行+并行
        if (question.contains("测试") && (question.contains("修复") || question.contains("重构"))) {
            return TopologyType.HYBRID;
        }
        // 并行
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

    private String buildPlanNarrative(OrchestrationContext context, TopologyType topology, List<SubAgentTask> tasks) {
        String question = context.request().question() == null ? "" : context.request().question().trim();
        String titles = tasks.stream().map(SubAgentTask::title).collect(Collectors.joining(" -> "));
        if (!StringUtils.hasText(question)) {
            return "按 " + topology.name() + " 执行 " + titles;
        }
        return "针对“" + question + "”按 " + topology.name() + " 拆成 " + titles;
    }

    private Map<String, Object> taskBrief(SubAgentTask task) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("taskId", task.taskId());
        brief.put("title", task.title());
        brief.put("capability", task.capability().name());
        brief.put("dependsOn", task.dependsOn());
        brief.put("parallelGroup", task.parallelGroup());
        return brief;
    }
}
