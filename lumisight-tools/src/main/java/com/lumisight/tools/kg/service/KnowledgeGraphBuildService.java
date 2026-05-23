package com.lumisight.tools.kg.service;

import com.lumisight.tools.kg.model.GraphSnapshot;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class KnowledgeGraphBuildService {

    private final IncrementalGraphBuilder incrementalGraphBuilder;

    public KnowledgeGraphBuildService(IncrementalGraphBuilder incrementalGraphBuilder) {
        this.incrementalGraphBuilder = incrementalGraphBuilder;
    }

    // 统一构建入口：供 API 调用，也便于后续接入定时任务。
    public KnowledgeGraphBuildResult build(String repoRoot) {
        Path repo = Path.of(repoRoot).toAbsolutePath().normalize();
        GraphSnapshot result = incrementalGraphBuilder.build(repo);
        return new KnowledgeGraphBuildResult(
                result.getRepoRoot(),
                result.getNodes().size(),
                result.getEdges().size(),
                result.getFileHashes().size(),
                result.getGeneratedAt() == null ? null : result.getGeneratedAt().toString(),
                result.isBuildUpdated(),
                result.getBuildSkipReason(),
                result.getGitBranch(),
                result.getGitCommit()
        );
    }
}
