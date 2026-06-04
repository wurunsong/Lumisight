package com.lumisight.common.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 线程上下文注册表。
 * <p>
 * 这里维护的是一组“上下文载体”（例如运行时上下文、调用上下文、SLF4J MDC），
 * 每个载体都知道三件事：
 * <ul>
 *   <li>如何从当前线程抓取值；</li>
 *   <li>如何把抓到的值恢复到另一个线程；</li>
 *   <li>当快照里没有值时如何清空上下文。</li>
 * </ul>
 * <p>
 * 典型流程是：
 * <ol>
 *   <li>任务提交到线程池之前，先从当前线程抓取一份上下文快照；</li>
 *   <li>任务在工作线程执行时，把这份快照临时安装进去；</li>
 *   <li>任务结束后，再把工作线程原来的上下文恢复回去。</li>
 * </ol>
 */
public final class ThreadContextRegistry {

    private static final List<ContextCarrier> CARRIERS = new CopyOnWriteArrayList<>();

    private ThreadContextRegistry() {
    }

    public static <T> void register(
            String name,
            Supplier<T> capture,
            Consumer<T> restore,
            Runnable clear
    ) {
        register(ContextCarrier.of(name, capture, restore, clear));
    }

    public static void register(ContextCarrier carrier) {
        CARRIERS.removeIf(existing -> existing.name().equals(carrier.name()));
        CARRIERS.add(carrier);
    }

    public static Runnable wrap(Runnable runnable) {
        ThreadContextSnapshot snapshot = capture();
        return () -> {
            try (ThreadContextScope ignored = install(snapshot)) {
                runnable.run();
            }
        };
    }

    public static <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> callable) {
        ThreadContextSnapshot snapshot = capture();
        return () -> {
            try (ThreadContextScope ignored = install(snapshot)) {
                return callable.call();
            }
        };
    }

    private static ThreadContextSnapshot capture() {
        List<Object> values = CARRIERS.stream()
                .map(ContextCarrier::captureValue)
                .toList();
        return new ThreadContextSnapshot(values);
    }

    private static ThreadContextScope install(ThreadContextSnapshot snapshot) {
        List<Object> previous = new ArrayList<>(CARRIERS.size());
        List<Object> values = snapshot == null ? List.of() : snapshot.values();
        for (int i = 0; i < CARRIERS.size(); i++) {
            ContextCarrier carrier = carrierAt(i);
            previous.add(carrier.captureValue());
            Object value = i < values.size() ? values.get(i) : null;
            if (value == null) {
                carrier.clearValue();
            } else {
                carrier.restoreValue(value);
            }
        }
        return new ThreadContextScope(previous);
    }

    private static ContextCarrier carrierAt(int index) {
        return CARRIERS.get(index);
    }

    private record ThreadContextSnapshot(List<Object> values) {
    }

    private static final class ThreadContextScope implements AutoCloseable {
        private final List<Object> previousValues;

        private ThreadContextScope(List<Object> previousValues) {
            this.previousValues = previousValues;
        }

        @Override
        public void close() {
            for (int i = 0; i < CARRIERS.size(); i++) {
                ContextCarrier carrier = carrierAt(i);
                Object previous = i < previousValues.size() ? previousValues.get(i) : null;
                if (previous == null) {
                    carrier.clearValue();
                } else {
                    carrier.restoreValue(previous);
                }
            }
        }
    }

    public interface ContextCarrier {
        String name();

        Object captureValue();

        void restoreValue(Object value);

        void clearValue();

        static <T> ContextCarrier of(
                String name,
                Supplier<T> capture,
                Consumer<T> restore,
                Runnable clear
        ) {
            return new ContextCarrier() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public Object captureValue() {
                    return capture.get();
                }

                @SuppressWarnings("unchecked")
                @Override
                public void restoreValue(Object value) {
                    restore.accept((T) value);
                }

                @Override
                public void clearValue() {
                    clear.run();
                }
            };
        }
    }
}
