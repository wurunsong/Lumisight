package com.lumisight.common.concurrent;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

final class ContextAwareExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;

    ContextAwareExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(ThreadContextRegistry.wrap(command));
    }

    @Override
    public <T> Future<T> submit(java.util.concurrent.Callable<T> task) {
        return delegate.submit(ThreadContextRegistry.wrap(task));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(ThreadContextRegistry.wrap(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(ThreadContextRegistry.wrap(task), result);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends java.util.concurrent.Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(tasks.stream().map(ThreadContextRegistry::wrap).toList());
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(tasks.stream().map(ThreadContextRegistry::wrap).toList(), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends java.util.concurrent.Callable<T>> tasks) throws InterruptedException, java.util.concurrent.ExecutionException {
        return delegate.invokeAny(tasks.stream().map(ThreadContextRegistry::wrap).toList());
    }

    @Override
    public <T> T invokeAny(Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        return delegate.invokeAny(tasks.stream().map(ThreadContextRegistry::wrap).toList(), timeout, unit);
    }
}
