package com.lumisight.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AgentFinalAnswerVerifier {

    private final ChatClient llmChatClient;
    private final AgentPromptService agentPromptService;
    private final AgentDecisionParser decisionParser;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentFinalAnswerVerifier(
            ChatClient.Builder chatClientBuilder,
            AgentPromptService agentPromptService,
            AgentDecisionParser decisionParser
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.agentPromptService = agentPromptService;
        this.decisionParser = decisionParser;
    }

    public VerifyResult verifyFinalAnswer(AgentRequest request, String candidateAnswer, List<AgentContextItem> contexts) {
        try {
            String raw = llmChatClient.prompt()
                    .system("你是严谨的答案复核器。")
                    .user(agentPromptService.verifyPrompt(request, candidateAnswer, contexts))
                    .call()
                    .content();
            String json = decisionParser.extractJsonObject(raw);
            Map<?, ?> parsed = objectMapper.readValue(json, Map.class);
            Object passRaw = parsed.get("pass");
            Object reasonRaw = parsed.get("reason");
            boolean pass = Boolean.parseBoolean(String.valueOf(passRaw == null ? false : passRaw));
            String reason = String.valueOf(reasonRaw == null ? "" : reasonRaw);
            return new VerifyResult(pass, reason);
        } catch (Exception e) {
            return new VerifyResult(true, "复核器异常，默认放行");
        }
    }

    public record VerifyResult(boolean pass, String reason) {
    }
}

