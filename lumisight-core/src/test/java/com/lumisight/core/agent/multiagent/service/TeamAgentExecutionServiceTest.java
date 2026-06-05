package com.lumisight.core.agent.multiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.agent.multiagent.model.MultiAgentExecutionState;
import com.lumisight.core.agent.multiagent.model.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.agent.multiagent.model.TaskExecutionStatus;
import com.lumisight.core.agent.multiagent.model.TopologyType;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.AgentTaskType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamAgentExecutionServiceTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldTrackPermissionLifecycleAndSucceededStates() {
        ExecutingSubAgent subAgent = stubSubAgent((task, agentId) -> new SubAgentResult(
                task.taskId(),
                agentId,
                true,
                task.title() + " done",
                List.of("ok"),
                List.of("tool:cat"),
                List.of("next"),
                0.9d,
                Map.of()
        ));
        TeamAgentExecutionService service = service(subAgent);
        MultiAgentExecutionStateStore stateStore = stateStore();

        TeamAgentExecutionService.ExecutionResult result = service.executePlan(plan(TopologyType.FAN_OUT_FAN_IN, false), context());

        assertEquals(2, result.results().size());
        assertTrue(result.executionState().taskStates().values().stream().allMatch(status -> status == TaskExecutionStatus.SUCCEEDED));
        assertTrue(result.lifecycleEvents().stream().anyMatch(event -> event.contains("permission_approved")));
        assertTrue(result.lifecycleEvents().stream().anyMatch(event -> event.contains("shutdown_approved")));
        assertTrue(result.executionState().inboxOffsets().containsKey("lead"));
        MultiAgentExecutionState persisted = stateStore.load(tempDir.toString(), "team-session").orElseThrow();
        assertTrue(persisted.taskStates().values().stream().allMatch(status -> status == TaskExecutionStatus.SUCCEEDED));
    }

    @Test
    void shouldBlockDependentTaskWhenUpstreamFailsInNonHybridTopology() {
        ExecutingSubAgent subAgent = stubSubAgent((task, agentId) -> {
            boolean success = !"task-scan".equals(task.taskId());
            return new SubAgentResult(
                    task.taskId(),
                    agentId,
                    success,
                    success ? "scan ok" : "summary should not run",
                    List.of(success ? "ok" : "failed"),
                    List.of(),
                    List.of(),
                    success ? 0.8d : 0.2d,
                    Map.of()
            );
        });
        TeamAgentExecutionService service = service(subAgent);

        TeamAgentExecutionService.ExecutionResult result = service.executePlan(plan(TopologyType.FAN_OUT_FAN_IN, true), context());

        assertEquals(TaskExecutionStatus.FAILED, result.executionState().taskStates().get("task-scan"));
        assertEquals(TaskExecutionStatus.BLOCKED, result.executionState().taskStates().get("task-summary"));
        assertEquals(1, result.results().size());
        assertTrue(result.lifecycleEvents().stream().anyMatch(event -> event.contains("blocked_by_failed_dependency")));
    }

    @Test
    void shouldResumeOnlyRemainingTasksFromPersistedState() {
        AtomicInteger executionCount = new AtomicInteger();
        ExecutingSubAgent subAgent = stubSubAgent((task, agentId) -> {
            executionCount.incrementAndGet();
            return new SubAgentResult(
                    task.taskId(),
                    agentId,
                    true,
                    task.title() + " resumed",
                    List.of("resumed"),
                    List.of(),
                    List.of(),
                    0.95d,
                    Map.of()
            );
        });
        MultiAgentExecutionStateStore stateStore = stateStore();
        stateStore.save(tempDir.toString(), "team-session", new MultiAgentExecutionState(
                "plan-test",
                "TEAM_AGENT",
                TopologyType.FAN_OUT_FAN_IN,
                plan(TopologyType.FAN_OUT_FAN_IN, true),
                Map.of(
                        "task-scan", TaskExecutionStatus.SUCCEEDED,
                        "task-summary", TaskExecutionStatus.PENDING
                ),
                List.of(new SubAgentResult(
                        "task-scan",
                        "team-agent-1",
                        true,
                        "scan done",
                        List.of("ok"),
                        List.of("tool:cat"),
                        List.of("summarize"),
                        0.8d,
                        Map.of()
                )),
                Map.of("lead", 2L),
                1,
                Map.of("lifecycleEvents", List.of("team-agent-1:succeeded:task-scan"))
        ));
        TeamAgentExecutionService service = service(subAgent);

        TeamAgentExecutionService.ExecutionResult result = service.executePlan(plan(TopologyType.FAN_OUT_FAN_IN, true), resumeContext());

        assertEquals(1, executionCount.get());
        assertEquals(TaskExecutionStatus.SUCCEEDED, result.executionState().taskStates().get("task-scan"));
        assertEquals(TaskExecutionStatus.SUCCEEDED, result.executionState().taskStates().get("task-summary"));
        assertEquals(2, result.results().size());
        assertTrue(result.lifecycleEvents().stream().anyMatch(event -> event.contains("resume_loaded")));
        assertFalse(result.lifecycleEvents().stream().anyMatch(event -> event.contains("task_assigned:task-scan")));
    }

    @Test
    void shouldPauseAtWaveBoundaryAndResumePendingTasks() {
        AtomicInteger stopChecks = new AtomicInteger();
        AtomicInteger executionCount = new AtomicInteger();
        ExecutingSubAgent subAgent = stubSubAgent((task, agentId) -> {
            executionCount.incrementAndGet();
            return new SubAgentResult(
                    task.taskId(),
                    agentId,
                    true,
                    task.title() + " done",
                    List.of("ok"),
                    List.of(),
                    List.of(),
                    0.9d,
                    Map.of()
            );
        });
        TeamAgentExecutionService service = service(subAgent);

        TeamAgentExecutionService.ExecutionResult paused = service.executePlan(
                plan(TopologyType.FAN_OUT_FAN_IN, true),
                orchestrationContext(false, () -> stopChecks.incrementAndGet() >= 2)
        );

        assertEquals(1, executionCount.get());
        assertEquals(TaskExecutionStatus.SUCCEEDED, paused.executionState().taskStates().get("task-scan"));
        assertEquals(TaskExecutionStatus.PENDING, paused.executionState().taskStates().get("task-summary"));
        assertTrue(Boolean.TRUE.equals(paused.executionState().fallbackState().get("paused")));
        assertTrue(paused.lifecycleEvents().stream().anyMatch(event -> event.contains("paused_after_wave")));
        assertFalse(paused.lifecycleEvents().stream().anyMatch(event -> event.contains("shutdown_approved")));

        TeamAgentExecutionService.ExecutionResult resumed = service.executePlan(
                plan(TopologyType.FAN_OUT_FAN_IN, true),
                resumeContext()
        );

        assertEquals(2, executionCount.get());
        assertEquals(TaskExecutionStatus.SUCCEEDED, resumed.executionState().taskStates().get("task-summary"));
        assertFalse(Boolean.TRUE.equals(resumed.executionState().fallbackState().get("paused")));
    }

    private TeamAgentExecutionService service(ExecutingSubAgent subAgent) {
        MultiAgentProperties properties = new MultiAgentProperties();
        properties.setMaxParallelAgents(3);
        properties.setAllowTeamAgent(true);
        properties.setTeamRootDir(".lumisight/teams");
        MultiAgentExecutionStateStore stateStore = new MultiAgentExecutionStateStore(properties, objectMapper);
        return new TeamAgentExecutionService(
                new FileAgentMailboxBus(properties, objectMapper),
                subAgent,
                properties,
                objectMapper,
                new ChildAgentPermissionPolicy(),
                stateStore
        );
    }

    private MultiAgentExecutionStateStore stateStore() {
        MultiAgentProperties properties = new MultiAgentProperties();
        properties.setTeamRootDir(".lumisight/teams");
        return new MultiAgentExecutionStateStore(properties, objectMapper);
    }

    private ExecutingSubAgent stubSubAgent(BiFunction<SubAgentTask, String, SubAgentResult> executor) {
        return new ExecutingSubAgent(null) {
            @Override
            public SubAgentResult executeAsTeamAgent(SubAgentTask task, OrchestrationContext context, String teamId, String agentId) {
                return executor.apply(task, agentId);
            }
        };
    }

    private OrchestrationPlan plan(TopologyType topology, boolean withDependency) {
        SubAgentTask first = new SubAgentTask(
                "task-scan",
                "scan",
                "scan code",
                SubAgentCapability.CODE_EXPLAIN,
                Map.of("question", "q1"),
                "output",
                List.of(),
                "scan",
                Map.of("maxRounds", 4),
                100,
                Map.of("repoRoot", tempDir.toString())
        );
        SubAgentTask second = new SubAgentTask(
                "task-summary",
                "summary",
                "summarize",
                SubAgentCapability.BUG_FIX,
                Map.of("question", "q2"),
                "output",
                withDependency ? List.of(first.taskId()) : List.of(),
                "summary",
                Map.of("maxRounds", 4),
                90,
                Map.of("repoRoot", tempDir.toString())
        );
        return new OrchestrationPlan(
                "plan-test",
                "goal",
                List.of(first, second),
                topology,
                "contract",
                Map.of()
        );
    }

    private OrchestrationContext context() {
        return orchestrationContext(false, null);
    }

    private OrchestrationContext resumeContext() {
        return orchestrationContext(true, null);
    }

    private OrchestrationContext orchestrationContext(boolean resume, java.util.function.BooleanSupplier shouldStop) {
        return new OrchestrationContext(
                "orch-test",
                new AgentRequest(
                        AgentTaskType.BUG_FIX,
                        tempDir.toString(),
                        "请分别分析多个模块并修复问题",
                        null,
                        "user",
                        "session",
                        false,
                        false,
                        resume,
                        true,
                        true,
                        6,
                        AgentRunMode.MULTI_AGENT,
                        AgentDialogueMode.FOLLOW
                ),
                shouldStop == null ? Map.of() : Map.of("shouldStop", shouldStop)
        );
    }
}
