package com.lumisight.core.agent.agent;

import com.lumisight.core.agent.model.AgentRequest;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

@Service
public class CodeAssistantStreamingAgentService {

    private static final int DEFAULT_CONTEXT_LIMIT = 5;

    private final ChatClient llmChatClient;
    private final AgentPromptService agentPromptService;

    public CodeAssistantStreamingAgentService(
            ChatClient.Builder chatClientBuilder,
            AgentPromptService agentPromptService
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.agentPromptService = agentPromptService;
    }

    public Flux<String> stream(AgentRequest request) {
        validateRequest(request);

        int limit = request.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : request.contextLimit();
        String finalPrompt = agentPromptService.buildFinalAnswerPrompt(request, java.util.List.of(), limit);

        return llmChatClient.prompt()
                .system(agentPromptService.systemPrompt(request.taskType()))
                .user(finalPrompt)
                .stream()
                .content();
    }

    private void validateRequest(AgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (!StringUtils.hasText(request.repoRoot())) {
            throw new IllegalArgumentException("repoRoot must not be blank");
        }
        if (!StringUtils.hasText(request.question())) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (request.taskType() == null) {
            throw new IllegalArgumentException("taskType must not be null");
        }
    }
}
