package com.lumisight.core.agent.multiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.common.concurrent.NamedExecutors;
import com.lumisight.core.agent.multiagent.model.MultiAgentExecutionState;
import com.lumisight.core.context.ambient.MultiAgentExecutionScope;
import com.lumisight.core.context.ambient.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.model.TaskExecutionStatus;
import com.lumisight.core.agent.multiagent.model.TeamAgentMessage;
import com.lumisight.core.agent.multiagent.model.TeamAgentMessageType;
import com.lumisight.core.agent.multiagent.model.TopologyType;
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
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

@Component
public class TeamAgentExecutionService {

    private static final String LEAD_AGENT_ID = "lead";

    private final AgentMailboxBus mailboxBus;
    private final ExecutingSubAgent executingSubAgent;
    private final MultiAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ChildAgentPermissionPolicy childAgentPermissionPolicy;
    private final MultiAgentExecutionStateStore executionStateStore;

    public TeamAgentExecutionService(
            AgentMailboxBus mailboxBus,
            ExecutingSubAgent executingSubAgent,
            MultiAgentProperties properties,
            ObjectMapper objectMapper,
            ChildAgentPermissionPolicy childAgentPermissionPolicy,
            MultiAgentExecutionStateStore executionStateStore
    ) {
        this.mailboxBus = mailboxBus;
        this.executingSubAgent = executingSubAgent;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.childAgentPermissionPolicy = childAgentPermissionPolicy;
        this.executionStateStore = executionStateStore;
    }

