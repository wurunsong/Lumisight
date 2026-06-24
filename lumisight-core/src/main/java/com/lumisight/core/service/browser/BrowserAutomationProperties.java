package com.lumisight.core.service.browser;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.browser")
public class BrowserAutomationProperties {

    private boolean enabled = true;
    private boolean headless = true;
    private String artifactDir = ".lumisight/browser-artifacts";
    private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private String locale = "zh-CN";
    private String timezoneId = "Asia/Shanghai";
    private int viewportWidth = 1440;
    private int viewportHeight = 960;
    private int navigationTimeoutMs = 15000;
    private int actionTimeoutMs = 10000;
    private int networkIdleTimeoutMs = 4000;
    private int postActionDelayMs = 600;
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

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getTimezoneId() {
        return timezoneId;
    }

    public void setTimezoneId(String timezoneId) {
        this.timezoneId = timezoneId;
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

    public int getNetworkIdleTimeoutMs() {
        return networkIdleTimeoutMs;
    }

    public void setNetworkIdleTimeoutMs(int networkIdleTimeoutMs) {
        this.networkIdleTimeoutMs = networkIdleTimeoutMs;
    }

    public int getPostActionDelayMs() {
        return postActionDelayMs;
    }

    public void setPostActionDelayMs(int postActionDelayMs) {
        this.postActionDelayMs = postActionDelayMs;
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
