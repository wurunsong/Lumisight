package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.AgentExecutionProfile;
import com.lumisight.core.agent.AgentExecutionTaskDispatcher;
import com.lumisight.core.agent.AgentLoopTask;
import com.lumisight.core.agent.AgentLoopTaskRunner;
import com.lumisight.core.agent.CompletedAgentLoopTask;
import com.lumisight.core.agent.PreparedAgentLoopTask;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.TaskContextEnvelope;
import com.lumisight.core.context.ambient.MultiAgentExecutionScope;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.AgentTaskType;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.support.AgentSessionContextStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SubAgentExecutionService {

    private final AgentExecutionTaskDispatcher agentExecutionTaskDispatcher;
    private final AgentLoopTaskRunner agentLoopTaskRunner;
    private final AgentSessionContextStore sessionContextStore;
    private final MultiAgentProperties properties;

    public SubAgentExecutionService(
            AgentExecutionTaskDispatcher agentExecutionTaskDispatcher,
            AgentLoopTaskRunner agentLoopTaskRunner,
            AgentSessionContextStore sessionContextStore,
            MultiAgentProperties properties
    ) {
        this.agentExecutionTaskDispatcher = agentExecutionTaskDispatcher;
        this.agentLoopTaskRunner = agentLoopTaskRunner;
        this.sessionContextStore = sessionContextStore;
        this.properties = properties;
    }

    public SubAgentResult execute(TaskContextEnvelope envelope, String parentSessionId) {
        return execute(envelope, parentSessionId, "subagent", null, null);
    }

    public SubAgentResult executeAssigned(
            TaskContextEnvelope envelope,
            String parentSessionId,
            String coordinationId,
            String workerId
    ) {
        return execute(envelope, parentSessionId, workerId, coordinationId, workerId);
    }

    private SubAgentResult execute(
            TaskContextEnvelope envelope,
            String parentSessionId,
            String agentName,
            String coordinationId,
            String agentId
    ) {
        PreparedSubAgentExecution preparedExecution = prepare(envelope, parentSessionId, agentName, coordinationId, agentId);
        if (preparedExecution.completedBeforeLoop()) {
            return completePrepared(preparedExecution, null);
        }
        CompletedAgentLoopTask completedLoop = agentLoopTaskRunner.runBlocking(toAgentLoopTask(preparedExecution));
        return completePrepared(preparedExecution, completedLoop);
    }

    public PreparedSubAgentExecution prepareAssigned(
            TaskContextEnvelope envelope,
            String parentSessionId,
            String coordinationId,
            String workerId
    ) {
        return prepare(envelope, parentSessionId, workerId, coordinationId, workerId);
    }

    public AgentLoopTask<CompletedAgentLoopTask> toAgentLoopTask(PreparedSubAgentExecution preparedExecution) {
        return agentExecutionTaskDispatcher.toAgentLoopTask(preparedExecution.loopTask());
    }

    public SubAgentResult completePrepared(
            PreparedSubAgentExecution preparedExecution,
            CompletedAgentLoopTask completedLoop
    ) {
        try {
            if (preparedExecution.immediateResult() != null) {
                return preparedExecution.immediateResult();
            }
            List<AgentEvent> events = preparedExecution.loopTask().completedBeforeLoop()
                    ? agentExecutionTaskDispatcher.completedEvents(preparedExecution.loopTask())
                    : agentExecutionTaskDispatcher.completePreparedLoopTask(completedLoop);
            return summarize(preparedExecution.envelope(), preparedExecution.agentName(), events == null ? List.of() : events);
        } catch (Exception e) {
            return failedResult(preparedExecution.envelope(), preparedExecution.agentName(), e);
        } finally {
            sessionContextStore.clear(preparedExecution.childSessionId());
        }
    }

    private PreparedSubAgentExecution prepare(
            TaskContextEnvelope envelope,
            String parentSessionId,
            String agentName,
            String coordinationId,
            String agentId
    ) {
        String childSessionId = "subagent-" + envelope.taskId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String orchestrationId = "orch-" + UUID.randomUUID();
        MultiAgentExecutionScope.Context parent = MultiAgentExecutionScope.current();
        int depth = parent == null ? 1 : parent.depth() + 1;
        if (depth > properties.getMaxSubagentDepth()) {
            return PreparedSubAgentExecution.completed(envelope, agentName, childSessionId, new SubAgentResult(
                    envelope.taskId(),
                    "subagent",
                    false,
                    "子 Agent 深度超限，已拒绝递归执行",
                    List.of("max depth exceeded"),
                    List.of(),
                    List.of("由 Lead 继续执行该任务"),
                    0.0d,
                    Map.of("depth", depth, "maxDepth", properties.getMaxSubagentDepth())
            ));
        }

        String childQuestion = buildQuestion(envelope);
        AgentRequest childRequest = new AgentRequest(
                mapTaskType(envelope.capability()),
                envelope.repoRoot(),
                childQuestion,
                null,
                parentSessionId + "/subagent",
                childSessionId,
                false,
                false,
                false,
                true,
                true,
                properties.getChildContextLimit(),
                AgentRunMode.NORMAL,
                AgentDialogueMode.FOLLOW
        );
        long deadlineEpochMs = System.currentTimeMillis() + Math.max(1, properties.getChildTimeoutMs());
        MultiAgentExecutionScope.Context childScope = new MultiAgentExecutionScope.Context(
                MultiAgentExecutionScope.Role.SUB_AGENT,
                orchestrationId,
                parentSessionId,
                envelope.taskId(),
                depth,
                parent == null ? null : parent.orchestrationMode(),
                MultiAgentExecutionScope.Phase.EXECUTION,
                false,
                properties.getSubagentMaxRounds(),
                deadlineEpochMs,
                coordinationId,
                agentId
        );
        try (MultiAgentExecutionScope.Scope ignored = MultiAgentExecutionScope.open(
                childScope
        )) {
            // 子 agent 在这里完成 request / scope / 权限等准备；线程池里的任务只负责执行已经准备好的 loop。
            PreparedAgentLoopTask loopTask = agentExecutionTaskDispatcher.prepareChildLoopTask(
                    childRequest,
                    AgentExecutionProfile.subAgent()
            );
            return PreparedSubAgentExecution.ready(envelope, agentName, childSessionId, loopTask);
        } catch (Exception e) {
            return PreparedSubAgentExecution.completed(envelope, agentName, childSessionId, failedResult(envelope, agentName, e));
        }
    }

    private SubAgentResult failedResult(TaskContextEnvelope envelope, String agentName, Exception e) {
        return new SubAgentResult(
                envelope.taskId(),
                agentName,
                false,
                "子 Agent 执行失败或超时: " + e.getMessage(),
                List.of("subagent execution failed"),
                List.of(),
                List.of("Lead 继续接管，或缩小子任务范围后重试"),
                0.15d,
                Map.of(
                        "taskId", envelope.taskId(),
                        "capability", envelope.capability().name(),
                        "error", e.getClass().getSimpleName()
                )
        );
    }

    private SubAgentResult summarize(TaskContextEnvelope envelope, String agentName, List<AgentEvent> events) {
        // 上层 lead 只需要结构化子任务结论，不应暴露完整事件流，因此这里统一做一次事件到结果的收口。
        String finalAnswer = events.stream()
                .filter(event -> "FINAL".equals(event.type()))
                .map(AgentEvent::message)
                .reduce((a, b) -> b)
                .orElseGet(() -> events.stream()
                        .filter(event -> "TOKEN".equals(event.type()))
                        .map(AgentEvent::message)
                        .reduce("", String::concat));
        boolean success = !finalAnswer.isBlank()
                && events.stream().noneMatch(event -> "ERROR".equals(event.type()) || "HUMAN_GATE".equals(event.type()));
        LinkedHashSet<String> evidenceRefs = new LinkedHashSet<>();
        List<String> findings = new ArrayList<>();
        List<String> suggestedActions = new ArrayList<>();
        for (AgentEvent event : events) {
            if ("TOOL_RESULT".equals(event.type()) && event.toolName() != null && !event.toolName().isBlank()) {
                evidenceRefs.add("tool:" + event.toolName());
            }
            if ("VERIFY_RESULT".equals(event.type()) && event.message() != null && !event.message().isBlank()) {
                findings.add(event.message());
            }
            if ("ASK_USER".equals(event.type()) || "HUMAN_GATE".equals(event.type())) {
                suggestedActions.add("Lead 继续处理需要人工确认或补充信息的部分");
            }
        }
        if (findings.isEmpty() && !finalAnswer.isBlank()) {
            findings.add(finalAnswer.length() > 240 ? finalAnswer.substring(0, 240) + "...(truncated)" : finalAnswer);
        }
        if (suggestedActions.isEmpty()) {
            suggestedActions.add(success ? "Lead 根据子任务结论决定是否继续落地或汇总" : "Lead 重新审视拆解方式或直接接管任务");
        }
        double confidence = success ? 0.72d : 0.25d;
        return new SubAgentResult(
                envelope.taskId(),
                agentName,
                success,
                finalAnswer.isBlank() ? "子 Agent 未产出最终文本结论" : finalAnswer,
                List.copyOf(findings),
                List.copyOf(evidenceRefs),
                List.copyOf(suggestedActions),
                confidence,
                Map.of(
                        "taskId", envelope.taskId(),
                        "capability", envelope.capability().name(),
                        "eventCount", events.size()
                )
        );
    }

    private String buildQuestion(TaskContextEnvelope envelope) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是一个只读子 Agent，只完成当前子任务。\n");
        builder.append("Goal: ").append(envelope.goal()).append("\n");
        builder.append("Instruction: ").append(envelope.instruction()).append("\n");
        builder.append("Expected Output: ").append(envelope.expectedOutputSchema()).append("\n");
        if (envelope.constraints() != null && !envelope.constraints().isEmpty()) {
            builder.append("Constraints: ").append(envelope.constraints()).append("\n");
        }
        if (envelope.inputEvidence() != null && !envelope.inputEvidence().isEmpty()) {
            builder.append("Input Evidence:\n");
            envelope.inputEvidence().forEach(item -> builder
                    .append("- [")
                    .append(item.sourceType())
                    .append("] ")
                    .append(item.sourceId())
                    .append(": ")
                    .append(item.content())
                    .append("\n"));
        }
        builder.append("只返回当前子任务需要的结论、证据和建议下一步。");
        return builder.toString();
    }

    private AgentTaskType mapTaskType(SubAgentCapability capability) {
        return switch (capability) {
            case BUG_FIX -> AgentTaskType.BUG_FIX;
            case BUILD_ANALYSIS, TEST_ANALYSIS, GIT_ANALYSIS, CODE_EXPLAIN, REFACTOR -> AgentTaskType.CODE_EXPLAIN;
        };
    }

    public record PreparedSubAgentExecution(
            TaskContextEnvelope envelope,
            String agentName,
            String childSessionId,
            PreparedAgentLoopTask loopTask,
            SubAgentResult immediateResult
    ) {

        static PreparedSubAgentExecution ready(
                TaskContextEnvelope envelope,
                String agentName,
                String childSessionId,
                PreparedAgentLoopTask loopTask
        ) {
            return new PreparedSubAgentExecution(envelope, agentName, childSessionId, loopTask, null);
        }

        static PreparedSubAgentExecution completed(
                TaskContextEnvelope envelope,
                String agentName,
                String childSessionId,
                SubAgentResult immediateResult
        ) {
            return new PreparedSubAgentExecution(envelope, agentName, childSessionId, null, immediateResult);
        }

        boolean completedBeforeLoop() {
            return immediateResult != null || loopTask.completedBeforeLoop();
        }
    }
}
