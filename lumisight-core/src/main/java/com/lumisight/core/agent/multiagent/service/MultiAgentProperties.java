package com.lumisight.core.agent.multiagent.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.agent.multi-agent")
public class MultiAgentProperties {

    private boolean enabled = true;
    private boolean autoUpgradeEnabled = true;
    private double autoUpgradeThreshold = 0.9d;
    private boolean allowSubagent = true;
    private int maxSubagentDepth = 1;
    private int maxTasksPerPlan = 8;
    private int maxParallelAgents = 3;
    private int subagentMaxRounds = 6;
    private int childContextLimit = 6;
    private int childTimeoutMs = 120000;
    // 例如: <repoRoot>/.lumisight/multi-agent/team-session-123/execution-state.json
    private String coordinationRootDir = ".lumisight/multi-agent";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoUpgradeEnabled() {
        return autoUpgradeEnabled;
    }

    public void setAutoUpgradeEnabled(boolean autoUpgradeEnabled) {
        this.autoUpgradeEnabled = autoUpgradeEnabled;
    }

    public double getAutoUpgradeThreshold() {
        return autoUpgradeThreshold;
    }

    public void setAutoUpgradeThreshold(double autoUpgradeThreshold) {
        this.autoUpgradeThreshold = autoUpgradeThreshold;
    }

    public boolean isAllowSubagent() {
        return allowSubagent;
    }

    public void setAllowSubagent(boolean allowSubagent) {
        this.allowSubagent = allowSubagent;
    }

    public int getMaxSubagentDepth() {
        return maxSubagentDepth;
    }

    public void setMaxSubagentDepth(int maxSubagentDepth) {
        this.maxSubagentDepth = maxSubagentDepth;
    }

    public int getMaxTasksPerPlan() {
        return maxTasksPerPlan;
    }

    public void setMaxTasksPerPlan(int maxTasksPerPlan) {
        this.maxTasksPerPlan = maxTasksPerPlan;
    }

    public int getMaxParallelAgents() {
        return maxParallelAgents;
    }

    public void setMaxParallelAgents(int maxParallelAgents) {
        this.maxParallelAgents = maxParallelAgents;
    }

    public int getSubagentMaxRounds() {
        return subagentMaxRounds;
    }

    public void setSubagentMaxRounds(int subagentMaxRounds) {
        this.subagentMaxRounds = subagentMaxRounds;
    }

    public int getChildContextLimit() {
        return childContextLimit;
    }

    public void setChildContextLimit(int childContextLimit) {
        this.childContextLimit = childContextLimit;
    }

    public int getChildTimeoutMs() {
        return childTimeoutMs;
    }

    public void setChildTimeoutMs(int childTimeoutMs) {
        this.childTimeoutMs = childTimeoutMs;
    }

    public String getCoordinationRootDir() {
        return coordinationRootDir;
    }

    public void setCoordinationRootDir(String coordinationRootDir) {
        this.coordinationRootDir = coordinationRootDir;
    }
}
