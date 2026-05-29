package com.lumisight.hooks;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

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
