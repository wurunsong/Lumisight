package com.lumisight.tools.kg.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "lumisight.kg", name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = "lumisight.nebula")
public class NebulaProperties {

    private String host = "127.0.0.1";
    private int port = 9669;
    private String username = "root";
    private String password = "nebula";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
