package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.CodeAssistantAgentService;
import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.TaskContextEnvelope;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.AgentTaskType;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.support.AgentSessionContextStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class SubAgentExecutionService {

    private final ObjectProvider<CodeAssistantAgentService> codeAssistantAgentServiceProvider;
    private final AgentSessionContextStore sessionContextStore;
    private final MultiAgentProperties properties;

    public SubAgentExecutionService(
            ObjectProvider<CodeAssistantAgentService> codeAssistantAgentServiceProvider,
            AgentSessionContextStore sessionContextStore,
            MultiAgentProperties properties
    ) {
        this.codeAssistantAgentServiceProvider = codeAssistantAgentServiceProvider;
        this.sessionContextStore = sessionContextStore;
        this.properties = properties;
    }

    public SubAgentResult execute(TaskContextEnvelope envelope, String parentSessionId) {
        String childSessionId = "subagent-" + envelope.taskId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        String orchestrationId = "orch-" + UUID.randomUUID();
        MultiAgentExecutionContext.Context parent = MultiAgentExecutionContext.current();
        int depth = parent == null ? 1 : parent.depth() + 1;
        if (depth > properties.getMaxSubagentDepth()) {
            return new SubAgentResult(
                    envelope.taskId(),
                    "subagent",
                    false,
                    "子 Agent 深度超限，已拒绝递归执行",
                    List.of("max depth exceeded"),
                    List.of(),
                    List.of("由 Lead 继续执行该任务"),
                    0.0d,
                    Map.of("depth", depth, "maxDepth", properties.getMaxSubagentDepth())
            );
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
        try (MultiAgentExecutionContext.Scope ignored = MultiAgentExecutionContext.open(
                new MultiAgentExecutionContext.Context(
                        MultiAgentExecutionContext.Role.SUB_AGENT,
                        orchestrationId,
                        parentSessionId,
                        envelope.taskId(),
                        depth,
                        false
                )
        )) {
            List<AgentEvent> events = codeAssistantAgentServiceProvider.getObject()
                    .run(childRequest)
                    .collectList()
                    .block();
            return summarize(envelope, events == null ? List.of() : events);
        } finally {
            sessionContextStore.clear(childSessionId);
        }
    }

    private SubAgentResult summarize(TaskContextEnvelope envelope, List<AgentEvent> events) {
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
                "subagent",
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
}