    public ExecutionResult executePlan(OrchestrationPlan plan, OrchestrationContext context) {
        String teamId = stableTeamId(context);
        String repoRoot = context.request().repoRoot();
        MultiAgentExecutionState resumeState = context.request().resume()
                ? executionStateStore.load(repoRoot, teamId).orElse(null)
                : null;
        OrchestrationPlan effectivePlan = resumeState != null && resumeState.plan() != null ? resumeState.plan() : plan;
        List<SubAgentTask> tasks = effectivePlan.tasks() == null ? List.of() : effectivePlan.tasks();
        List<String> workerIds = allocateWorkers(tasks, effectivePlan.topology());
        Map<String, TaskExecutionStatus> taskStates = initTaskStates(tasks, resumeState);
        Map<String, Long> inboxOffsets = new ConcurrentHashMap<>(initInboxOffsets(resumeState));
        Map<String, SubAgentResult> completed = initCompleted(resumeState);
        Map<String, List<String>> permissionsByTask = initPermissionsByTask(resumeState);
        // todo 为啥不用copyOnWriteArrayList
        List<String> lifecycleEvents = Collections.synchronizedList(initLifecycleEvents(resumeState));

        List<List<SubAgentTask>> waves = buildExecutionWaves(tasks, effectivePlan.topology());
        int currentRound = resumeState == null ? 0 : Math.max(0, resumeState.currentRound());
        if (resumeState != null) {
            lifecycleEvents.add("lead:resume_loaded:" + effectivePlan.planId());
        }
        // team agent状态会落到文件中<repoRoot>/.lumisight/teams/<teamId>/execution-state.json
        executionStateStore.save(repoRoot, teamId, snapshot(teamId, effectivePlan, taskStates, completed, inboxOffsets, currentRound, lifecycleEvents, permissionsByTask));
        boolean paused = false;
        for (List<SubAgentTask> wave : waves) {
            if (shouldStop(context)) {
                paused = true;
                lifecycleEvents.add("lead:paused_before_wave:" + effectivePlan.planId());
                break;
            }
            List<SubAgentTask> waveCandidates = filterRunnableTasks(wave, effectivePlan.topology(), completed, taskStates, lifecycleEvents);
            List<SubAgentTask> runnable = waveCandidates.stream()
                    .filter(task -> shouldExecute(taskStates.get(task.taskId())))
                    .toList();
            if (runnable.isEmpty()) {
                continue;
            }
            currentRound++;
            dispatchTasks(repoRoot, teamId, runnable, workerIds, completed, inboxOffsets, lifecycleEvents, permissionsByTask, taskStates);
            Map<String, WorkerPendingTask> pendingByWorker = new ConcurrentHashMap<>();
            // pollWorkerInbox最后还是调用的CodeAssistantAgentService这个类，runWorkerActions是一个多线程执行器
            runWorkerActions(workerIds, workerId -> pollWorkerInbox(context, teamId, workerId, inboxOffsets, pendingByWorker, lifecycleEvents));
            List<TeamAgentMessage> bufferedLeadMessages = List.of();
            // 这里执行那些需要提权而在前一步没执行的任务
            if (!pendingByWorker.isEmpty()) {
                bufferedLeadMessages = handleLeadPermissionRequests(repoRoot, teamId, inboxOffsets, lifecycleEvents);
                runWorkerActions(new ArrayList<>(pendingByWorker.keySet()), workerId ->
                        pollWorkerPermissionResponses(context, teamId, workerId, pendingByWorker, inboxOffsets, lifecycleEvents));
            }
            List<TeamAgentMessage> leadMessages = new ArrayList<>(bufferedLeadMessages);
            leadMessages.addAll(readLeadInbox(repoRoot, teamId, inboxOffsets));
            collectLeadResults(leadMessages, completed, taskStates, lifecycleEvents);
            runnable.forEach(task -> taskStates.computeIfAbsent(task.taskId(), ignored -> TaskExecutionStatus.PENDING));
            markWaveCompletions(runnable, completed, taskStates);
            executionStateStore.save(repoRoot, teamId, snapshot(teamId, effectivePlan, taskStates, completed, inboxOffsets, currentRound, lifecycleEvents, permissionsByTask));
            if (shouldStop(context)) {
                paused = true;
                lifecycleEvents.add("lead:paused_after_wave:" + effectivePlan.planId());
                break;
            }
        }

        if (!paused) {
            lifecycleEvents.addAll(shutdownWorkers(repoRoot, teamId, workerIds, inboxOffsets));
        }
        MultiAgentExecutionState state = snapshot(teamId, effectivePlan, taskStates, completed, inboxOffsets, currentRound, lifecycleEvents, permissionsByTask);
        executionStateStore.save(repoRoot, teamId, state);
        return new ExecutionResult(teamId, state.childSummaries(), lifecycleEvents, state);
    }

