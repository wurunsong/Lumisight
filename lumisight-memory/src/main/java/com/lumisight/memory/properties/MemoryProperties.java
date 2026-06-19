package com.lumisight.memory.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "lumisight.memory")
public class MemoryProperties {

    private String rootDir = ".lumisight/memory";
    private boolean autoArchiveEnabled = true;
    private String archiveDirName = ".forgotten";
    private int maxEntrypointLines = 200;
    private int maxEntrypointBytes = 25_000;
    private int headerScanLines = 30;
    private int maxScannedFiles = 200;
    private int maxRelevantEntries = 5;
    private int staleAfterDays = 1;
    private int softForgetAfterDays = 7;
    private int hardForgetAfterDays = 30;
    private List<String> autoForgetTypes = new ArrayList<>(List.of("project", "reference"));

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public boolean isAutoArchiveEnabled() {
        return autoArchiveEnabled;
    }

    public void setAutoArchiveEnabled(boolean autoArchiveEnabled) {
        this.autoArchiveEnabled = autoArchiveEnabled;
    }

    public String getArchiveDirName() {
        return archiveDirName;
    }

    public void setArchiveDirName(String archiveDirName) {
        this.archiveDirName = archiveDirName;
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

    public int getSoftForgetAfterDays() {
        return softForgetAfterDays;
    }

    public void setSoftForgetAfterDays(int softForgetAfterDays) {
        this.softForgetAfterDays = softForgetAfterDays;
    }

    public int getHardForgetAfterDays() {
        return hardForgetAfterDays;
    }

    public void setHardForgetAfterDays(int hardForgetAfterDays) {
        this.hardForgetAfterDays = hardForgetAfterDays;
    }

    public List<String> getAutoForgetTypes() {
        return autoForgetTypes;
    }

    public void setAutoForgetTypes(List<String> autoForgetTypes) {
        this.autoForgetTypes = autoForgetTypes == null ? new ArrayList<>() : new ArrayList<>(autoForgetTypes);
    }
}
