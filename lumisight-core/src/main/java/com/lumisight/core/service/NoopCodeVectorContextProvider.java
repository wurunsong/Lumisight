package com.lumisight.core.service;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.port.CodeVectorContextProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnMissingBean(CodeVectorContextProvider.class)
public class NoopCodeVectorContextProvider implements CodeVectorContextProvider {

    @Override
    public List<AgentContextItem> retrieveByCode(String repoRoot, String codeQuery, Integer limit) {
        return List.of(new AgentContextItem(
                "code_vector",
                "placeholder",
                "Code vector context provider not wired yet.",
                Map.of("repoRoot", repoRoot)
        ));
    }
}
