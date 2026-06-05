package com.lumisight.core.agent.multiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamAgentExecutionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldTrackPermissionLifecycleAndSucceededStates() {
        ExecutingSubAgent subAgent = mock(ExecutingSubAgent.class);
        when(subAgent.executeAsTeamAgent(any(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    SubAgentTask task = invocation.getArgument(0);
                    String agentId = invocation.getArgument(3);
                    return new SubAgentResult(
                            task.taskId(),
                            agentId,
                            true,
                            task.title() + " done",
                            List.of("ok"),
                            List.of("tool:cat"),
                            List.of("next"),
                            0.9d,
                            Map.of()
                    );
                });
        TeamAgentExecutionService service = service(subAgent);

        TeamAgentExecutionService.ExecutionResult result = service.executePlan(plan(TopologyType.FAN_OUT_FAN_IN, false), context());

        assertEquals(2, result.results().size());
        assertTrue(result.executionState().taskStates().values().stream().allMatch(status -> status == TaskExecutionStatus.SUCCEEDED));
        assertTrue(result.lifecycleEvents().stream().anyMatch(event -> event.contains("permission_approved")));
        assertTrue(result.lifecycleEvents().stream().anyMatch(event -> event.contains("shutdown_approved")));
        assertTrue(result.executionState().inboxOffsets().containsKey("lead"));
    }

    @Test
    void shouldBlockDependentTaskWhenUpstreamFailsInNonHybridTopology() {
        ExecutingSubAgent subAgent = mock(ExecutingSubAgent.class);
        when(subAgent.executeAsTeamAgent(any(), any(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    SubAgentTask task = invocation.getArgument(0);
                    boolean success = !"task-scan".equals(task.taskId());
                    return new SubAgentResult(
                            task.taskId(),
                            invocation.getArgument(3),
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

    private TeamAgentExecutionService service(ExecutingSubAgent subAgent) {
        MultiAgentProperties properties = new MultiAgentProperties();
        properties.setMaxParallelAgents(3);
        properties.setAllowTeamAgent(true);
        properties.setTeamRootDir(".lumisight/teams");
        return new TeamAgentExecutionService(
                new FileAgentMailboxBus(properties, new ObjectMapper()),
                subAgent,
                properties,
                new ObjectMapper(),
                new ChildAgentPermissionPolicy()
        );
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
                        false,
                        true,
                        true,
                        6,
                        AgentRunMode.MULTI_AGENT,
                        AgentDialogueMode.FOLLOW
                ),
                Map.of()
        );
    }
}
