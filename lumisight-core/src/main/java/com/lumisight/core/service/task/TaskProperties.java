package com.lumisight.core.service.task;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.task")
public class TaskProperties {

    private String rootDir = ".tasks";
    private int maxListLimit = 200;

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public int getMaxListLimit() {
        return maxListLimit;
    }

    public void setMaxListLimit(int maxListLimit) {
        this.maxListLimit = maxListLimit;
    }
}
