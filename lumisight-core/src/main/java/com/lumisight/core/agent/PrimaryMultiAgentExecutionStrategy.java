package com.lumisight.core.agent;

import com.lumisight.core.agent.multiagent.service.MultiAgentOrchestrationService;
import com.lumisight.core.context.AgentExecutionState;
import com.lumisight.core.context.ambient.MultiAgentExecutionScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.support.AgentSessionContextStore;
import com.lumisight.core.support.context.AgentContextAppendOptions;
import com.lumisight.core.support.context.AgentContextManager;
import com.lumisight.core.support.context.AgentContextSession;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * primary 请求专用的多 agent 编排策略。
 * 只负责调度/汇总多 agent 结果，本身不再承担后续单 agent 执行逻辑。
 */
@Component
class PrimaryMultiAgentExecutionStrategy implements AgentMultiAgentExecutionStrategy {

    private final MultiAgentOrchestrationService multiAgentOrchestrationService;
    private final AgentContextManager agentContextManager;
    private final AgentSessionContextStore conversationManager;

    PrimaryMultiAgentExecutionStrategy(
            MultiAgentOrchestrationService multiAgentOrchestrationService,
            AgentContextManager agentContextManager,
            AgentSessionContextStore conversationManager
    ) {
        this.multiAgentOrchestrationService = multiAgentOrchestrationService;
        this.agentContextManager = agentContextManager;
        this.conversationManager = conversationManager;
    }

    @Override
    public MultiAgentPlanOutcome planIfNeeded(
            AgentRequestContext requestContext,
            AgentExecutionState executionState,
            AgentEventPublisher publisher
    ) {
        AgentRequest effectiveRequest = requestContext.effectiveRequest();
        // 如果运行模式不是multi agent，直接返回skipped
        if (!requestContext.profile().allowMultiAgentOrchestration()
                || effectiveRequest.runMode() != AgentRunMode.MULTI_AGENT) {
            return MultiAgentPlanOutcome.skipped(executionState);
        }
        // 编排阶段始终基于 executionState 里的 repoRoot / effectiveQuestion 重新组装请求，
        // 避免 resume、repoRoot 归一化后的信息在 plan 阶段丢失。
        AgentRequest orchestrationRequest = new AgentRequest(
                effectiveRequest.taskType(),
                executionState.resolvedRepoRoot(),
                executionState.effectiveQuestion(),
                effectiveRequest.skillPath(),
                effectiveRequest.userId(),
                executionState.sessionId(),
                effectiveRequest.approveRiskyToolCall(),
                effectiveRequest.interrupt(),
                effectiveRequest.resume(),
                effectiveRequest.includeRagContext(),
                effectiveRequest.includeKnowledgeGraphContext(),
                effectiveRequest.contextLimit(),
                effectiveRequest.runMode(),
                effectiveRequest.dialogueMode()
        );
        // 规划多agent的执行计划
        MultiAgentOrchestrationService.PlannedOrchestration plannedOrchestration = multiAgentOrchestrationService.plan(
                orchestrationRequest,
                Map.of("shouldStop", (BooleanSupplier) () -> !conversationManager.isActiveEpoch(executionState.sessionId(), executionState.runEpoch()))
        );
        Map<String, Object> orchestrationPayload = new LinkedHashMap<>();
        orchestrationPayload.put("planId", plannedOrchestration.plan().planId());
        orchestrationPayload.put("orchestrationMode", plannedOrchestration.plan().orchestrationMode().name());
        orchestrationPayload.put("taskCount", plannedOrchestration.plan().tasks() == null ? 0 : plannedOrchestration.plan().tasks().size());
        orchestrationPayload.put("executionMode", "SUB_AGENT");
        orchestrationPayload.put("planNarrative", plannedOrchestration.plan().metadata().getOrDefault("planNarrative", ""));
        orchestrationPayload.put("boundaryNotes", plannedOrchestration.plan().metadata().getOrDefault("boundaryNotes", List.of()));
        orchestrationPayload.put("taskBriefs", plannedOrchestration.plan().metadata().getOrDefault("taskBriefs", List.of()));
        orchestrationPayload.put("waves", plannedOrchestration.waves());
        publisher.emit(AgentEvent.orchestrationPlan(
                requestContext.traceId(),
                executionState.sessionId(),
                0,
                orchestrationPayload
        ));
        return new MultiAgentPlanOutcome(executionState, plannedOrchestration);
    }

