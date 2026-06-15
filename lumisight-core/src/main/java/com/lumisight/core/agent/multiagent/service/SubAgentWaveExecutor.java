package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.AgentLoopTask;
import com.lumisight.core.agent.AgentLoopTaskRunner;
import com.lumisight.core.agent.CompletedAgentLoopTask;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 执行一个已经规划好的 wave。
 * 这里关心的是“准备好的 loop task 如何进入线程池并收口”，不参与上层编排方式选择。
 */
@Component
class SubAgentWaveExecutor {

    private final ExecutingSubAgent executingSubAgent;
    private final AgentLoopTaskRunner agentLoopTaskRunner;

    SubAgentWaveExecutor(
            ExecutingSubAgent executingSubAgent,
            AgentLoopTaskRunner agentLoopTaskRunner
    ) {
        this.executingSubAgent = executingSubAgent;
        this.agentLoopTaskRunner = agentLoopTaskRunner;
    }

    SubAgentWaveExecutionResult execute(SubAgentWavePlan wavePlan) {
        PreparedWave preparedWave = prepare(wavePlan);
        completeLoopTasks(preparedWave, agentLoopTaskRunner.runWave(preparedWave.loopTasks(), wavePlan.parallelism()));
        return preparedWave.toExecutionResult();
    }

    private PreparedWave prepare(SubAgentWavePlan wavePlan) {
        PreparedWave preparedWave = new PreparedWave(wavePlan);
        for (SubAgentWaveWorkItem workItem : wavePlan.workItems()) {
            preparedWave.markRunning(workItem);
            try {
                SubAgentExecutionService.PreparedSubAgentExecution preparedExecution = executingSubAgent.prepareAssigned(
                        workItem.task(),
                        wavePlan.context(),
                        wavePlan.coordinationId(),
                        workItem.workerId()
                );
                if (preparedExecution.completedBeforeLoop()) {
                    SubAgentResult result = executingSubAgent.completePrepared(preparedExecution, null);
                    preparedWave.completeImmediately(workItem, result);
                    continue;
                }
                AgentLoopTask<CompletedAgentLoopTask> loopTask = executingSubAgent.toAgentLoopTask(preparedExecution);
                preparedWave.addPendingLoop(workItem, preparedExecution, loopTask);
            } catch (Exception e) {
                preparedWave.failBeforeLoop(workItem, e);
            }
        }
        return preparedWave;
    }

    private void completeLoopTasks(PreparedWave preparedWave, List<CompletedAgentLoopTask> completedLoops) {
        for (CompletedAgentLoopTask completedLoop : completedLoops) {
            PreparedWorkItem preparedWorkItem = preparedWave.pendingByLoopTaskId().get(completedLoop.taskId());
            if (preparedWorkItem == null) {
                continue;
            }
            SubAgentResult result = executingSubAgent.completePrepared(preparedWorkItem.preparedExecution(), completedLoop);
            preparedWave.completeLoop(preparedWorkItem.workItem(), result);
        }
    }

    private SubAgentResult failedWorkItemResult(SubAgentWaveWorkItem workItem, Exception e) {
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

    private final class PreparedWave {
        private final SubAgentWavePlan wavePlan;
        private final Map<String, WorkItemResult> resultByTaskId = new LinkedHashMap<>();
        private final Map<String, PreparedWorkItem> pendingByLoopTaskId = new LinkedHashMap<>();
        private final List<AgentLoopTask<CompletedAgentLoopTask>> loopTasks = new ArrayList<>();
        private final Map<String, List<String>> lifecycleByTaskId = new LinkedHashMap<>();

        private PreparedWave(SubAgentWavePlan wavePlan) {
            this.wavePlan = wavePlan;
        }

        private void markRunning(SubAgentWaveWorkItem workItem) {
            lifecycleByTaskId.put(workItem.task().taskId(), new ArrayList<>(List.of(
                    workItem.workerId() + ":wave_" + wavePlan.waveNumber() + ":running:" + workItem.task().taskId()
            )));
        }

        private void completeImmediately(SubAgentWaveWorkItem workItem, SubAgentResult result) {
            markCompleted(workItem, result);
        }

        private void addPendingLoop(
                SubAgentWaveWorkItem workItem,
                SubAgentExecutionService.PreparedSubAgentExecution preparedExecution,
                AgentLoopTask<CompletedAgentLoopTask> loopTask
        ) {
            pendingByLoopTaskId.put(loopTask.taskId(), new PreparedWorkItem(workItem, preparedExecution));
            loopTasks.add(loopTask);
        }

        private void failBeforeLoop(SubAgentWaveWorkItem workItem, Exception e) {
            SubAgentResult result = failedWorkItemResult(workItem, e);
            lifecycleByTaskId.computeIfAbsent(workItem.task().taskId(), ignored -> new ArrayList<>())
                    .add(workItem.workerId() + ":failed:" + workItem.task().taskId());
            resultByTaskId.put(workItem.task().taskId(), new WorkItemResult(result, List.copyOf(lifecycleByTaskId.get(workItem.task().taskId()))));
        }

        private void completeLoop(SubAgentWaveWorkItem workItem, SubAgentResult result) {
            markCompleted(workItem, result);
        }

        private void markCompleted(SubAgentWaveWorkItem workItem, SubAgentResult result) {
            lifecycleByTaskId.computeIfAbsent(workItem.task().taskId(), ignored -> new ArrayList<>())
                    .add(workItem.workerId() + ":" + (result.success() ? "succeeded" : "failed") + ":" + workItem.task().taskId());
            resultByTaskId.put(workItem.task().taskId(), new WorkItemResult(result, List.copyOf(lifecycleByTaskId.get(workItem.task().taskId()))));
        }

        private List<AgentLoopTask<CompletedAgentLoopTask>> loopTasks() {
            return List.copyOf(loopTasks);
        }

        private Map<String, PreparedWorkItem> pendingByLoopTaskId() {
            return pendingByLoopTaskId;
        }

        private SubAgentWaveExecutionResult toExecutionResult() {
            List<SubAgentResult> results = new ArrayList<>(resultByTaskId.size());
            List<String> lifecycleEvents = new ArrayList<>();
            for (SubAgentWaveWorkItem workItem : wavePlan.workItems()) {
                WorkItemResult result = resultByTaskId.get(workItem.task().taskId());
                if (result == null) {
                    continue;
                }
                results.add(result.result());
                lifecycleEvents.addAll(result.lifecycleEvents());
            }
            return new SubAgentWaveExecutionResult(List.copyOf(results), List.copyOf(lifecycleEvents));
        }
    }

    private record PreparedWorkItem(
            SubAgentWaveWorkItem workItem,
            SubAgentExecutionService.PreparedSubAgentExecution preparedExecution
    ) {
    }

    private record WorkItemResult(SubAgentResult result, List<String> lifecycleEvents) {
    }
}
