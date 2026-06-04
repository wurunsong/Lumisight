package com.lumisight.api.agent.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.agent.cron")
public class AgentCronProperties {

    private String timezone = "Asia/Shanghai";
    private int schedulerPoolSize = 2;
    private int maxRecentRuns = 20;

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public int getSchedulerPoolSize() {
        return schedulerPoolSize;
    }

    public void setSchedulerPoolSize(int schedulerPoolSize) {
        this.schedulerPoolSize = schedulerPoolSize;
    }

    public int getMaxRecentRuns() {
        return maxRecentRuns;
    }

    public void setMaxRecentRuns(int maxRecentRuns) {
        this.maxRecentRuns = maxRecentRuns;
    }
}