    @Override
    public MultiAgentOrchestrationOutcome executePlanIfNeeded(
            MultiAgentPlanOutcome planOutcome,
            AgentRequestContext requestContext,
            AgentEventPublisher publisher
    ) {
        if (planOutcome == null || !planOutcome.planned()) {
            AgentExecutionState executionState = planOutcome == null ? null : planOutcome.executionState();
            return MultiAgentOrchestrationOutcome.noop(executionState);
        }
        AgentExecutionState executionState = planOutcome.executionState();
        MultiAgentOrchestrationService.OrchestrationResult coordinationResult = multiAgentOrchestrationService.execute(planOutcome.plannedOrchestration());
        AgentContextSession nextSession = agentContextManager.append(
                executionState.sessionId(),
                executionState.contextSession(),
                new AgentContextItem(
                        "multi_agent",
                        "orchestration_plan_" + coordinationResult.plan().planId(),
                        coordinationResult.summary(),
                        Map.of(
                                "planId", coordinationResult.plan().planId(),
                                "coordinationId", coordinationResult.coordinationId(),
                                "orchestrationMode", coordinationResult.plan().orchestrationMode().name(),
                                "waves", coordinationResult.waves(),
                                "executionMode", coordinationResult.executionMode(),
                                "taskStates", coordinationResult.executionState().taskStates(),
                                "currentWave", coordinationResult.executionState().currentWave(),
                                "schedulerState", coordinationResult.executionState().schedulerState()
                        )
                ),
                AgentContextAppendOptions.system()
        );
        coordinationResult.executionState().taskStates().forEach((taskId, status) -> publisher.emit(
                AgentEvent.multiAgentTaskStatus(
                        requestContext.traceId(),
                        executionState.sessionId(),
                        0,
                        taskId,
                        status.name(),
                        Map.of("executionMode", coordinationResult.executionMode())
                )
        ));
        coordinationResult.lifecycleEvents().forEach(event -> {
            String[] parts = event.split(":", 3);
            String agentId = parts.length > 0 ? parts[0] : "";
            String action = parts.length > 1 ? parts[1] : event;
            String taskId = parts.length > 2 ? parts[2] : "";
            publisher.emit(AgentEvent.subAgentLifecycle(
                    requestContext.traceId(),
                    executionState.sessionId(),
                    0,
                    agentId,
                    action,
                    taskId.isBlank() ? Map.of("raw", event) : Map.of("raw", event, "taskId", taskId)
            ));
        });
        for (var result : coordinationResult.results()) {
            publisher.emit(AgentEvent.subagentResult(
                    requestContext.traceId(),
                    executionState.sessionId(),
                    0,
                    result.taskId(),
                    result.success(),
                    result.summary(),
                    Map.of(
                            "agentName", result.agentName(),
                            "confidence", result.confidence(),
                            "suggestedActions", result.suggestedActions()
                    )
            ));
            nextSession = agentContextManager.append(
                    executionState.sessionId(),
                    nextSession,
                    new AgentContextItem(
                            "subagent",
                            result.taskId(),
                            result.summary(),
                            Map.of(
                                    "agentName", result.agentName(),
                                    "success", result.success(),
                                    "findings", result.findings(),
                                    "evidenceRefs", result.evidenceRefs(),
                                    "suggestedActions", result.suggestedActions(),
                                    "confidence", result.confidence(),
                                    "payload", result.payload()
                            )
                    ),
                    AgentContextAppendOptions.system()
            );
        }
        if (coordinationResult.results().isEmpty()) {
            publisher.emit(AgentEvent.multiAgentFallback(requestContext.traceId(), executionState.sessionId(), 0, "未获得可用的子任务结果，回退主 Agent 直跑"));
        }
        // 多-agent 本轮产出的结果先写回主会话上下文，随后由 lead 继续单-agent 收敛、写盘和最终答复。
        return new MultiAgentOrchestrationOutcome(
                executionState.withContextSession(nextSession),
                new MultiAgentExecutionScope.Context(
                        MultiAgentExecutionScope.Role.LEAD_AGENT,
                        coordinationResult.context().orchestrationId(),
                        executionState.sessionId(),
                        coordinationResult.plan().planId(),
                        0,
                        coordinationResult.plan().orchestrationMode(),
                        MultiAgentExecutionScope.Phase.CONVERGENCE,
                        false,
                        0,
                        0L,
                        coordinationResult.coordinationId(),
                        "lead"
                )
        );
    }
}
