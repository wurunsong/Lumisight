package com.lumisight.core.support.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.agent.context")
public class AgentContextManagementProperties {

    private String artifactDir = ".lumisight/context-artifacts";
    private int singleArtifactBytes = 50 * 1024;
    private int toolMessageBytes = 200 * 1024;
    private int previewBytes = 2 * 1024;
    private int snipTriggerTokens = 24_000;
    private int snipTargetTokens = 18_000;
    private int projectionSoftTokens = 28_000;
    private int projectionHardTokens = 34_000;
    private int effectiveContextWindow = 180_000;
    private int responseReserveTokens = 20_000;
    private int autoCompactBufferTokens = 13_000;
    private int microCompactKeepRecent = 8;
    private int snipKeepRecentConversation = 12;
    private int postCompactMaxRestoreEntries = 5;
    private int postCompactTokenBudget = 50_000;
    private int postCompactMaxTokensPerEntry = 5_000;
    private int postCompactSkillTokenBudget = 25_000;
    private int microCompactStaleMinutes = 60;
    private int maxAutoCompactFailures = 3;

    public String getArtifactDir() {
        return artifactDir;
    }

    public void setArtifactDir(String artifactDir) {
        this.artifactDir = artifactDir;
    }

    public int getSingleArtifactBytes() {
        return singleArtifactBytes;
    }

    public void setSingleArtifactBytes(int singleArtifactBytes) {
        this.singleArtifactBytes = singleArtifactBytes;
    }

    public int getToolMessageBytes() {
        return toolMessageBytes;
    }

    public void setToolMessageBytes(int toolMessageBytes) {
        this.toolMessageBytes = toolMessageBytes;
    }

    public int getPreviewBytes() {
        return previewBytes;
    }

    public void setPreviewBytes(int previewBytes) {
        this.previewBytes = previewBytes;
    }

    public int getSnipTriggerTokens() {
        return snipTriggerTokens;
    }

    public void setSnipTriggerTokens(int snipTriggerTokens) {
        this.snipTriggerTokens = snipTriggerTokens;
    }

    public int getSnipTargetTokens() {
        return snipTargetTokens;
    }

    public void setSnipTargetTokens(int snipTargetTokens) {
        this.snipTargetTokens = snipTargetTokens;
    }

    public int getProjectionSoftTokens() {
        return projectionSoftTokens;
    }

    public void setProjectionSoftTokens(int projectionSoftTokens) {
        this.projectionSoftTokens = projectionSoftTokens;
    }

    public int getProjectionHardTokens() {
        return projectionHardTokens;
    }

    public void setProjectionHardTokens(int projectionHardTokens) {
        this.projectionHardTokens = projectionHardTokens;
    }

    public int getEffectiveContextWindow() {
        return effectiveContextWindow;
    }

    public void setEffectiveContextWindow(int effectiveContextWindow) {
        this.effectiveContextWindow = effectiveContextWindow;
    }

    public int getResponseReserveTokens() {
        return responseReserveTokens;
    }

    public void setResponseReserveTokens(int responseReserveTokens) {
        this.responseReserveTokens = responseReserveTokens;
    }

    public int getAutoCompactBufferTokens() {
        return autoCompactBufferTokens;
    }

    public void setAutoCompactBufferTokens(int autoCompactBufferTokens) {
        this.autoCompactBufferTokens = autoCompactBufferTokens;
    }

    public int getMicroCompactKeepRecent() {
        return microCompactKeepRecent;
    }

    public void setMicroCompactKeepRecent(int microCompactKeepRecent) {
        this.microCompactKeepRecent = microCompactKeepRecent;
    }

    public int getSnipKeepRecentConversation() {
        return snipKeepRecentConversation;
    }

    public void setSnipKeepRecentConversation(int snipKeepRecentConversation) {
        this.snipKeepRecentConversation = snipKeepRecentConversation;
    }

    public int getPostCompactMaxRestoreEntries() {
        return postCompactMaxRestoreEntries;
    }

    public void setPostCompactMaxRestoreEntries(int postCompactMaxRestoreEntries) {
        this.postCompactMaxRestoreEntries = postCompactMaxRestoreEntries;
    }

    public int getPostCompactTokenBudget() {
        return postCompactTokenBudget;
    }

    public void setPostCompactTokenBudget(int postCompactTokenBudget) {
        this.postCompactTokenBudget = postCompactTokenBudget;
    }

    public int getPostCompactMaxTokensPerEntry() {
        return postCompactMaxTokensPerEntry;
    }

    public void setPostCompactMaxTokensPerEntry(int postCompactMaxTokensPerEntry) {
        this.postCompactMaxTokensPerEntry = postCompactMaxTokensPerEntry;
    }

    public int getPostCompactSkillTokenBudget() {
        return postCompactSkillTokenBudget;
    }

    public void setPostCompactSkillTokenBudget(int postCompactSkillTokenBudget) {
        this.postCompactSkillTokenBudget = postCompactSkillTokenBudget;
    }

    public int getMicroCompactStaleMinutes() {
        return microCompactStaleMinutes;
    }

    public void setMicroCompactStaleMinutes(int microCompactStaleMinutes) {
        this.microCompactStaleMinutes = microCompactStaleMinutes;
    }

    public int getMaxAutoCompactFailures() {
        return maxAutoCompactFailures;
    }

    public void setMaxAutoCompactFailures(int maxAutoCompactFailures) {
        this.maxAutoCompactFailures = maxAutoCompactFailures;
    }
}
