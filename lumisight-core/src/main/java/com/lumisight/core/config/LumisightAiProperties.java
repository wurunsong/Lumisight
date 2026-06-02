package com.lumisight.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lumisight.ai")
public class LumisightAiProperties {

    private String chatApiKey;
    private String embeddingApiKey;
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
