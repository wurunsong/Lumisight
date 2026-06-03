package com.lumisight.common.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class NamedExecutors {

    private NamedExecutors() {
    }

    public static ExecutorService newFixedPool(String prefix, int size) {
        return new ContextAwareExecutorService(Executors.newFixedThreadPool(Math.max(1, size), daemonFactory(prefix)));
    }

    public static ExecutorService newCachedPool(String prefix) {
        return new ContextAwareExecutorService(Executors.newCachedThreadPool(daemonFactory(prefix)));
    }

    public static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(0);
        String threadPrefix = (prefix == null || prefix.isBlank()) ? "lumisight-worker" : prefix.trim();
        return runnable -> {
            Thread thread = new Thread(runnable, threadPrefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
