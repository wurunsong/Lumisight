package com.lumisight.api.agent.transport;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class AgentTransportRegistry {

    private final Map<String, AgentTransportAdapter> adapters;

    public AgentTransportRegistry(List<AgentTransportAdapter> adapters) {
        this.adapters = adapters.stream()
                .collect(Collectors.toUnmodifiableMap(
                        adapter -> adapter.protocol().toLowerCase(),
                        Function.identity(),
                        (a, b) -> a
                ));
    }

    public AgentTransportAdapter get(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return null;
        }
        return adapters.get(protocol.toLowerCase());
    }
}

