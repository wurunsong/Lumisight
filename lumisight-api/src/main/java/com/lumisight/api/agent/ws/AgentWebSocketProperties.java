package com.lumisight.api.agent.ws;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.agent.websocket")
public class AgentWebSocketProperties {

    private int maxConnections = 200;
    private int maxTextMessageSize = 65536;
    private long sendTimeLimitMs = 10000;
    private long sendBufferSizeLimitBytes = 512000;
    private long messageRateLimitPerMinute = 120;
    private String allowedOrigins = "*";

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getMaxTextMessageSize() {
        return maxTextMessageSize;
    }

    public void setMaxTextMessageSize(int maxTextMessageSize) {
        this.maxTextMessageSize = maxTextMessageSize;
    }

    public long getSendTimeLimitMs() {
        return sendTimeLimitMs;
    }

    public void setSendTimeLimitMs(long sendTimeLimitMs) {
        this.sendTimeLimitMs = sendTimeLimitMs;
    }

    public long getSendBufferSizeLimitBytes() {
        return sendBufferSizeLimitBytes;
    }

    public void setSendBufferSizeLimitBytes(long sendBufferSizeLimitBytes) {
        this.sendBufferSizeLimitBytes = sendBufferSizeLimitBytes;
    }

    public long getMessageRateLimitPerMinute() {
        return messageRateLimitPerMinute;
    }

    public void setMessageRateLimitPerMinute(long messageRateLimitPerMinute) {
        this.messageRateLimitPerMinute = messageRateLimitPerMinute;
    }

    public String getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}

