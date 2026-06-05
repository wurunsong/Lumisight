package com.lumisight.core.agent.multiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.agent.multiagent.model.MultiAgentExecutionState;
import com.lumisight.core.agent.multiagent.model.OrchestrationContext;
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
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class TeamAgentExecutionService {

    private static final String LEAD_AGENT_ID = "lead";

    private final AgentMailboxBus mailboxBus;
    private final ExecutingSubAgent executingSubAgent;
    private final MultiAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final ChildAgentPermissionPolicy childAgentPermissionPolicy;

    public TeamAgentExecutionService(
            AgentMailboxBus mailboxBus,
            ExecutingSubAgent executingSubAgent,
            MultiAgentProperties properties,
            ObjectMapper objectMapper,
            ChildAgentPermissionPolicy childAgentPermissionPolicy
    ) {
        this.mailboxBus = mailboxBus;
        this.executingSubAgent = executingSubAgent;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.childAgentPermissionPolicy = childAgentPermissionPolicy;
    }

    public ExecutionResult executePlan(OrchestrationPlan plan, OrchestrationContext context) {
        String teamId = "team-" + plan.planId();
        String repoRoot = context.request().repoRoot();
        List<SubAgentTask> tasks = plan.tasks() == null ? List.of() : plan.tasks();
        List<String> workerIds = allocateWorkers(tasks, plan.topology());
        Map<String, TaskExecutionStatus> taskStates = initTaskStates(tasks);
        Map<String, Long> inboxOffsets = new LinkedHashMap<>();
        Map<String, SubAgentResult> completed = new LinkedHashMap<>();
        Map<String, List<String>> permissionsByTask = new LinkedHashMap<>();
        List<String> lifecycleEvents = new ArrayList<>();

        List<List<SubAgentTask>> waves = buildExecutionWaves(tasks, plan.topology());
        int currentRound = 0;
        for (List<SubAgentTask> wave : waves) {
            currentRound++;
            List<SubAgentTask> runnable = filterRunnableTasks(wave, plan.topology(), completed, taskStates, lifecycleEvents);
            if (runnable.isEmpty()) {
                continue;
            }
            dispatchTasks(repoRoot, teamId, runnable, workerIds, completed, inboxOffsets, lifecycleEvents, permissionsByTask, taskStates);
            Map<String, WorkerPendingTask> pendingByWorker = new LinkedHashMap<>();
            for (String workerId : workerIds) {
                pollWorkerInbox(context, teamId, workerId, inboxOffsets, pendingByWorker, lifecycleEvents);
            }
            List<TeamAgentMessage> bufferedLeadMessages = List.of();
            if (!pendingByWorker.isEmpty()) {
                bufferedLeadMessages = handleLeadPermissionRequests(repoRoot, teamId, inboxOffsets, lifecycleEvents);
                for (String workerId : new ArrayList<>(pendingByWorker.keySet())) {
                    pollWorkerPermissionResponses(context, teamId, workerId, pendingByWorker, inboxOffsets, lifecycleEvents);
                }
            }
            List<TeamAgentMessage> leadMessages = new ArrayList<>(bufferedLeadMessages);
            leadMessages.addAll(readLeadInbox(repoRoot, teamId, inboxOffsets));
            collectLeadResults(leadMessages, completed, taskStates, lifecycleEvents);
            runnable.forEach(task -> taskStates.computeIfAbsent(task.taskId(), ignored -> TaskExecutionStatus.PENDING));
            markWaveCompletions(runnable, completed, taskStates);
        }

        lifecycleEvents.addAll(shutdownWorkers(repoRoot, teamId, workerIds, inboxOffsets));
        MultiAgentExecutionState state = new MultiAgentExecutionState(
                plan.planId(),
                "TEAM_AGENT",
                plan.topology(),
                plan,
                Map.copyOf(taskStates),
                completed.values().stream().sorted(Comparator.comparing(SubAgentResult::taskId)).toList(),
                Map.copyOf(inboxOffsets),
                currentRound,
                Map.of(
                        "lifecycleEvents", List.copyOf(lifecycleEvents),
                        "permissionsByTask", Map.copyOf(permissionsByTask)
                )
        );
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

    private Map<String, TaskExecutionStatus> initTaskStates(List<SubAgentTask> tasks) {
        Map<String, TaskExecutionStatus> states = new LinkedHashMap<>();
        for (SubAgentTask task : tasks) {
            states.put(task.taskId(), TaskExecutionStatus.PENDING);
        }
        return states;
    }

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
                    .toList();
            if (ready.isEmpty()) {
                ready = List.of(pending.values().iterator().next());
            }
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
        SubAgentResult result = executingSubAgent.executeAsTeamAgent(task, context, teamId, workerId);
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
        Set<AgentToolPermission> permissions = childAgentPermissionPolicy.permissionsFor(MultiAgentExecutionContext.Role.TEAM_AGENT, capability);
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

    public record ExecutionResult(
            String teamId,
            List<SubAgentResult> results,
            List<String> lifecycleEvents,
            MultiAgentExecutionState executionState
    ) {
    }

    private record WorkerPendingTask(SubAgentTask task, List<String> requestedPermissions) {
    }
}
