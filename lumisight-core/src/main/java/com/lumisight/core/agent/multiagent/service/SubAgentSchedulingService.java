package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.AgentLoopTask;
import com.lumisight.core.agent.AgentLoopTaskRunner;
import com.lumisight.core.agent.CompletedAgentLoopTask;
import com.lumisight.core.agent.multiagent.model.AgentOrchestrationMode;
import com.lumisight.core.agent.multiagent.model.MultiAgentExecutionState;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.model.TaskExecutionStatus;
import com.lumisight.core.context.ambient.OrchestrationContext;
import com.lumisight.core.tool.AgentToolPermission;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * 子任务调度器。
 * serial / parallel / hybrid 是 agent 编排方式；wave 只是把编排方式落到线程池时使用的执行批次。
 * 这里先按批次准备子任务，再只把准备完成后的 agent loop 封装成 AgentLoopTask 扔给统一线程池执行器。
 */
@Component
public class SubAgentSchedulingService {

    private final ExecutingSubAgent executingSubAgent;
    private final AgentLoopTaskRunner agentLoopTaskRunner;
    private final MultiAgentProperties properties;
    private final ChildAgentPermissionPolicy childAgentPermissionPolicy;
    private final MultiAgentExecutionStateStore executionStateStore;

    public SubAgentSchedulingService(
            ExecutingSubAgent executingSubAgent,
            AgentLoopTaskRunner agentLoopTaskRunner,
            MultiAgentProperties properties,
            ChildAgentPermissionPolicy childAgentPermissionPolicy,
            MultiAgentExecutionStateStore executionStateStore
    ) {
        this.executingSubAgent = executingSubAgent;
        this.agentLoopTaskRunner = agentLoopTaskRunner;
        this.properties = properties;
        this.childAgentPermissionPolicy = childAgentPermissionPolicy;
        this.executionStateStore = executionStateStore;
    }

    public ExecutionResult executePlan(OrchestrationPlan plan, OrchestrationContext context) {
        String coordinationId = stableCoordinationId(context);
        String repoRoot = context.request().repoRoot();
        MultiAgentExecutionState resumeState = context.request().resume()
                ? executionStateStore.load(repoRoot, coordinationId).orElse(null)
                : null;
        OrchestrationPlan effectivePlan = resumeState != null && resumeState.plan() != null ? resumeState.plan() : plan;
        List<SubAgentTask> tasks = effectivePlan.tasks() == null ? List.of() : effectivePlan.tasks();
        Map<String, TaskExecutionStatus> taskStates = initTaskStates(tasks, resumeState);
        Map<String, SubAgentResult> completed = initCompleted(resumeState);
        Map<String, List<String>> permissionsByTask = initPermissionsByTask(resumeState);
        List<String> lifecycleEvents = Collections.synchronizedList(initLifecycleEvents(resumeState));
        List<List<SubAgentTask>> waves = buildExecutionWaves(tasks, effectivePlan.orchestrationMode());
        int currentWave = resumeState == null ? 0 : Math.max(0, resumeState.currentWave());
        if (resumeState != null) {
            lifecycleEvents.add("lead:resume_loaded:" + effectivePlan.planId());
        }
        saveState(repoRoot, coordinationId, effectivePlan, taskStates, completed, currentWave, lifecycleEvents, permissionsByTask);

        boolean paused = false;
        for (List<SubAgentTask> wave : waves) {
            if (shouldStop(context)) {
                paused = true;
                lifecycleEvents.add("lead:paused_before_wave:" + effectivePlan.planId());
                break;
            }
            List<SubAgentTask> runnable = filterRunnableTasks(wave, effectivePlan.orchestrationMode(), completed, taskStates, lifecycleEvents).stream()
                    .filter(task -> shouldExecute(taskStates.get(task.taskId())))
                    .toList();
            if (runnable.isEmpty()) {
                continue;
            }

            currentWave++;
            lifecycleEvents.add("lead:wave_started:" + currentWave);
            WaveExecutionResult waveResult = executeWave(context, coordinationId, effectivePlan.orchestrationMode(), currentWave, runnable, completed, permissionsByTask, taskStates);
            lifecycleEvents.addAll(waveResult.lifecycleEvents());
            for (SubAgentResult result : waveResult.results()) {
                completed.put(result.taskId(), result);
                taskStates.put(result.taskId(), result.success() ? TaskExecutionStatus.SUCCEEDED : TaskExecutionStatus.FAILED);
            }
            markWaveCompletions(runnable, completed, taskStates);
            lifecycleEvents.add("lead:wave_completed:" + currentWave);
            saveState(repoRoot, coordinationId, effectivePlan, taskStates, completed, currentWave, lifecycleEvents, permissionsByTask);

            if (shouldStop(context)) {
                paused = true;
                lifecycleEvents.add("lead:paused_after_wave:" + effectivePlan.planId());
                break;
            }
        }

        if (!paused) {
            lifecycleEvents.add("lead:all_waves_completed:" + currentWave);
        }
        MultiAgentExecutionState state = snapshot(coordinationId, effectivePlan, taskStates, completed, currentWave, lifecycleEvents, permissionsByTask);
        executionStateStore.save(repoRoot, coordinationId, state);
        return new ExecutionResult(coordinationId, state.childSummaries(), lifecycleEvents, state);
    }

