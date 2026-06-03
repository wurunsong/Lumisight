package com.lumisight.core.support;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Component
public class StreamingChatClientSupport {

    public String collect(ChatClient chatClient, String systemPrompt, String userPrompt) {
        return collect(chatClient, systemPrompt, userPrompt, null, null);
    }

    public String collect(
            ChatClient chatClient,
            String systemPrompt,
            String userPrompt,
            BooleanSupplier shouldContinue,
            Consumer<String> onChunk
    ) {
        StringBuilder buffer = new StringBuilder();
        chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content()
                .takeWhile(chunk -> shouldContinue == null || shouldContinue.getAsBoolean())
                .doOnNext(chunk -> {
                    buffer.append(chunk);
                    if (onChunk != null) {
                        onChunk.accept(chunk);
                    }
                })
                .blockLast();
        return buffer.toString();
    }
}
