package com.lumisight.hooks.dispatcher;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

import com.lumisight.hooks.service.AgentHook;
import com.lumisight.hooks.dto.AgentHookContext;
import com.lumisight.hooks.enums.AgentHookPoint;

@Component
public class AgentHookDispatcher {

    private final List<AgentHook> hooks;

    public AgentHookDispatcher(List<AgentHook> hooks) {
        this.hooks = hooks.stream()
                .sorted(Comparator.comparingInt(AgentHook::order))
                .toList();
    }

    public void fire(AgentHookPoint point, AgentHookContext context) {
        for (AgentHook hook : hooks) {
            if (hook.supports(point)) {
                hook.onHook(point, context);
            }
        }
    }
}
