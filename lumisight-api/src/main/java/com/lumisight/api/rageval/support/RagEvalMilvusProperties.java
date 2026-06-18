package com.lumisight.api.rageval.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.rag-eval.milvus")
public class RagEvalMilvusProperties {

    private boolean enabled = false;
    private String host = "127.0.0.1";
    private int port = 19530;
    private String databaseName = "default";
    private String collectionName = "rag_eval_codesearchnet_1024";
    private String username = "";
    private String password = "";
    private String token = "";
    private boolean secure = false;
    private boolean initializeSchema = true;
    private boolean clearBeforeRun = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

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

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public void setCollectionName(String collectionName) {
        this.collectionName = collectionName;
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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }

    public boolean isClearBeforeRun() {
        return clearBeforeRun;
    }

    public void setClearBeforeRun(boolean clearBeforeRun) {
        this.clearBeforeRun = clearBeforeRun;
    }
}
