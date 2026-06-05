package com.lumisight.core.service.browser;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.browser")
public class BrowserAutomationProperties {

    private boolean enabled = true;
    private boolean headless = true;
    private String artifactDir = ".lumisight/browser-artifacts";
    private int viewportWidth = 1440;
    private int viewportHeight = 960;
    private int navigationTimeoutMs = 15000;
    private int actionTimeoutMs = 10000;
    private int maxSnapshotElements = 40;
    private int maxSnapshotTextChars = 4000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public String getArtifactDir() {
        return artifactDir;
    }

    public void setArtifactDir(String artifactDir) {
        this.artifactDir = artifactDir;
    }

    public int getViewportWidth() {
        return viewportWidth;
    }

    public void setViewportWidth(int viewportWidth) {
        this.viewportWidth = viewportWidth;
    }

    public int getViewportHeight() {
        return viewportHeight;
    }

    public void setViewportHeight(int viewportHeight) {
        this.viewportHeight = viewportHeight;
    }

    public int getNavigationTimeoutMs() {
        return navigationTimeoutMs;
    }

    public void setNavigationTimeoutMs(int navigationTimeoutMs) {
        this.navigationTimeoutMs = navigationTimeoutMs;
    }

    public int getActionTimeoutMs() {
        return actionTimeoutMs;
    }

    public void setActionTimeoutMs(int actionTimeoutMs) {
        this.actionTimeoutMs = actionTimeoutMs;
    }

    public int getMaxSnapshotElements() {
        return maxSnapshotElements;
    }

    public void setMaxSnapshotElements(int maxSnapshotElements) {
        this.maxSnapshotElements = maxSnapshotElements;
    }

    public int getMaxSnapshotTextChars() {
        return maxSnapshotTextChars;
    }

    public void setMaxSnapshotTextChars(int maxSnapshotTextChars) {
        this.maxSnapshotTextChars = maxSnapshotTextChars;
    }
}
