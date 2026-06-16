package com.lumisight.core.support;

import com.lumisight.common.concurrent.NamedExecutors;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.memory.dto.RelevantMemoryBundle;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Agent 侧记忆沉淀的异步入口。
 * 对话主流程只负责投递记忆信号，真正的模型判断和落盘在专用单线程队列里串行执行，
 * 避免记忆反思阻塞用户对话，也避免多个沉淀任务同时改同一份 MEMORY.md。
 */
@Component
public class AgentMemoryAsyncService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryAsyncService.class);

    private final ExecutorService memoryConsolidationExecutor = NamedExecutors.newFixedPool("agent-memory-consolidation", 1);
    private final AgentMemoryConsolidationService agentMemoryConsolidationService;

    public AgentMemoryAsyncService(AgentMemoryConsolidationService agentMemoryConsolidationService) {
        this.agentMemoryConsolidationService = agentMemoryConsolidationService;
    }

    public void enqueueFinalAnswer(
            AgentRequest request,
            String effectiveQuestion,
            String finalAnswer,
            RelevantMemoryBundle memoryContext
    ) {
        enqueueSignal("final_answer", request, effectiveQuestion, finalAnswer, memoryContext);
    }

    public void enqueueSignal(
            String trigger,
            AgentRequest request,
            String effectiveQuestion,
            String observedContent,
            RelevantMemoryBundle memoryContext
    ) {
        enqueue(trigger, () -> agentMemoryConsolidationService.consolidateSignal(
                trigger,
                request,
                effectiveQuestion,
                observedContent,
                memoryContext
        ));
    }

    private void enqueue(String trigger, Runnable task) {
        try {
            memoryConsolidationExecutor.submit(task);
        } catch (RejectedExecutionException e) {
            log.debug("agent_memory_consolidation_enqueue_rejected, trigger={}, error={}", trigger, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        memoryConsolidationExecutor.shutdown();
    }
}
