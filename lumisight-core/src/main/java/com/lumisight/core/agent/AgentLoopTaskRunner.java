package com.lumisight.core.agent;

import com.lumisight.common.concurrent.NamedExecutors;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 统一的 loop 任务执行器。
 * 无论是 single-agent 还是 multi-agent wave，最后都通过它把 AgentLoopTask 扔进线程池。
 */
@Component
public class AgentLoopTaskRunner {

    public <R> R runBlocking(AgentLoopTask<R> task) {
        return runWave(List.of(task), 1).get(0);
    }

    public <R> List<R> runWave(List<AgentLoopTask<R>> tasks, int parallelism) {
        if (tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = NamedExecutors.newFixedPool("agent-loop-task", Math.max(1, parallelism));
        List<Future<R>> futures = new ArrayList<>(tasks.size());
        try {
            for (AgentLoopTask<R> task : tasks) {
                futures.add(executor.submit(task.handler()::execute));
            }
            List<R> results = new ArrayList<>(futures.size());
            for (Future<R> future : futures) {
                results.add(future.get());
            }
            return List.copyOf(results);
        } catch (InterruptedException e) {
            cancelPending(futures);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("agent loop tasks interrupted", e);
        } catch (ExecutionException e) {
            cancelPending(futures);
            throw new IllegalStateException("failed to execute agent loop tasks", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private <R> void cancelPending(List<Future<R>> futures) {
        for (Future<R> future : futures) {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }
}
