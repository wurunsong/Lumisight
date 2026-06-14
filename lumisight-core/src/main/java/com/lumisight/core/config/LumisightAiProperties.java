package com.lumisight.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lumisight.ai")
public class LumisightAiProperties {

    private String chatApiKey;
    private String embeddingApiKey;
    private String taskChatModel = "deepseek-v4-pro";
    private String schedulerChatModel = "deepseek-v4-pro";
    private int chatConnectTimeoutSeconds = 10;
    private int chatReadTimeoutSeconds = 120;

    public String getChatApiKey() {
        return chatApiKey;
    }

    public void setChatApiKey(String chatApiKey) {
        this.chatApiKey = chatApiKey;
    }

    public String getEmbeddingApiKey() {
        return embeddingApiKey;
    }

    public void setEmbeddingApiKey(String embeddingApiKey) {
        this.embeddingApiKey = embeddingApiKey;
    }

    public String getTaskChatModel() {
        return taskChatModel;
    }

    public void setTaskChatModel(String taskChatModel) {
        this.taskChatModel = taskChatModel;
    }

    public String getSchedulerChatModel() {
        return schedulerChatModel;
    }

    public void setSchedulerChatModel(String schedulerChatModel) {
        this.schedulerChatModel = schedulerChatModel;
    }

    public int getChatConnectTimeoutSeconds() {
        return chatConnectTimeoutSeconds;
    }

    public void setChatConnectTimeoutSeconds(int chatConnectTimeoutSeconds) {
        this.chatConnectTimeoutSeconds = chatConnectTimeoutSeconds;
    }

    public int getChatReadTimeoutSeconds() {
        return chatReadTimeoutSeconds;
    }

    public void setChatReadTimeoutSeconds(int chatReadTimeoutSeconds) {
        this.chatReadTimeoutSeconds = chatReadTimeoutSeconds;
    }
}
