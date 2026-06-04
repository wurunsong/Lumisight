package com.lumisight.core.context;

abstract class AbstractThreadLocalAgentContext<T> {

    private final ThreadLocal<T> holder = new ThreadLocal<>();

    protected Scope openValue(T value) {
        holder.set(value);
        return new Scope(holder);
    }

    protected T requiredValue(String errorMessage) {
        T context = holder.get();
        if (context == null) {
            throw new IllegalStateException(errorMessage);
        }
        return context;
    }

    protected T currentValue() {
        return holder.get();
    }

    protected void restoreValue(T context) {
        if (context == null) {
            holder.remove();
            return;
        }
        holder.set(context);
    }

    protected void clearValue() {
        holder.remove();
    }

    protected static final class Scope implements AutoCloseable {
        private final ThreadLocal<?> holder;

        private Scope(ThreadLocal<?> holder) {
            this.holder = holder;
        }

        @Override
        public void close() {
            holder.remove();
        }
    }
}
