package com.lumisight.hooks;

public interface AgentHook {

    default int order() {
        return 0;
    }

    default boolean supports(AgentHookPoint point) {
        return true;
    }

    void onHook(AgentHookPoint point, AgentHookContext context);
}