    private WaveExecutionResult executeWave(
            OrchestrationContext context,
            String coordinationId,
            AgentOrchestrationMode orchestrationMode,
            int currentWave,
            List<SubAgentTask> runnable,
            Map<String, SubAgentResult> completed,
            Map<String, List<String>> permissionsByTask,
            Map<String, TaskExecutionStatus> taskStates
    ) {
        List<SubAgentWorkItem> workItems = new ArrayList<>(runnable.size());
        for (int i = 0; i < runnable.size(); i++) {
            SubAgentTask task = enrichTaskWithDependencyEvidence(runnable.get(i), completed, permissionsByTask);
            String workerId = "subagent-worker-" + (i + 1);
            taskStates.put(task.taskId(), TaskExecutionStatus.RUNNING);
            workItems.add(new SubAgentWorkItem(task, workerId));
        }
        int parallelism = decideWaveParallelism(orchestrationMode, currentWave, workItems.size());
        Map<String, WorkItemResult> workItemResultByTaskId = new LinkedHashMap<>();
        Map<String, PreparedWorkItem> pendingByLoopTaskId = new LinkedHashMap<>();
        List<AgentLoopTask<CompletedAgentLoopTask>> loopTasks = new ArrayList<>();
        for (SubAgentWorkItem workItem : workItems) {
            List<String> lifecycleEvents = new ArrayList<>();
            lifecycleEvents.add(workItem.workerId() + ":wave_" + currentWave + ":running:" + workItem.task().taskId());
            try {
                SubAgentExecutionService.PreparedSubAgentExecution preparedExecution = executingSubAgent.prepareAssigned(
                        workItem.task(),
                        context,
                        coordinationId,
                        workItem.workerId()
                );
                if (preparedExecution.completedBeforeLoop()) {
                    SubAgentResult result = executingSubAgent.completePrepared(preparedExecution, null);
                    lifecycleEvents.add(workItem.workerId() + ":" + (result.success() ? "succeeded" : "failed") + ":" + workItem.task().taskId());
                    workItemResultByTaskId.put(workItem.task().taskId(), new WorkItemResult(result, List.copyOf(lifecycleEvents)));
                    continue;
                }
                AgentLoopTask<CompletedAgentLoopTask> loopTask = executingSubAgent.toAgentLoopTask(preparedExecution);
                pendingByLoopTaskId.put(loopTask.taskId(), new PreparedWorkItem(workItem, preparedExecution, lifecycleEvents));
                loopTasks.add(loopTask);
            } catch (Exception e) {
                SubAgentResult result = failedWorkItemResult(workItem, e);
                lifecycleEvents.add(workItem.workerId() + ":failed:" + workItem.task().taskId());
                workItemResultByTaskId.put(workItem.task().taskId(), new WorkItemResult(result, List.copyOf(lifecycleEvents)));
            }
        }

        List<CompletedAgentLoopTask> completedLoops = agentLoopTaskRunner.runWave(loopTasks, parallelism);
        for (CompletedAgentLoopTask completedLoop : completedLoops) {
            PreparedWorkItem preparedWorkItem = pendingByLoopTaskId.get(completedLoop.taskId());
            if (preparedWorkItem == null) {
                continue;
            }
            SubAgentResult result = executingSubAgent.completePrepared(preparedWorkItem.preparedExecution(), completedLoop);
            List<String> lifecycleEvents = new ArrayList<>(preparedWorkItem.lifecycleEvents());
            lifecycleEvents.add(preparedWorkItem.workItem().workerId() + ":" + (result.success() ? "succeeded" : "failed") + ":" + preparedWorkItem.workItem().task().taskId());
            workItemResultByTaskId.put(preparedWorkItem.workItem().task().taskId(), new WorkItemResult(result, List.copyOf(lifecycleEvents)));
        }

        List<SubAgentResult> results = new ArrayList<>(workItemResultByTaskId.size());
        List<String> lifecycleEvents = new ArrayList<>();
        for (SubAgentWorkItem workItem : workItems) {
            WorkItemResult result = workItemResultByTaskId.get(workItem.task().taskId());
            if (result == null) {
                continue;
            }
            results.add(result.result());
            lifecycleEvents.addAll(result.lifecycleEvents());
        }
        return new WaveExecutionResult(List.copyOf(results), List.copyOf(lifecycleEvents));
    }

