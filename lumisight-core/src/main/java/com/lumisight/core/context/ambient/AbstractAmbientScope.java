package com.lumisight.core.context.ambient;

abstract class AbstractAmbientScope<T> extends AbstractThreadLocalAgentContext<T> implements AmbientScope<T> {

    private final String carrierName;

    protected AbstractAmbientScope(String carrierName) {
        this.carrierName = carrierName;
    }

    @Override
    public final String carrierName() {
        return carrierName;
    }

    @Override
    public final T current() {
        return currentValue();
    }

    @Override
    public final void restore(T value) {
        restoreValue(value);
    }

    @Override
    public final void clear() {
        clearValue();
    }

    protected final Scope open(T value) {
        return openValue(value);
    }

    protected final T required(String errorMessage) {
        return requiredValue(errorMessage);
    }
}