    private List<String> allocateWorkers(List<SubAgentTask> tasks, TopologyType topology) {
        int base = switch (topology) {
            case SERIAL_DAG -> 1;
            case FAN_OUT_FAN_IN -> Math.min(properties.getMaxParallelAgents(), Math.max(1, tasks.size()));
            case HYBRID -> Math.min(properties.getMaxParallelAgents(), Math.max(2, tasks.size()));
        };
        int workerCount = Math.max(1, base);
        List<String> workerIds = new ArrayList<>(workerCount);
        for (int i = 0; i < workerCount; i++) {
            workerIds.add("team-agent-" + (i + 1));
        }
        return workerIds;
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

    private Map<String, Long> initInboxOffsets(MultiAgentExecutionState resumeState) {
        if (resumeState == null || resumeState.inboxOffsets() == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(resumeState.inboxOffsets());
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
        if (resumeState == null || resumeState.fallbackState() == null) {
            return new LinkedHashMap<>();
        }
        Object raw = resumeState.fallbackState().get("permissionsByTask");
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
        if (resumeState == null || resumeState.fallbackState() == null) {
            return new ArrayList<>();
        }
        Object raw = resumeState.fallbackState().get("lifecycleEvents");
        if (!(raw instanceof List<?> list)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(list.stream().map(String::valueOf).toList());
    }

    /**
     * 把一连串任务拆成一波一波（wave）可执行的任务组，wave考虑了任务的依赖关系
     * @param tasks
     * @param topology
     * @return
     */
    private List<List<SubAgentTask>> buildExecutionWaves(List<SubAgentTask> tasks, TopologyType topology) {
        if (topology == TopologyType.SERIAL_DAG) {
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
                            candidates -> candidates.isEmpty()
                                    ? List.of(pending.values().iterator().next())
                                    : List.copyOf(candidates)
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
            TopologyType topology,
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
            if (dependencyFailed && topology != TopologyType.HYBRID) {
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

    private void runWorkerActions(List<String> workerIds, WorkerAction action) {
        if (workerIds == null || workerIds.isEmpty()) {
            return;
        }
        ExecutorService executor = NamedExecutors.newFixedPool("team-agent-wave", Math.min(properties.getMaxParallelAgents(), Math.max(1, workerIds.size())));
        try {
            List<Callable<Void>> tasks = workerIds.stream()
                    .<Callable<Void>>map(workerId -> () -> {
                        action.run(workerId);
                        return null;
                    })
                    .toList();
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get();
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to execute team-agent wave in parallel", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean shouldStop(OrchestrationContext context) {
        if (context == null || context.runtimeAttributes() == null) {
            return false;
        }
        // raw支持多种动态判断方式，不只是判断布尔值，还可以是其他函数类型
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

    private void dispatchTasks(
            String repoRoot,
            String teamId,
            List<SubAgentTask> tasks,
            List<String> workerIds,
            Map<String, SubAgentResult> completed,
            Map<String, Long> inboxOffsets,
            List<String> lifecycleEvents,
            Map<String, List<String>> permissionsByTask,
            Map<String, TaskExecutionStatus> taskStates
    ) {
        for (int i = 0; i < tasks.size(); i++) {
            SubAgentTask task = enrichTaskWithDependencyEvidence(tasks.get(i), completed, permissionsByTask);
            String workerId = workerIds.get(i % workerIds.size());
            taskStates.put(task.taskId(), TaskExecutionStatus.RUNNING);
            mailboxBus.send(repoRoot, new TeamAgentMessage(
                    UUID.randomUUID().toString(),
                    teamId,
                    LEAD_AGENT_ID,
                    workerId,
                    TeamAgentMessageType.TASK_ASSIGNMENT,
                    task.title(),
                    Map.of("task", objectMapper.convertValue(task, Map.class)),
                    System.currentTimeMillis()
            ));
            incrementOffset(inboxOffsets, workerId, 1L);
            lifecycleEvents.add(workerId + ":task_assigned:" + task.taskId());
        }
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

    private void pollWorkerInbox(
            OrchestrationContext context,
            String teamId,
            String workerId,
            Map<String, Long> inboxOffsets,
            Map<String, WorkerPendingTask> pendingByWorker,
            List<String> lifecycleEvents
    ) {
        List<TeamAgentMessage> inbox = mailboxBus.readInbox(context.request().repoRoot(), teamId, workerId, true);
        incrementOffset(inboxOffsets, workerId, inbox.size());
        for (TeamAgentMessage message : inbox) {
            if (message.type() != TeamAgentMessageType.TASK_ASSIGNMENT) {
                continue;
            }
            SubAgentTask task = objectMapper.convertValue(message.payload().get("task"), SubAgentTask.class);
            // 向leader agent所要权限
            List<String> requestedPermissions = requestedPermissions(task);
            if (!requestedPermissions.isEmpty()) {
                mailboxBus.send(context.request().repoRoot(), new TeamAgentMessage(
                        UUID.randomUUID().toString(),
                        teamId,
                        workerId,
                        LEAD_AGENT_ID,
                        TeamAgentMessageType.PERMISSION_REQUEST,
                        "request permissions for " + task.taskId(),
                        Map.of("taskId", task.taskId(), "permissions", requestedPermissions),
                        System.currentTimeMillis()
                ));
                incrementOffset(inboxOffsets, LEAD_AGENT_ID, 1L);
                pendingByWorker.put(workerId, new WorkerPendingTask(task, requestedPermissions));
                lifecycleEvents.add(workerId + ":permission_requested:" + task.taskId());
                continue;
            }
            executeAssignedTask(context, teamId, workerId, task, inboxOffsets, lifecycleEvents);
        }
    }

    private List<TeamAgentMessage> handleLeadPermissionRequests(
            String repoRoot,
            String teamId,
            Map<String, Long> inboxOffsets,
            List<String> lifecycleEvents
    ) {
        List<TeamAgentMessage> leadMessages = mailboxBus.readInbox(repoRoot, teamId, LEAD_AGENT_ID, true);
        incrementOffset(inboxOffsets, LEAD_AGENT_ID, leadMessages.size());
        List<TeamAgentMessage> residualMessages = new ArrayList<>();
        for (TeamAgentMessage message : leadMessages) {
            if (message.type() != TeamAgentMessageType.PERMISSION_REQUEST) {
                residualMessages.add(message);
                continue;
            }
            List<String> permissions = ((List<?>) message.payload().getOrDefault("permissions", List.of())).stream()
                    .map(String::valueOf)
                    .toList();
            boolean approved = permissions.stream().allMatch(this::isTeamPermissionApprovable);
            mailboxBus.send(repoRoot, new TeamAgentMessage(
                    UUID.randomUUID().toString(),
                    teamId,
                    LEAD_AGENT_ID,
                    message.fromAgentId(),
                    TeamAgentMessageType.PERMISSION_RESPONSE,
                    approved ? "approved" : "denied",
                    Map.of(
                            "taskId", String.valueOf(message.payload().getOrDefault("taskId", "")),
                            "approved", approved,
                            "permissions", permissions
                    ),
                    System.currentTimeMillis()
            ));
            incrementOffset(inboxOffsets, message.fromAgentId(), 1L);
            lifecycleEvents.add(message.fromAgentId() + ":permission_" + (approved ? "approved" : "denied") + ":" + message.payload().getOrDefault("taskId", ""));
        }
        return List.copyOf(residualMessages);
    }

    private void pollWorkerPermissionResponses(
            OrchestrationContext context,
            String teamId,
            String workerId,
            Map<String, WorkerPendingTask> pendingByWorker,
            Map<String, Long> inboxOffsets,
            List<String> lifecycleEvents
    ) {
        List<TeamAgentMessage> inbox = mailboxBus.readInbox(context.request().repoRoot(), teamId, workerId, true);
        incrementOffset(inboxOffsets, workerId, inbox.size());
        WorkerPendingTask pending = pendingByWorker.get(workerId);
        for (TeamAgentMessage message : inbox) {
            if (message.type() != TeamAgentMessageType.PERMISSION_RESPONSE || pending == null) {
                continue;
            }
            boolean approved = Boolean.parseBoolean(String.valueOf(message.payload().getOrDefault("approved", false)));
            if (approved) {
                executeAssignedTask(context, teamId, workerId, pending.task(), inboxOffsets, lifecycleEvents);
            } else {
                SubAgentResult denied = new SubAgentResult(
                        pending.task().taskId(),
                        workerId,
                        false,
                        "Lead 未批准所需权限，子任务被阻断",
                        List.of("permission denied"),
                        List.of(),
                        List.of("Lead 可改由主 Agent 接管，或缩小任务权限范围"),
                        0.1d,
                        Map.of("requestedPermissions", pending.requestedPermissions())
                );
                sendResult(context.request().repoRoot(), teamId, workerId, denied, pending.task().taskId(), inboxOffsets, lifecycleEvents);
            }
            pendingByWorker.remove(workerId);
        }
    }

    private void executeAssignedTask(
            OrchestrationContext context,
            String teamId,
            String workerId,
            SubAgentTask task,
            Map<String, Long> inboxOffsets,
            List<String> lifecycleEvents
    ) {
        lifecycleEvents.add(workerId + ":running:" + task.taskId());
        SubAgentResult result;
        try {
            result = executingSubAgent.executeAsTeamAgent(task, context, teamId, workerId);
        } catch (Exception e) {
            result = new SubAgentResult(
                    task.taskId(),
                    workerId,
                    false,
                    "Team agent 执行失败: " + e.getMessage(),
                    List.of("team agent execution failed"),
                    List.of(),
                    List.of("Lead 可接管该任务，或拆小后重试"),
                    0.1d,
                    Map.of("error", e.getClass().getSimpleName())
            );
        }
        sendResult(context.request().repoRoot(), teamId, workerId, result, task.taskId(), inboxOffsets, lifecycleEvents);
    }

    private void sendResult(
            String repoRoot,
            String teamId,
            String workerId,
            SubAgentResult result,
            String taskId,
            Map<String, Long> inboxOffsets,
            List<String> lifecycleEvents
    ) {
        mailboxBus.send(repoRoot, new TeamAgentMessage(
                UUID.randomUUID().toString(),
                teamId,
                workerId,
                LEAD_AGENT_ID,
                TeamAgentMessageType.RESULT,
                result.summary(),
                Map.of("result", objectMapper.convertValue(result, Map.class)),
                System.currentTimeMillis()
        ));
        mailboxBus.send(repoRoot, new TeamAgentMessage(
                UUID.randomUUID().toString(),
                teamId,
                workerId,
                LEAD_AGENT_ID,
                TeamAgentMessageType.IDLE_NOTIFICATION,
                workerId + " idle",
                Map.of("taskId", taskId),
                System.currentTimeMillis()
        ));
        incrementOffset(inboxOffsets, LEAD_AGENT_ID, 2L);
        lifecycleEvents.add(workerId + ":" + (result.success() ? "succeeded" : "failed") + ":" + taskId);
        lifecycleEvents.add(workerId + ":idle:" + taskId);
    }

    private List<TeamAgentMessage> readLeadInbox(String repoRoot, String teamId, Map<String, Long> inboxOffsets) {
        List<TeamAgentMessage> messages = mailboxBus.readInbox(repoRoot, teamId, LEAD_AGENT_ID, true);
        incrementOffset(inboxOffsets, LEAD_AGENT_ID, messages.size());
        return messages;
    }

    private void collectLeadResults(
            List<TeamAgentMessage> messages,
            Map<String, SubAgentResult> completed,
            Map<String, TaskExecutionStatus> taskStates,
            List<String> lifecycleEvents
    ) {
        for (TeamAgentMessage message : messages) {
            if (message.type() == TeamAgentMessageType.RESULT && message.payload() != null && message.payload().get("result") != null) {
                SubAgentResult result = objectMapper.convertValue(message.payload().get("result"), SubAgentResult.class);
                completed.put(result.taskId(), result);
                taskStates.put(result.taskId(), result.success() ? TaskExecutionStatus.SUCCEEDED : TaskExecutionStatus.FAILED);
            } else if (message.type() == TeamAgentMessageType.IDLE_NOTIFICATION) {
                lifecycleEvents.add(message.fromAgentId() + ":idle_notice");
            }
        }
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

    private List<String> shutdownWorkers(String repoRoot, String teamId, List<String> workerIds, Map<String, Long> inboxOffsets) {
        LinkedHashSet<String> lifecycleEvents = new LinkedHashSet<>();
        for (String workerId : workerIds) {
            mailboxBus.send(repoRoot, new TeamAgentMessage(
                    UUID.randomUUID().toString(),
                    teamId,
                    LEAD_AGENT_ID,
                    workerId,
                    TeamAgentMessageType.SHUTDOWN_REQUEST,
                    "shutdown",
                    Map.of(),
                    System.currentTimeMillis()
            ));
            incrementOffset(inboxOffsets, workerId, 1L);
        }
        for (String workerId : workerIds) {
            List<TeamAgentMessage> workerMessages = mailboxBus.readInbox(repoRoot, teamId, workerId, true);
            incrementOffset(inboxOffsets, workerId, workerMessages.size());
            for (TeamAgentMessage message : workerMessages) {
                if (message.type() == TeamAgentMessageType.SHUTDOWN_REQUEST) {
                    lifecycleEvents.add(workerId + ":shutdown_requested");
                    mailboxBus.send(repoRoot, new TeamAgentMessage(
                            UUID.randomUUID().toString(),
                            teamId,
                            workerId,
                            LEAD_AGENT_ID,
                            TeamAgentMessageType.SHUTDOWN_APPROVED,
                            workerId + " shutdown",
                            Map.of(),
                            System.currentTimeMillis()
                    ));
                    incrementOffset(inboxOffsets, LEAD_AGENT_ID, 1L);
                }
            }
        }
        List<TeamAgentMessage> leadMessages = readLeadInbox(repoRoot, teamId, inboxOffsets);
        for (TeamAgentMessage message : leadMessages) {
            lifecycleEvents.add(message.fromAgentId() + ":" + message.type().name().toLowerCase());
        }
        return List.copyOf(lifecycleEvents);
    }

    private List<String> requestedPermissions(SubAgentTask task) {
        Object raw = task.metadata() == null ? null : task.metadata().get("requestedPermissions");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private List<String> requiredPermissionNames(SubAgentCapability capability) {
        Set<AgentToolPermission> permissions = childAgentPermissionPolicy.permissionsFor(MultiAgentExecutionScope.Role.TEAM_AGENT, capability);
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

    private boolean isTeamPermissionApprovable(String permissionName) {
        return "BUILD_COMPILE".equals(permissionName) || "BROWSER_READ".equals(permissionName);
    }

    private void incrementOffset(Map<String, Long> inboxOffsets, String inboxId, long delta) {
        inboxOffsets.merge(inboxId, delta, Long::sum);
    }

    private MultiAgentExecutionState snapshot(
            String teamId,
            OrchestrationPlan plan,
            Map<String, TaskExecutionStatus> taskStates,
            Map<String, SubAgentResult> completed,
            Map<String, Long> inboxOffsets,
            int currentRound,
            List<String> lifecycleEvents,
            Map<String, List<String>> permissionsByTask
    ) {
        return new MultiAgentExecutionState(
                plan.planId(),
                "TEAM_AGENT",
                plan.topology(),
                plan,
                Map.copyOf(taskStates),
                completed.values().stream().sorted(Comparator.comparing(SubAgentResult::taskId)).toList(),
                Map.copyOf(inboxOffsets),
                currentRound,
                Map.of(
                        "teamId", teamId,
                        "paused", hasPendingTasks(taskStates),
                        "lifecycleEvents", List.copyOf(lifecycleEvents),
                        "permissionsByTask", Map.copyOf(permissionsByTask)
                )
        );
    }

    private boolean hasPendingTasks(Map<String, TaskExecutionStatus> taskStates) {
        return taskStates.values().stream().anyMatch(status -> status == TaskExecutionStatus.PENDING || status == TaskExecutionStatus.RUNNING);
    }

    private String stableTeamId(OrchestrationContext context) {
        String sessionId = context.request() == null ? null : context.request().sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return "team-anonymous";
        }
        return "team-" + sessionId.replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    public record ExecutionResult(
            String teamId,
            List<SubAgentResult> results,
            List<String> lifecycleEvents,
            MultiAgentExecutionState executionState
    ) {
    }

    private record WorkerPendingTask(SubAgentTask task, List<String> requestedPermissions) {
    }

    @FunctionalInterface
    private interface WorkerAction {
        void run(String workerId);
    }
}
