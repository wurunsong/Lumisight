package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.AgentOrchestrationMode;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.model.TaskExecutionStatus;
import com.lumisight.core.context.ambient.OrchestrationContext;
import com.lumisight.core.tool.AgentToolPermission;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把 agent 编排方式落成可执行批次。
 * 注意：wave 是调度批次，不是编排方式；serial / parallel / hybrid 才是上层语义。
 */
@Component
class SubAgentWavePlanner {

    private final MultiAgentProperties properties;
    private final ChildAgentPermissionPolicy childAgentPermissionPolicy;

    SubAgentWavePlanner(
            MultiAgentProperties properties,
            ChildAgentPermissionPolicy childAgentPermissionPolicy
    ) {
        this.properties = properties;
        this.childAgentPermissionPolicy = childAgentPermissionPolicy;
    }

    List<List<SubAgentTask>> buildExecutionWaves(List<SubAgentTask> tasks, AgentOrchestrationMode orchestrationMode) {
        if (orchestrationMode == AgentOrchestrationMode.SERIAL) {
            return tasks.stream().map(List::of).toList();
        }
        List<List<SubAgentTask>> waves = new ArrayList<>();
        Map<String, SubAgentTask> pending = tasks.stream()
                .collect(Collectors.toMap(SubAgentTask::taskId, task -> task, (a, b) -> a, LinkedHashMap::new));
        LinkedHashSet<String> released = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            List<SubAgentTask> ready = pending.values().stream()
                    .filter(task -> task.dependsOn() == null || released.containsAll(task.dependsOn()))
                    .sorted(Comparator.comparingInt(SubAgentTask::priority).reversed())
                    .collect(Collectors.collectingAndThen(
                            Collectors.toList(),
                            candidates -> candidates.isEmpty() ? List.of(pending.values().iterator().next()) : List.copyOf(candidates)
                    ));
            waves.add(ready);
            ready.forEach(task -> {
                pending.remove(task.taskId());
                released.add(task.taskId());
            });
        }
        return waves;
    }

    List<SubAgentTask> selectRunnableTasks(
            List<SubAgentTask> wave,
            AgentOrchestrationMode orchestrationMode,
            Map<String, SubAgentResult> completed,
            Map<String, TaskExecutionStatus> taskStates,
            List<String> lifecycleEvents
    ) {
        List<SubAgentTask> runnable = new ArrayList<>();
        for (SubAgentTask task : wave) {
            boolean dependencyFailed = task.dependsOn() != null && task.dependsOn().stream()
                    .map(completed::get)
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(result -> !result.success());
            if (dependencyFailed && orchestrationMode != AgentOrchestrationMode.HYBRID) {
                taskStates.put(task.taskId(), TaskExecutionStatus.BLOCKED);
                lifecycleEvents.add(task.taskId() + ":blocked_by_failed_dependency");
                continue;
            }
            runnable.add(task);
        }
        return runnable;
    }

    SubAgentWavePlan planWave(
            OrchestrationContext context,
            String coordinationId,
            AgentOrchestrationMode orchestrationMode,
            int waveNumber,
            List<SubAgentTask> runnable,
            Map<String, SubAgentResult> completed,
            Map<String, List<String>> permissionsByTask,
            Map<String, TaskExecutionStatus> taskStates
    ) {
        List<SubAgentWaveWorkItem> workItems = new ArrayList<>(runnable.size());
        for (int i = 0; i < runnable.size(); i++) {
            SubAgentTask task = enrichTaskWithDependencyEvidence(runnable.get(i), completed, permissionsByTask);
            String workerId = "subagent-worker-" + (i + 1);
            taskStates.put(task.taskId(), TaskExecutionStatus.RUNNING);
            workItems.add(new SubAgentWaveWorkItem(task, workerId));
        }
        return new SubAgentWavePlan(
                context,
                coordinationId,
                waveNumber,
                decideParallelism(orchestrationMode, waveNumber, workItems.size()),
                List.copyOf(workItems)
        );
    }

    int decideParallelism(AgentOrchestrationMode orchestrationMode, int waveNumber, int waveSize) {
        if (orchestrationMode == AgentOrchestrationMode.SERIAL) {
            return 1;
        }
        if (waveSize <= 1) {
            return 1;
        }
        if (orchestrationMode == AgentOrchestrationMode.HYBRID) {
            // hybrid 场景下按每轮可运行任务数动态收缩并发度，避免把串行依赖阶段也硬顶满线程。
            int preferred = waveNumber == 1 ? waveSize : Math.max(1, Math.min(waveSize, properties.getMaxParallelAgents() - 1));
            return Math.max(1, Math.min(properties.getMaxParallelAgents(), preferred));
        }
        return Math.max(1, Math.min(properties.getMaxParallelAgents(), waveSize));
    }

    private SubAgentTask enrichTaskWithDependencyEvidence(
            SubAgentTask task,
            Map<String, SubAgentResult> completed,
            Map<String, List<String>> permissionsByTask
    ) {
        Map<String, Object> inputs = new LinkedHashMap<>(task.inputs() == null ? Map.of() : task.inputs());
        if (task.dependsOn() != null && !task.dependsOn().isEmpty()) {
            List<Map<String, Object>> dependencyEvidence = task.dependsOn().stream()
                    .map(completed::get)
                    .filter(java.util.Objects::nonNull)
                    .map(result -> Map.of(
                            "taskId", result.taskId(),
                            "summary", result.summary(),
                            "success", result.success(),
                            "findings", result.findings(),
                            "evidenceRefs", result.evidenceRefs()
                    ))
                    .toList();
            if (!dependencyEvidence.isEmpty()) {
                inputs.put("dependencyEvidence", dependencyEvidence);
            }
        }
        List<String> requestedPermissions = requiredPermissionNames(task.capability());
        if (!requestedPermissions.isEmpty()) {
            permissionsByTask.put(task.taskId(), requestedPermissions);
        }
        return new SubAgentTask(
                task.taskId(),
                task.title(),
                task.instruction(),
                task.capability(),
                Map.copyOf(inputs),
                task.expectedOutput(),
                task.dependsOn(),
                task.parallelGroup(),
                task.budget(),
                task.priority(),
                mergeMetadata(task.metadata(), requestedPermissions)
        );
    }

    private Map<String, Object> mergeMetadata(Map<String, Object> metadata, List<String> requestedPermissions) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        if (!requestedPermissions.isEmpty()) {
            merged.put("requestedPermissions", requestedPermissions);
        }
        return Map.copyOf(merged);
    }

    private List<String> requiredPermissionNames(SubAgentCapability capability) {
        Set<AgentToolPermission> permissions = childAgentPermissionPolicy.permissionsFor(capability);
        EnumSet<AgentToolPermission> leadApprovedSubset = EnumSet.of(
                AgentToolPermission.BUILD_COMPILE,
                AgentToolPermission.BROWSER_READ
        );
        return permissions.stream()
                .filter(leadApprovedSubset::contains)
                .map(Enum::name)
                .sorted()
                .toList();
    }
}
