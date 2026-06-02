package com.lumisight.core.hooks.runtime;

import com.lumisight.common.concurrent.NamedExecutors;
import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.runtime.AgentToolExecutionService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

@Component
public class HookedToolExecutor {

    private static final int MAX_PARALLEL_TOOLS = 4;

    private final AgentToolExecutionService agentToolExecutionService;
    private final ExecutorService parallelToolExecutor = NamedExecutors.newFixedPool("tool-parallel", MAX_PARALLEL_TOOLS);

    public HookedToolExecutor(AgentToolExecutionService agentToolExecutionService) {
        this.agentToolExecutionService = agentToolExecutionService;
    }

    public AgentToolExecutionResult execute(
            ToolDecision decision,
            Set<AgentToolPermission> enabledPermissions,
            int limit,
            String sessionId,
            int round,
            String question
    ) {
        return agentToolExecutionService.execute(
                decision,
                enabledPermissions,
                limit,
                new ToolHookContext(sessionId, round, question)
        );
    }

    public List<List<ToolDecision>> partitionToolCalls(
            List<ToolDecision> toolCalls,
            Set<AgentToolPermission> enabledPermissions
    ) {
        List<List<ToolDecision>> batches = new ArrayList<>();
        List<ToolDecision> currentConcurrentBatch = new ArrayList<>();
        for (ToolDecision toolCall : toolCalls) {
            boolean safe = agentToolExecutionService.isConcurrencySafe(toolCall, enabledPermissions);
            if (safe) {
                currentConcurrentBatch.add(toolCall);
                continue;
            }
            if (!currentConcurrentBatch.isEmpty()) {
                batches.add(List.copyOf(currentConcurrentBatch));
                currentConcurrentBatch.clear();
            }
            batches.add(List.of(toolCall));
        }
        if (!currentConcurrentBatch.isEmpty()) {
            batches.add(List.copyOf(currentConcurrentBatch));
        }
        return batches;
    }

    public List<AgentToolExecutionResult> executeBatch(
            List<ToolDecision> decisions,
            Set<AgentToolPermission> enabledPermissions,
            int limit,
            String sessionId,
            int round,
            String question
    ) {
        if (decisions.size() <= 1) {
            return List.of(execute(decisions.get(0), enabledPermissions, limit, sessionId, round, question));
        }
        List<CompletableFuture<AgentToolExecutionResult>> futures = decisions.stream()
                .map(decision -> CompletableFuture.supplyAsync(
                        () -> execute(decision, enabledPermissions, limit, sessionId, round, question),
                        parallelToolExecutor
                ))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }
}
