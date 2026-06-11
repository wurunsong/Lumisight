package com.lumisight.hooks.service;

import com.lumisight.hooks.dto.AgentHookContext;
import com.lumisight.hooks.enums.AgentHookPoint;

public interface AgentHook {

    default int order() {
        return 0;
    }

    default boolean supports(AgentHookPoint point) {
        return true;
    }

    void onHook(AgentHookPoint point, AgentHookContext context);
}
