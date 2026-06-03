package com.lumisight.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.common.util.ValueParsers;
import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.port.KnowledgeGraphOneHopProvider;
import com.lumisight.core.port.SourceCodeLookupProvider;
import com.lumisight.core.service.NoopKnowledgeGraphOneHopProvider;
import com.lumisight.core.service.SourceCodeLookupProviderImpl;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentContextEnrichmentService {

    private final KnowledgeGraphOneHopProvider knowledgeGraphOneHopProvider;
    private final SourceCodeLookupProvider sourceCodeLookupProvider;
    private final AgentPromptService agentPromptService;
    private final StreamingChatClientSupport streamingChatClientSupport;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentContextEnrichmentService(
            @Autowired(required = false) KnowledgeGraphOneHopProvider knowledgeGraphOneHopProvider,
            @Autowired(required = false) SourceCodeLookupProvider sourceCodeLookupProvider,
            AgentPromptService agentPromptService,
            StreamingChatClientSupport streamingChatClientSupport
    ) {
        this.knowledgeGraphOneHopProvider = knowledgeGraphOneHopProvider == null
                ? new NoopKnowledgeGraphOneHopProvider()
                : knowledgeGraphOneHopProvider;
        this.sourceCodeLookupProvider = sourceCodeLookupProvider == null
                ? new SourceCodeLookupProviderImpl()
                : sourceCodeLookupProvider;
        this.agentPromptService = agentPromptService;
        this.streamingChatClientSupport = streamingChatClientSupport;
    }

    public List<AgentContextItem> enrichAndFilter(
            ChatClient llmChatClient,
            List<AgentContextItem> vectorResults,
            int limit,
            Map<String, Object> decisionArgs,
            String toolName
    ) {
        List<AgentContextItem> enriched = enrichGraphAndSource(vectorResults, limit);
        String userQuestion = ValueParsers.asString(decisionArgs == null ? null : decisionArgs.get("naturalLanguageQuery"));
        if (!StringUtils.hasText(userQuestion)) {
            userQuestion = ValueParsers.asString(decisionArgs == null ? null : decisionArgs.get("codeQuery"));
        }
        return filterRelevantContexts(llmChatClient, userQuestion, enriched, limit, toolName);
    }

    private List<AgentContextItem> enrichGraphAndSource(List<AgentContextItem> vectorResults, int limit) {
        List<AgentContextItem> enriched = new ArrayList<>(vectorResults);
        for (AgentContextItem item : vectorResults) {
            Object kgNodeIdValue = item.metadata() == null ? null : item.metadata().get("kg_node_id");
            if (!(kgNodeIdValue instanceof String kgNodeId) || kgNodeId.isBlank()) {
                continue;
            }
            List<AgentContextItem> oneHop = knowledgeGraphOneHopProvider.retrieveByNodeId(
                    AgentToolRuntimeContext.required().repoRoot(),
                    kgNodeId,
                    limit
            );
            enriched.addAll(oneHop);
            List<Map<String, Object>> methodNodes = knowledgeGraphOneHopProvider.retrieveMethodNodeLocationsByNodeId(
                    AgentToolRuntimeContext.required().repoRoot(),
                    kgNodeId,
                    limit
            );
            for (Map<String, Object> methodNode : methodNodes) {
                String sourceFile = ValueParsers.asString(methodNode.get("sourceFile"));
                Integer startLine = ValueParsers.asInteger(methodNode.get("startLine"));
                Integer endLine = ValueParsers.asInteger(methodNode.get("endLine"));
                enriched.addAll(sourceCodeLookupProvider.lookupMethodSource(
                        AgentToolRuntimeContext.required().repoRoot(),
                        sourceFile,
                        startLine,
                        endLine
                ));
            }
        }
        return enriched;
    }

    private List<AgentContextItem> filterRelevantContexts(
            ChatClient llmChatClient,
            String userQuestion,
            List<AgentContextItem> contexts,
            int limit,
            String toolName
    ) {
        if (!StringUtils.hasText(userQuestion) || contexts.isEmpty()) {
            return contexts;
        }
        String prompt = agentPromptService.relevanceFilterPrompt(userQuestion, contexts, limit, toolName);
        String raw = streamingChatClientSupport.collect(
                llmChatClient,
                agentPromptService.relevanceFilterSystemPrompt(),
                prompt
        );
        try {
            List<Map<String, Object>> scoredItems = objectMapper.readValue(
                    extractJsonArray(raw),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class)
            );
            if (scoredItems == null || scoredItems.isEmpty()) {
                return contexts;
            }
            scoredItems.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));
            Set<Integer> selected = new LinkedHashSet<>();
            int maxKeep = Math.max(1, limit * 2);
            for (Map<String, Object> item : scoredItems) {
                Integer idx = ValueParsers.asInteger(item.get("index"));
                if (idx != null) {
                    selected.add(idx);
                }
                if (selected.size() >= maxKeep) {
                    break;
                }
            }
            List<AgentContextItem> filtered = new ArrayList<>();
            for (Integer index : selected) {
                if (index == null || index < 0 || index >= contexts.size()) {
                    continue;
                }
                filtered.add(contexts.get(index));
            }
            return filtered.isEmpty() ? contexts : filtered;
        } catch (Exception ignored) {
            return contexts;
        }
    }

    private String extractJsonArray(String raw) {
        if (raw == null) {
            return "[]";
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return "[]";
    }

    private double scoreOf(Map<String, Object> row) {
        Object score = row.get("score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        if (score == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(String.valueOf(score));
        } catch (Exception ignored) {
            return 0D;
        }
    }
}
