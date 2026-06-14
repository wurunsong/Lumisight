package com.lumisight.core.context.ambient;

import com.lumisight.common.concurrent.ThreadContextRegistry;

/**
 * 非 agent 主执行状态的附加运行作用域。
 * 这类作用域通常绑定在线程上，用于在工具执行、多 agent 子流程等链路中透传运行期附加信息。
 */
public interface AmbientScope<T> {

    String carrierName();

    T current();

    void restore(T value);

    void clear();

    default ThreadContextRegistry.ContextCarrier threadContextCarrier() {
        return ThreadContextRegistry.ContextCarrier.of(
                carrierName(),
                this::current,
                this::restore,
                this::clear
        );
    }
}
