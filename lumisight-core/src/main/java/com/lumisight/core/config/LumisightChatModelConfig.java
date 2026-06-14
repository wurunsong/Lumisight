package com.lumisight.core.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class LumisightChatModelConfig {

    public static final String TASK_CHAT_MODEL = "taskChatModel";
    public static final String SCHEDULER_CHAT_MODEL = "schedulerChatModel";
    public static final String TASK_CHAT_CLIENT_BUILDER = "taskChatClientBuilder";
    public static final String SCHEDULER_CHAT_CLIENT_BUILDER = "schedulerChatClientBuilder";

    @Bean(name = TASK_CHAT_MODEL)
    @Primary
    public ChatModel taskChatModel(
            @Qualifier("openAiChatModel") OpenAiChatModel baseOpenAiChatModel,
            LumisightAiProperties properties
    ) {
        return baseOpenAiChatModel.mutate()
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(properties.getTaskChatModel())
                        .build())
                .build();
    }

    @Bean(name = SCHEDULER_CHAT_MODEL)
    public ChatModel schedulerChatModel(
            @Qualifier("openAiChatModel") OpenAiChatModel baseOpenAiChatModel,
            LumisightAiProperties properties
    ) {
        return baseOpenAiChatModel.mutate()
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(properties.getSchedulerChatModel())
                        .build())
                .build();
    }

    @Bean(name = TASK_CHAT_CLIENT_BUILDER)
    @Primary
    public ChatClient.Builder taskChatClientBuilder(@Qualifier(TASK_CHAT_MODEL) ChatModel taskChatModel) {
        return ChatClient.builder(taskChatModel);
    }

    @Bean(name = SCHEDULER_CHAT_CLIENT_BUILDER)
    public ChatClient.Builder schedulerChatClientBuilder(@Qualifier(SCHEDULER_CHAT_MODEL) ChatModel schedulerChatModel) {
        return ChatClient.builder(schedulerChatModel);
    }
}
