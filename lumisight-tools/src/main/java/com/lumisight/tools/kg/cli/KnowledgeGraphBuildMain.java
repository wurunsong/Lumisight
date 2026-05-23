package com.lumisight.tools.kg.cli;

import com.lumisight.tools.kg.model.GraphSnapshot;
import com.lumisight.tools.kg.service.IncrementalGraphBuilder;

import java.nio.file.Path;

public class KnowledgeGraphBuildMain {

    public static void main(String[] args) {
        // 命令行参数：目标仓库根目录。
        if (args.length < 1) {
            System.err.println("Usage: KnowledgeGraphBuildMain <repoRoot>");
            System.exit(1);
        }

        Path repoRoot = Path.of(args[0]).toAbsolutePath().normalize();

        IncrementalGraphBuilder builder = new IncrementalGraphBuilder();
        GraphSnapshot snapshot = builder.build(repoRoot);

        // 输出本次构建统计，便于快速确认增量结果规模。
        System.out.println("Build done.");
        System.out.println("Repo: " + snapshot.getRepoRoot());
        System.out.println("Nodes: " + snapshot.getNodes().size());
        System.out.println("Edges: " + snapshot.getEdges().size());
        System.out.println("Files indexed: " + snapshot.getFileHashes().size());
        System.out.println("Updated: " + snapshot.isBuildUpdated());
        System.out.println("Skip reason: " + snapshot.getBuildSkipReason());
        System.out.println("Git branch: " + snapshot.getGitBranch());
        System.out.println("Git commit: " + snapshot.getGitCommit());
    }
}
