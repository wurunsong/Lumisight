package com.lumisight.tools.kg.service;

import com.lumisight.tools.kg.model.GraphEdge;
import com.lumisight.tools.kg.model.GraphNode;
import com.lumisight.tools.kg.model.GraphSnapshot;
import com.lumisight.tools.kg.parser.JavaCodeGraphParser;
import com.lumisight.tools.kg.parser.ParsedGraphFragment;
import com.lumisight.tools.kg.store.NebulaGraphStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class IncrementalGraphBuilder {

    private final JavaCodeGraphParser parser = new JavaCodeGraphParser();

    public GraphSnapshot build(Path repoRoot) {
        GitState gitState = resolveGitState(repoRoot);
        validateBranch(gitState.branch());

        try (NebulaGraphStore store = new NebulaGraphStore("127.0.0.1", 9669, "root", "nebula")) {
            String graphCommit = store.currentGraphCommit();

            GraphSnapshot skipped = new GraphSnapshot();
            skipped.setRepoRoot(repoRoot.toAbsolutePath().normalize().toString());
            skipped.setGitBranch(gitState.branch());
            skipped.setGitCommit(gitState.commit());
            skipped.setBuildUpdated(false);

            if (graphCommit != null && !graphCommit.isBlank()) {
                if (graphCommit.equals(gitState.commit())) {
                    skipped.setBuildSkipReason("No update: graph already at current HEAD");
                    return skipped;
                }
                if (!isAncestor(repoRoot, graphCommit, gitState.commit())) {
                    skipped.setBuildSkipReason("No update: current branch is not ahead of graph commit");
                    return skipped;
                }
            }

            GraphSnapshot snapshot = new GraphSnapshot();
            snapshot.setNodes(new HashMap<>());
            snapshot.setEdges(new HashMap<>());
            snapshot.setFileHashes(new HashMap<>());

            // 全量解析当前仓库 Java 文件，产出当前提交对应的完整图谱。
            List<Path> javaFiles = collectJavaFiles(repoRoot);
            for (Path javaFile : javaFiles) {
                ParsedGraphFragment fragment = parser.parseFile(repoRoot, javaFile);
                snapshot.getNodes().putAll(fragment.getNodes());
                snapshot.getEdges().putAll(fragment.getEdges());
                snapshot.getFileHashes().put(repoRoot.relativize(javaFile).toString(), gitState.commit());
            }

            persistToNebula(store, snapshot, gitState);
            snapshot.setRepoRoot(repoRoot.toAbsolutePath().normalize().toString());
            snapshot.setGitBranch(gitState.branch());
            snapshot.setGitCommit(gitState.commit());
            snapshot.setGeneratedAt(Instant.now());
            snapshot.setBuildUpdated(true);
            snapshot.setBuildSkipReason(null);
            return snapshot;
        }
    }

    private void persistToNebula(NebulaGraphStore store, GraphSnapshot snapshot, GitState gitState) {
        for (GraphNode node : snapshot.getNodes().values()) {
            String vid = commitScopedVid(gitState.commit(), node.id());
            Map<String, Object> props = new HashMap<>();
            props.put("node_id", node.id());
            props.put("node_type", node.type().name());
            props.put("name", node.name());
            props.put("qualified_name", node.qualifiedName());
            props.put("source_file", node.sourceFile());
            props.put("repo_name", node.repoName());
            props.put("module_name", node.moduleName());
            props.put("package_name", node.packageName());
            props.put("class_name", node.className());
            props.put("method_name", node.methodName());
            props.put("parameter_count", node.parameterCount() == null ? 0 : node.parameterCount());
            props.put("git_branch", gitState.branch());
            props.put("git_commit", gitState.commit());
            store.writeNode(vid, props);
        }

        for (GraphEdge edge : snapshot.getEdges().values()) {
            String fromVid = commitScopedVid(gitState.commit(), edge.fromNodeId());
            String toVid = commitScopedVid(gitState.commit(), edge.toNodeId());
            Map<String, Object> props = new HashMap<>();
            props.put("edge_id", edge.id());
            props.put("edge_type", edge.type().name());
            props.put("source_file", edge.sourceFile());
            props.put("git_branch", gitState.branch());
            props.put("git_commit", gitState.commit());
            store.writeEdge(fromVid, toVid, props);
        }

        store.writeState(gitState.branch(), gitState.commit());
    }

    private List<Path> collectJavaFiles(Path repoRoot) {
        try (Stream<Path> stream = Files.walk(repoRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan repository: " + repoRoot, e);
        }
    }

    private String commitScopedVid(String commit, String nodeId) {
        return commit + ":" + nodeId;
    }

    private GitState resolveGitState(Path repoRoot) {
        String branch = runGitCommand(repoRoot, "rev-parse", "--abbrev-ref", "HEAD");
        String commit = runGitCommand(repoRoot, "rev-parse", "HEAD");
        return new GitState(branch, commit);
    }

    private void validateBranch(String branch) {
        if (!"main".equals(branch) && !"master".equals(branch)) {
            throw new IllegalStateException("Only main/master branch is allowed for KG build, current: " + branch);
        }
    }

    private boolean isAncestor(Path repoRoot, String ancestorCommit, String targetCommit) {
        ProcessBuilder builder = new ProcessBuilder(
                "git", "merge-base", "--is-ancestor", ancestorCommit, targetCommit
        );
        builder.directory(repoRoot.toFile());
        try {
            Process process = builder.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return true;
            }
            if (exitCode == 1) {
                return false;
            }
            throw new IllegalStateException("Failed to compare git history, exit code: " + exitCode);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run git merge-base command", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while comparing git history", e);
        }
    }

    private String runGitCommand(Path repoRoot, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repoRoot.toFile());
        try {
            Process process = builder.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.readLine();
            }
            int exitCode = process.waitFor();
            if (exitCode != 0 || output == null || output.isBlank()) {
                throw new IllegalStateException("Git command failed: git " + String.join(" ", args));
            }
            return output.trim();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run git command: git " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git command", e);
        }
    }

    private record GitState(String branch, String commit) {
    }
}
