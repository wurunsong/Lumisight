package com.lumisight.core.agent.multiagent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.config.LumisightChatModelConfig;
import com.lumisight.core.context.ambient.OrchestrationContext;
import com.lumisight.core.model.AgentTaskType;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.StreamingChatClientSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * sub-agent 任务拓扑规划器。
 * scheduler 模型负责生成 taskKey / dependsOn 等拓扑信息；启发式逻辑只作为模型失败时的保底方案。
 */
@Component
public class SubAgentTaskPlanningService {

    private static final Logger log = LoggerFactory.getLogger(SubAgentTaskPlanningService.class);

    private final MultiAgentProperties properties;
    private final ChatClient schedulerChatClient;
    private final AgentPromptService agentPromptService;
    private final StreamingChatClientSupport streamingChatClientSupport;
    private final ObjectMapper objectMapper;

    public SubAgentTaskPlanningService(
            MultiAgentProperties properties,
            @Qualifier(LumisightChatModelConfig.SCHEDULER_CHAT_CLIENT_BUILDER) ChatClient.Builder schedulerChatClientBuilder,
            AgentPromptService agentPromptService,
            StreamingChatClientSupport streamingChatClientSupport,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.schedulerChatClient = schedulerChatClientBuilder.build();
        this.agentPromptService = agentPromptService;
        this.streamingChatClientSupport = streamingChatClientSupport;
        this.objectMapper = objectMapper;
    }

    public List<SubAgentTask> planTasks(OrchestrationContext context) {
        try {
            List<SubAgentTask> tasks = planBySchedulerModel(context);
            if (!tasks.isEmpty()) {
                return tasks;
            }
        } catch (Exception e) {
            log.debug("subagent_task_model_plan_failed, error={}", e.getMessage());
        }
        return fallbackTasks(context);
    }

    private List<SubAgentTask> planBySchedulerModel(OrchestrationContext context) throws Exception {
        String raw = streamingChatClientSupport.collect(
                schedulerChatClient,
                agentPromptService.subAgentTaskPlanSystemPrompt(),
                agentPromptService.subAgentTaskPlanUserPrompt(context, properties.getMaxTasksPerPlan())
        );
        TaskPlanPayload payload = objectMapper.readValue(cleanJson(raw), TaskPlanPayload.class);
        if (payload == null || payload.tasks() == null || payload.tasks().isEmpty()) {
            return List.of();
        }
        return materializeTasks(context, payload.tasks());
    }

    private List<SubAgentTask> materializeTasks(OrchestrationContext context, List<PlannedTask> plannedTasks) {
        int maxTasks = Math.max(1, properties.getMaxTasksPerPlan());
        LinkedHashMap<String, PlannedTask> taskByKey = new LinkedHashMap<>();
        int index = 1;
        for (PlannedTask task : plannedTasks) {
            if (taskByKey.size() >= maxTasks) {
                break;
            }
            String taskKey = normalizeTaskKey(task.taskKey(), "task-" + index);
            if (taskByKey.containsKey(taskKey)) {
                throw new IllegalArgumentException("duplicate taskKey: " + taskKey);
            }
            validateRequiredText(task.title(), "title");
            validateRequiredText(task.instruction(), "instruction");
            taskByKey.put(taskKey, task);
            index++;
        }
        if (taskByKey.isEmpty()) {
            return List.of();
        }

        Map<String, List<String>> dependencyKeys = normalizeDependencyKeys(taskByKey);
        validateAcyclicDependencies(dependencyKeys);

        Map<String, String> taskIdByKey = new LinkedHashMap<>();
        taskByKey.keySet().forEach(taskKey -> taskIdByKey.put(taskKey, UUID.randomUUID().toString()));

        List<SubAgentTask> tasks = new ArrayList<>();
        for (Map.Entry<String, PlannedTask> entry : taskByKey.entrySet()) {
            String taskKey = entry.getKey();
            PlannedTask planned = entry.getValue();
            List<String> dependsOn = dependencyKeys.getOrDefault(taskKey, List.of()).stream()
                    .map(taskIdByKey::get)
                    .toList();
            tasks.add(new SubAgentTask(
                    taskIdByKey.get(taskKey),
                    planned.title().trim(),
                    planned.instruction().trim(),
                    parseCapability(planned.capability(), defaultCapability(context)),
                    Map.of("question", safeQuestion(context)),
                    StringUtils.hasText(planned.expectedOutput()) ? planned.expectedOutput().trim() : "返回结构化分析结论、证据引用和建议下一步",
                    dependsOn,
                    StringUtils.hasText(planned.parallelGroup()) ? planned.parallelGroup().trim() : taskKey,
                    Map.of("maxRounds", properties.getSubagentMaxRounds()),
                    planned.priority() == null ? 100 : planned.priority(),
                    Map.of(
                            "taskKey", taskKey,
                            "taskType", context.request().taskType().name(),
                            "repoRoot", context.request().repoRoot()
                    )
            ));
        }
        return List.copyOf(tasks);
    }

