package com.lumisight.common.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
        CARRIERS.removeIf(carrier -> carrier.name().equals(name));
        CARRIERS.add(new ContextCarrier() {
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
        });
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

    private interface ContextCarrier {
        String name();

        Object captureValue();

        void restoreValue(Object value);

        void clearValue();
    }
}
