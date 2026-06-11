package com.lumisight.memory.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "lumisight.memory")
public class MemoryProperties {

    private String rootDir = ".lumisight/memory";
    private int maxEntrypointLines = 200;
    private int maxEntrypointBytes = 25_000;
    private int headerScanLines = 30;
    private int maxScannedFiles = 200;
    private int maxRelevantEntries = 5;
    private int staleAfterDays = 1;

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public int getMaxEntrypointLines() {
        return maxEntrypointLines;
    }

    public void setMaxEntrypointLines(int maxEntrypointLines) {
        this.maxEntrypointLines = maxEntrypointLines;
    }

    public int getMaxEntrypointBytes() {
        return maxEntrypointBytes;
    }

    public void setMaxEntrypointBytes(int maxEntrypointBytes) {
        this.maxEntrypointBytes = maxEntrypointBytes;
    }

    public int getHeaderScanLines() {
        return headerScanLines;
    }

    public void setHeaderScanLines(int headerScanLines) {
        this.headerScanLines = headerScanLines;
    }

    public int getMaxScannedFiles() {
        return maxScannedFiles;
    }

    public void setMaxScannedFiles(int maxScannedFiles) {
        this.maxScannedFiles = maxScannedFiles;
    }

    public int getMaxRelevantEntries() {
        return maxRelevantEntries;
    }

    public void setMaxRelevantEntries(int maxRelevantEntries) {
        this.maxRelevantEntries = maxRelevantEntries;
    }

    public int getStaleAfterDays() {
        return staleAfterDays;
    }

    public void setStaleAfterDays(int staleAfterDays) {
        this.staleAfterDays = staleAfterDays;
    }
}
