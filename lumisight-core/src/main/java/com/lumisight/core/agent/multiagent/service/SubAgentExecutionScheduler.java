package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.MultiAgentExecutionState;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.model.TaskExecutionStatus;
import com.lumisight.core.context.ambient.OrchestrationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * 子任务执行调度器。
 * 这里负责“下一波跑什么、用多少并发、状态怎么推进和持久化”；真正跑 loop 交给 SubAgentWaveExecutor。
 */
@Component
class SubAgentExecutionScheduler {

    private final SubAgentWavePlanner wavePlanner;
    private final MultiAgentExecutionStateStore executionStateStore;

    SubAgentExecutionScheduler(
            SubAgentWavePlanner wavePlanner,
            MultiAgentExecutionStateStore executionStateStore
    ) {
        this.wavePlanner = wavePlanner;
        this.executionStateStore = executionStateStore;
    }

    ScheduleSession open(OrchestrationPlan plan, OrchestrationContext context) {
        String coordinationId = stableCoordinationId(context);
        String repoRoot = context.request().repoRoot();
        MultiAgentExecutionState resumeState = context.request().resume()
                ? executionStateStore.load(repoRoot, coordinationId).orElse(null)
                : null;
        OrchestrationPlan effectivePlan = resumeState != null && resumeState.plan() != null ? resumeState.plan() : plan;
        List<SubAgentTask> tasks = effectivePlan.tasks() == null ? List.of() : effectivePlan.tasks();
        ScheduleSession session = new ScheduleSession(
                context,
                repoRoot,
                coordinationId,
                effectivePlan,
                wavePlanner.buildExecutionWaves(tasks, effectivePlan.orchestrationMode()),
                initTaskStates(tasks, resumeState),
                initCompleted(resumeState),
                initPermissionsByTask(resumeState),
                Collections.synchronizedList(initLifecycleEvents(resumeState)),
                resumeState == null ? 0 : Math.max(0, resumeState.currentWave())
        );
        if (resumeState != null) {
            session.lifecycleEvents().add("lead:resume_loaded:" + effectivePlan.planId());
        }
        saveState(session);
        return session;
    }

    Optional<SubAgentWavePlan> nextWave(ScheduleSession session) {
        while (session.hasRemainingWaves()) {
            if (shouldStop(session.context())) {
                session.pause("lead:paused_before_wave:" + session.plan().planId());
                return Optional.empty();
            }
            List<SubAgentTask> wave = session.nextRawWave();
            List<SubAgentTask> runnable = wavePlanner.selectRunnableTasks(
                            wave,
                            session.plan().orchestrationMode(),
                            session.completed(),
                            session.taskStates(),
                            session.lifecycleEvents()
                    ).stream()
                    .filter(task -> shouldExecute(session.taskStates().get(task.taskId())))
                    .toList();
            if (runnable.isEmpty()) {
                continue;
            }

            session.startNextWave();
            return Optional.of(wavePlanner.planWave(
                    session.context(),
                    session.coordinationId(),
                    session.plan().orchestrationMode(),
                    session.currentWave(),
                    runnable,
                    session.completed(),
                    session.permissionsByTask(),
                    session.taskStates()
            ));
        }
        return Optional.empty();
    }

    void completeWave(ScheduleSession session, SubAgentWavePlan wavePlan, SubAgentWaveExecutionResult waveResult) {
        session.lifecycleEvents().addAll(waveResult.lifecycleEvents());
        for (SubAgentResult result : waveResult.results()) {
            session.completed().put(result.taskId(), result);
            session.taskStates().put(result.taskId(), result.success() ? TaskExecutionStatus.SUCCEEDED : TaskExecutionStatus.FAILED);
        }
        markWaveCompletions(wavePlan.workItems().stream().map(SubAgentWaveWorkItem::task).toList(), session.completed(), session.taskStates());
        session.lifecycleEvents().add("lead:wave_completed:" + session.currentWave());
        saveState(session);

        if (shouldStop(session.context())) {
            session.pause("lead:paused_after_wave:" + session.plan().planId());
        }
    }

    boolean shouldContinue(ScheduleSession session) {
        return !session.paused() && session.hasRemainingWaves();
    }

    MultiAgentExecutionState finish(ScheduleSession session) {
        if (!session.paused()) {
            session.lifecycleEvents().add("lead:all_waves_completed:" + session.currentWave());
        }
        MultiAgentExecutionState state = snapshot(session);
        executionStateStore.save(session.repoRoot(), session.coordinationId(), state);
        return state;
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

    private void saveState(ScheduleSession session) {
        executionStateStore.save(session.repoRoot(), session.coordinationId(), snapshot(session));
    }

    private MultiAgentExecutionState snapshot(ScheduleSession session) {
        return new MultiAgentExecutionState(
                session.plan().planId(),
                session.coordinationId(),
                "SUB_AGENT",
                session.plan().orchestrationMode(),
                session.plan(),
                Map.copyOf(session.taskStates()),
                session.completed().values().stream().sorted(Comparator.comparing(SubAgentResult::taskId)).toList(),
                session.currentWave(),
                Map.of(
                        "paused", session.paused() || hasPendingTasks(session.taskStates()),
                        "lifecycleEvents", List.copyOf(session.lifecycleEvents()),
                        "permissionsByTask", Map.copyOf(session.permissionsByTask())
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

    static final class ScheduleSession {
        private final OrchestrationContext context;
        private final String repoRoot;
        private final String coordinationId;
        private final OrchestrationPlan plan;
        private final List<List<SubAgentTask>> waves;
        private final Map<String, TaskExecutionStatus> taskStates;
        private final Map<String, SubAgentResult> completed;
        private final Map<String, List<String>> permissionsByTask;
        private final List<String> lifecycleEvents;
        private int waveCursor;
        private int currentWave;
        private boolean paused;

        private ScheduleSession(
                OrchestrationContext context,
                String repoRoot,
                String coordinationId,
                OrchestrationPlan plan,
                List<List<SubAgentTask>> waves,
                Map<String, TaskExecutionStatus> taskStates,
                Map<String, SubAgentResult> completed,
                Map<String, List<String>> permissionsByTask,
                List<String> lifecycleEvents,
                int currentWave
        ) {
            this.context = context;
            this.repoRoot = repoRoot;
            this.coordinationId = coordinationId;
            this.plan = plan;
            this.waves = waves;
            this.taskStates = taskStates;
            this.completed = completed;
            this.permissionsByTask = permissionsByTask;
            this.lifecycleEvents = lifecycleEvents;
            this.currentWave = currentWave;
        }

        private boolean hasRemainingWaves() {
            return waveCursor < waves.size();
        }

        private List<SubAgentTask> nextRawWave() {
            return waves.get(waveCursor++);
        }

        private void startNextWave() {
            currentWave++;
            lifecycleEvents.add("lead:wave_started:" + currentWave);
        }

        private void pause(String event) {
            paused = true;
            lifecycleEvents.add(event);
        }

        OrchestrationContext context() {
            return context;
        }

        String repoRoot() {
            return repoRoot;
        }

        String coordinationId() {
            return coordinationId;
        }

        OrchestrationPlan plan() {
            return plan;
        }

        Map<String, TaskExecutionStatus> taskStates() {
            return taskStates;
        }

        Map<String, SubAgentResult> completed() {
            return completed;
        }

        Map<String, List<String>> permissionsByTask() {
            return permissionsByTask;
        }

        List<String> lifecycleEvents() {
            return lifecycleEvents;
        }

        int currentWave() {
            return currentWave;
        }

        boolean paused() {
            return paused;
        }
    }
}
