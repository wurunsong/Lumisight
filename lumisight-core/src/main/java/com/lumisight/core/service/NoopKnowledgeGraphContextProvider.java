package com.lumisight.core.service;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.port.KnowledgeGraphContextProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnMissingBean(KnowledgeGraphContextProvider.class)
public class NoopKnowledgeGraphContextProvider implements KnowledgeGraphContextProvider {

    @Override
    public List<AgentContextItem> retrieve(String repoRoot, String question, Integer limit) {
        return List.of(new AgentContextItem(
                "knowledge_graph",
                "placeholder",
                "Knowledge graph context provider not wired yet.",
                Map.of("repoRoot", repoRoot)
        ));
    }
}
