package com.lumisight.core.agent.agent;

import com.lumisight.core.agent.model.AgentRequest;
import com.lumisight.core.agent.support.AgentPromptService;
import com.lumisight.core.agent.support.AgentRequestValidators;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
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
        AgentRequestValidators.validate(request);

        int limit = request.contextLimit() == null ? DEFAULT_CONTEXT_LIMIT : request.contextLimit();
        String finalPrompt = agentPromptService.buildFinalAnswerPrompt(request, java.util.List.of(), limit);

        return llmChatClient.prompt()
                .system(agentPromptService.systemPrompt(request.taskType()))
                .user(finalPrompt)
                .stream()
                .content();
    }

}