    private Map<String, List<String>> normalizeDependencyKeys(Map<String, PlannedTask> taskByKey) {
        Map<String, List<String>> dependencyKeys = new LinkedHashMap<>();
        for (Map.Entry<String, PlannedTask> entry : taskByKey.entrySet()) {
            String taskKey = entry.getKey();
            List<String> rawDependsOn = entry.getValue().dependsOn() == null ? List.of() : entry.getValue().dependsOn();
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            for (String rawDependency : rawDependsOn) {
                String dependencyKey = normalizeTaskKey(rawDependency, "");
                if (!StringUtils.hasText(dependencyKey)) {
                    continue;
                }
                if (dependencyKey.equals(taskKey)) {
                    throw new IllegalArgumentException("task depends on itself: " + taskKey);
                }
                if (!taskByKey.containsKey(dependencyKey)) {
                    throw new IllegalArgumentException("unknown dependency: " + dependencyKey);
                }
                normalized.add(dependencyKey);
            }
            dependencyKeys.put(taskKey, List.copyOf(normalized));
        }
        return dependencyKeys;
    }

    private void validateAcyclicDependencies(Map<String, List<String>> dependencyKeys) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String taskKey : dependencyKeys.keySet()) {
            visit(taskKey, dependencyKeys, visiting, visited);
        }
    }

    private void visit(
            String taskKey,
            Map<String, List<String>> dependencyKeys,
            Set<String> visiting,
            Set<String> visited
    ) {
        if (visited.contains(taskKey)) {
            return;
        }
        if (!visiting.add(taskKey)) {
            throw new IllegalArgumentException("cyclic dependency around taskKey: " + taskKey);
        }
        for (String dependencyKey : dependencyKeys.getOrDefault(taskKey, List.of())) {
            visit(dependencyKey, dependencyKeys, visiting, visited);
        }
        visiting.remove(taskKey);
        visited.add(taskKey);
    }

    private List<SubAgentTask> fallbackTasks(OrchestrationContext context) {
        String question = safeQuestion(context);
        List<SubAgentTask> tasks = new ArrayList<>();
        if (question.contains("前端") && question.contains("后端")) {
            tasks.add(fallbackTask(context, "frontend-analysis", "前端侧分析", "只分析前端页面、交互和浏览器侧证据", SubAgentCapability.CODE_EXPLAIN, "frontend", List.of()));
            tasks.add(fallbackTask(context, "backend-analysis", "后端侧分析", "只分析接口、服务和后端日志/代码路径", defaultCapability(context), "backend", List.of()));
            tasks.add(fallbackTask(context, "integration-summary", "前后端收敛", "把前端和后端发现收敛成统一结论、依赖关系和下一步建议", defaultCapability(context), "summary", List.of(tasks.get(0).taskId(), tasks.get(1).taskId())));
        } else if (question.contains("测试") && (question.contains("修复") || question.contains("重构"))) {
            SubAgentTask fixTask = fallbackTask(context, "fix-analysis", "修复方案分析", "先聚焦代码根因、修复面和潜在影响", defaultCapability(context), "fix", List.of());
            tasks.add(fixTask);
            tasks.add(fallbackTask(context, "test-impact", "测试影响分析", "评估需要补哪些测试、验证路径和风险回归点", SubAgentCapability.TEST_ANALYSIS, "verify", List.of(fixTask.taskId())));
        } else if (question.contains("同时") || question.contains("分别") || question.contains("多个模块") || question.contains("多个文件")) {
            tasks.add(fallbackTask(context, "evidence-scan", "证据并行扫描", "优先扫描涉及模块里的核心证据、入口和可疑代码路径", SubAgentCapability.CODE_EXPLAIN, "scan-a", List.of()));
            tasks.add(fallbackTask(context, "risk-scan", "风险并行扫描", "并行检查风险点、边界条件和可能的副作用", defaultCapability(context), "scan-b", List.of()));
            tasks.add(fallbackTask(context, "integration-summary", "汇总收敛", "把不同扫描结果收敛成统一结论和下一步建议", defaultCapability(context), "summary", List.of(tasks.get(0).taskId(), tasks.get(1).taskId())));
        } else {
            tasks.add(fallbackTask(context, "primary-analysis", "主任务分解", question, defaultCapability(context), "lead", List.of()));
        }
        return tasks.stream().limit(Math.max(1, properties.getMaxTasksPerPlan())).toList();
    }

    private SubAgentTask fallbackTask(
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
                Map.of("question", safeQuestion(context)),
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

    private SubAgentCapability defaultCapability(OrchestrationContext context) {
        AgentTaskType type = context.request().taskType();
        if (type == AgentTaskType.BUG_FIX) {
            return SubAgentCapability.BUG_FIX;
        }
        return SubAgentCapability.CODE_EXPLAIN;
    }

    private SubAgentCapability parseCapability(String raw, SubAgentCapability fallback) {
        if (!StringUtils.hasText(raw)) {
            return fallback;
        }
        try {
            return SubAgentCapability.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void validateRequiredText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("task " + field + " is required");
        }
    }

    private String normalizeTaskKey(String raw, String fallback) {
        String source = StringUtils.hasText(raw) ? raw : fallback;
        return source == null ? "" : source.trim().toLowerCase().replaceAll("[^a-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
    }

    private String safeQuestion(OrchestrationContext context) {
        return context.request().question() == null ? "" : context.request().question();
    }

    private String cleanJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TaskPlanPayload(List<PlannedTask> tasks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PlannedTask(
            String taskKey,
            String title,
            String instruction,
            String capability,
            String parallelGroup,
            List<String> dependsOn,
            String expectedOutput,
            Integer priority
    ) {
    }
}
