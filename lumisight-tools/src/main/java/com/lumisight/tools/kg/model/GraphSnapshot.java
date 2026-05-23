package com.lumisight.tools.kg.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class GraphSnapshot {

    private String repoRoot;
    private Instant generatedAt;
    private String gitBranch;
    private String gitCommit;
    private Map<String, GraphNode> nodes = new LinkedHashMap<>();
    private Map<String, GraphEdge> edges = new LinkedHashMap<>();
    private Map<String, String> fileHashes = new HashMap<>();
    @JsonIgnore
    private boolean buildUpdated;
    @JsonIgnore
    private String buildSkipReason;

    public String getRepoRoot() {
        return repoRoot;
    }

    public void setRepoRoot(String repoRoot) {
        this.repoRoot = repoRoot;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }

    public String getGitBranch() {
        return gitBranch;
    }

    public void setGitBranch(String gitBranch) {
        this.gitBranch = gitBranch;
    }

    public String getGitCommit() {
        return gitCommit;
    }

    public void setGitCommit(String gitCommit) {
        this.gitCommit = gitCommit;
    }

    public Map<String, GraphNode> getNodes() {
        return nodes;
    }

    public void setNodes(Map<String, GraphNode> nodes) {
        this.nodes = nodes;
    }

    public Map<String, GraphEdge> getEdges() {
        return edges;
    }

    public void setEdges(Map<String, GraphEdge> edges) {
        this.edges = edges;
    }

    public Map<String, String> getFileHashes() {
        return fileHashes;
    }

    public void setFileHashes(Map<String, String> fileHashes) {
        this.fileHashes = fileHashes;
    }

    public boolean isBuildUpdated() {
        return buildUpdated;
    }

    public void setBuildUpdated(boolean buildUpdated) {
        this.buildUpdated = buildUpdated;
    }

    public String getBuildSkipReason() {
        return buildSkipReason;
    }

    public void setBuildSkipReason(String buildSkipReason) {
        this.buildSkipReason = buildSkipReason;
    }
}
