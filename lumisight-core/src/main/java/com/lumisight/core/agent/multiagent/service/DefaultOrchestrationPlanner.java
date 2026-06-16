package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.AgentOrchestrationMode;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.context.ambient.OrchestrationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 多 Agent 编排计划组装器。
 * 任务拓扑由 SubAgentTaskPlanningService 规划，编排方式由 OrchestrationModeDecider 决策；这里只负责合成 OrchestrationPlan。
 */
@Component
public class DefaultOrchestrationPlanner implements OrchestrationPlanner {

    private final SubAgentTaskPlanningService taskPlanningService;
    private final OrchestrationModeDecider orchestrationModeDecider;

    public DefaultOrchestrationPlanner(
            SubAgentTaskPlanningService taskPlanningService,
            OrchestrationModeDecider orchestrationModeDecider
    ) {
        this.taskPlanningService = taskPlanningService;
        this.orchestrationModeDecider = orchestrationModeDecider;
    }

    @Override
    public OrchestrationPlan createPlan(OrchestrationContext context) {
        List<SubAgentTask> tasks = taskPlanningService.planTasks(context);
        // 根据任务拓扑选择 serial / parallel / hybrid，wave 仍是后续执行层的批次实现。
        AgentOrchestrationMode orchestrationMode = orchestrationModeDecider.decide(context, tasks);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("mode", orchestrationMode == AgentOrchestrationMode.SERIAL ? "single-step-default" : "multi-step-orchestration");
        metadata.put("taskCount", tasks.size());
        metadata.put("planNarrative", buildPlanNarrative(context, orchestrationMode, tasks));
        metadata.put("boundaryNotes", List.of(
                "主 Agent / Lead 负责最终写仓库、编译验证和最终回答",
                "sub-agent 只返回局部结论和证据，不直接面向用户收口",
                "当前多 Agent 是请求内协作系统，不是长期自治团队"
        ));
        metadata.put("taskBriefs", tasks.stream().map(this::taskBrief).toList());
        return new OrchestrationPlan(
                context.orchestrationId(),
                "完成用户请求: " + context.request().question(),
                tasks,
                orchestrationMode,
                "汇总所有子任务结果，仅由 lead 执行最终写入或最终回答",
                Map.copyOf(metadata)
        );
    }

    private String buildPlanNarrative(OrchestrationContext context, AgentOrchestrationMode orchestrationMode, List<SubAgentTask> tasks) {
        String question = context.request().question() == null ? "" : context.request().question().trim();
        String titles = tasks.stream().map(SubAgentTask::title).collect(Collectors.joining(" -> "));
        if (!StringUtils.hasText(question)) {
            return "按 " + orchestrationMode.name() + " 编排执行 " + titles;
        }
        return "针对“" + question + "”按 " + orchestrationMode.name() + " 编排拆成 " + titles;
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
