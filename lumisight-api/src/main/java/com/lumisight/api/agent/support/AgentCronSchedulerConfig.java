package com.lumisight.api.agent.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class AgentCronSchedulerConfig {

    @Bean(name = "agentCronTaskScheduler")
    public TaskScheduler agentCronTaskScheduler(AgentCronProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, properties.getSchedulerPoolSize()));
        scheduler.setThreadNamePrefix("agent-cron-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