    private SubAgentResult failedWorkItemResult(SubAgentWorkItem workItem, Exception e) {
        return new SubAgentResult(
                workItem.task().taskId(),
                workItem.workerId(),
                false,
                "Sub-agent worker 执行失败: " + e.getMessage(),
                List.of("sub-agent worker execution failed"),
                List.of(),
                List.of("Lead 可接管该任务，或拆小后重试"),
                0.1d,
                Map.of("error", e.getClass().getSimpleName())
        );
    }

    private int decideWaveParallelism(AgentOrchestrationMode orchestrationMode, int currentWave, int waveSize) {
        if (orchestrationMode == AgentOrchestrationMode.SERIAL) {
            return 1;
        }
        if (waveSize <= 1) {
            return 1;
        }
        if (orchestrationMode == AgentOrchestrationMode.HYBRID) {
            // hybrid 场景下按每轮可运行任务数动态收缩并发度，避免把串行依赖阶段也硬顶满线程。
            int preferred = currentWave == 1 ? waveSize : Math.max(1, Math.min(waveSize, properties.getMaxParallelAgents() - 1));
            return Math.max(1, Math.min(properties.getMaxParallelAgents(), preferred));
        }
        return Math.max(1, Math.min(properties.getMaxParallelAgents(), waveSize));
    }

    private Map<String, TaskExecutionStatus> initTaskStates(List<SubAgentTask> tasks, MultiAgentExecutionState resumeState) {
        Map<String, TaskExecutionStatus> states = new LinkedHashMap<>();
        for (SubAgentTask task : tasks) {
            TaskExecutionStatus restored = resumeState == null || resumeState.taskStates() == null
                    ? null
                    : resumeState.taskStates().get(task.taskId());
            states.put(task.taskId(), restored == null ? TaskExecutionStatus.PENDING : restored);
        }
        return states;
    }

    private Map<String, SubAgentResult> initCompleted(MultiAgentExecutionState resumeState) {
        Map<String, SubAgentResult> completed = new LinkedHashMap<>();
        if (resumeState == null || resumeState.childSummaries() == null) {
            return completed;
        }
        for (SubAgentResult result : resumeState.childSummaries()) {
            completed.put(result.taskId(), result);
        }
        return completed;
    }

