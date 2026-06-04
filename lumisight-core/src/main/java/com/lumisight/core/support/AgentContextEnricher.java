package com.lumisight.core.support;

import com.lumisight.core.model.AgentContextItem;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

public interface AgentContextEnricher {

    List<AgentContextItem> enrichAndFilter(
            ChatClient llmChatClient,
            List<AgentContextItem> seedContexts,
            int limit,
            Map<String, Object> decisionArgs,
            String toolName
    );
}