    private Map<String, List<String>> initPermissionsByTask(MultiAgentExecutionState resumeState) {
        if (resumeState == null || resumeState.schedulerState() == null) {
            return new LinkedHashMap<>();
        }
        Object raw = resumeState.schedulerState().get("permissionsByTask");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return new LinkedHashMap<>();
        }
        Map<String, List<String>> permissionsByTask = new LinkedHashMap<>();
        rawMap.forEach((key, value) -> {
            if (value instanceof List<?> list) {
                permissionsByTask.put(String.valueOf(key), list.stream().map(String::valueOf).toList());
            }
        });
        return permissionsByTask;
    }

    private List<String> initLifecycleEvents(MultiAgentExecutionState resumeState) {
        if (resumeState == null || resumeState.schedulerState() == null) {
            return new ArrayList<>();
        }
        Object raw = resumeState.schedulerState().get("lifecycleEvents");
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(list.stream().map(String::valueOf).toList());
    }

    private List<List<SubAgentTask>> buildExecutionWaves(List<SubAgentTask> tasks, AgentOrchestrationMode orchestrationMode) {
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

    private List<SubAgentTask> filterRunnableTasks(
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

    private boolean shouldExecute(TaskExecutionStatus status) {
        return status == null || status == TaskExecutionStatus.PENDING || status == TaskExecutionStatus.RUNNING;
    }

    private boolean shouldStop(OrchestrationContext context) {
        if (context == null || context.runtimeAttributes() == null) {
            return false;
        }
        Object raw = context.runtimeAttributes().get("shouldStop");
        if (raw instanceof BooleanSupplier supplier) {
            return supplier.getAsBoolean();
        }
        if (raw instanceof java.util.function.Supplier<?> supplier) {
            Object supplied = supplier.get();
            return supplied instanceof Boolean value && value;
        }
        return raw instanceof Boolean value && value;
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

    private void markWaveCompletions(
            List<SubAgentTask> runnable,
            Map<String, SubAgentResult> completed,
            Map<String, TaskExecutionStatus> taskStates
    ) {
        for (SubAgentTask task : runnable) {
            if (!completed.containsKey(task.taskId()) && taskStates.get(task.taskId()) == TaskExecutionStatus.PENDING) {
                taskStates.put(task.taskId(), TaskExecutionStatus.SKIPPED);
            } else if (completed.containsKey(task.taskId()) && taskStates.get(task.taskId()) == TaskExecutionStatus.PENDING) {
                taskStates.put(task.taskId(), completed.get(task.taskId()).success() ? TaskExecutionStatus.SUCCEEDED : TaskExecutionStatus.FAILED);
            }
        }
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

    private void saveState(
            String repoRoot,
            String coordinationId,
            OrchestrationPlan plan,
            Map<String, TaskExecutionStatus> taskStates,
            Map<String, SubAgentResult> completed,
            int currentWave,
            List<String> lifecycleEvents,
            Map<String, List<String>> permissionsByTask
    ) {
        executionStateStore.save(repoRoot, coordinationId, snapshot(
                coordinationId,
                plan,
                taskStates,
                completed,
                currentWave,
                lifecycleEvents,
                permissionsByTask
        ));
    }

    private MultiAgentExecutionState snapshot(
            String coordinationId,
            OrchestrationPlan plan,
            Map<String, TaskExecutionStatus> taskStates,
            Map<String, SubAgentResult> completed,
            int currentWave,
            List<String> lifecycleEvents,
            Map<String, List<String>> permissionsByTask
    ) {
        return new MultiAgentExecutionState(
                plan.planId(),
                coordinationId,
                "SUB_AGENT",
                plan.orchestrationMode(),
                plan,
                Map.copyOf(taskStates),
                completed.values().stream().sorted(Comparator.comparing(SubAgentResult::taskId)).toList(),
                currentWave,
                Map.of(
                        "paused", hasPendingTasks(taskStates),
                        "lifecycleEvents", List.copyOf(lifecycleEvents),
                        "permissionsByTask", Map.copyOf(permissionsByTask)
                )
        );
    }

    private boolean hasPendingTasks(Map<String, TaskExecutionStatus> taskStates) {
        return taskStates.values().stream().anyMatch(status -> status == TaskExecutionStatus.PENDING || status == TaskExecutionStatus.RUNNING);
    }

    private String stableCoordinationId(OrchestrationContext context) {
        String sessionId = context.request() == null ? null : context.request().sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return "multi-agent-anonymous";
        }
        return "coord-" + sessionId.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    public record ExecutionResult(
            String coordinationId,
            List<SubAgentResult> results,
            List<String> lifecycleEvents,
            MultiAgentExecutionState executionState
    ) {
    }

    private record SubAgentWorkItem(SubAgentTask task, String workerId) {
    }

    private record PreparedWorkItem(
            SubAgentWorkItem workItem,
            SubAgentExecutionService.PreparedSubAgentExecution preparedExecution,
            List<String> lifecycleEvents
    ) {
    }

    private record WorkItemResult(SubAgentResult result, List<String> lifecycleEvents) {
    }

    private record WaveExecutionResult(List<SubAgentResult> results, List<String> lifecycleEvents) {
    }
}
