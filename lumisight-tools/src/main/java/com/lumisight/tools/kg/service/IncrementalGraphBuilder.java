package com.lumisight.tools.kg.service;

import com.lumisight.tools.kg.config.NebulaProperties;
import com.lumisight.tools.kg.model.GraphEdge;
import com.lumisight.tools.kg.model.GraphNode;
import com.lumisight.tools.kg.model.GraphSnapshot;
import com.lumisight.tools.kg.parser.JavaCodeGraphParser;
import com.lumisight.tools.kg.parser.ParsedGraphFragment;
import com.lumisight.tools.kg.store.NebulaGraphStore;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Component
public class IncrementalGraphBuilder {

    private final JavaCodeGraphParser parser = new JavaCodeGraphParser();
    private final NebulaProperties nebulaProperties;

    public IncrementalGraphBuilder(NebulaProperties nebulaProperties) {
        this.nebulaProperties = nebulaProperties;
    }

    public GraphSnapshot build(Path repoRoot) {
        GitState gitState = resolveGitState(repoRoot);
        validateBranch(gitState.branch());
        String repoName = repoRoot.getFileName().toString();
        String repoRootString = repoRoot.toAbsolutePath().normalize().toString();

        try (NebulaGraphStore store = new NebulaGraphStore(
                nebulaProperties.getHost(),
                nebulaProperties.getPort(),
                nebulaProperties.getUsername(),
                nebulaProperties.getPassword()
        )) {
            String graphCommit = store.currentRepoCommit(repoName);

            GraphSnapshot skipped = new GraphSnapshot();
            skipped.setRepoRoot(repoRootString);
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
            boolean fullBuild = graphCommit == null || graphCommit.isBlank();

            if (fullBuild) {
                // 首次构建全量解析。
                List<Path> javaFiles = collectJavaFiles(repoRoot);
                for (Path javaFile : javaFiles) {
                    ParsedGraphFragment fragment = parser.parseFile(repoRoot, javaFile);
                    snapshot.getNodes().putAll(fragment.getNodes());
                    snapshot.getEdges().putAll(fragment.getEdges());
                    snapshot.getFileHashes().put(repoRoot.relativize(javaFile).toString(), gitState.commit());
                }
            } else {
                // 后续仅处理 git diff 变更文件，并按类粒度做删除+重建。
                List<JavaFileDiff> diffs = collectJavaDiffs(repoRoot, graphCommit, gitState.commit());
                if (diffs.isEmpty()) {
                    store.writeRepoMeta(repoName, repoRootString, gitState.branch(), gitState.commit(), -1, -1, -1);
                    snapshot.setRepoRoot(repoRootString);
                    snapshot.setGitBranch(gitState.branch());
                    snapshot.setGitCommit(gitState.commit());
                    snapshot.setGeneratedAt(Instant.now());
                    snapshot.setBuildUpdated(true);
                    snapshot.setBuildSkipReason(null);
                    return snapshot;
                }
                for (JavaFileDiff diff : diffs) {
                    ParsedGraphFragment oldFragment = new ParsedGraphFragment();
                    if (diff.oldPath() != null) {
                        String oldSource = readFileAtCommit(repoRoot, graphCommit, diff.oldPath());
                        if (oldSource != null) {
                            oldFragment = parser.parseSource(repoRoot, diff.oldPath(), oldSource);
                        }
                    }

                    ParsedGraphFragment newFragment = new ParsedGraphFragment();
                    if (diff.newPath() != null) {
                        Path currentFile = repoRoot.resolve(diff.newPath());
                        if (Files.exists(currentFile)) {
                            newFragment = parser.parseFile(repoRoot, currentFile);
                        }
                    }

                    Set<String> impactedClasses = new HashSet<>();
                    impactedClasses.addAll(extractClassNames(oldFragment));
                    impactedClasses.addAll(extractClassNames(newFragment));

                    Set<String> deleteNodeIds = collectNodeIdsByClasses(oldFragment, impactedClasses);
                    deleteNodeIds.addAll(collectNodeIdsByClasses(newFragment, impactedClasses));
                    store.deleteVertices(deleteNodeIds);

                    for (GraphNode node : newFragment.getNodes().values()) {
                        if (node.type().name().equals("MODULE") || node.type().name().equals("PACKAGE")
                                || impactedClasses.contains(node.className())) {
                            writeNode(store, node, gitState);
                            snapshot.getNodes().put(node.id(), node);
                        }
                    }
                    for (GraphEdge edge : newFragment.getEdges().values()) {
                        GraphNode fromNode = newFragment.getNodes().get(edge.fromNodeId());
                        GraphNode toNode = newFragment.getNodes().get(edge.toNodeId());
                        if (fromNode == null || toNode == null) {
                            continue;
                        }
                        boolean fromImpacted = fromNode.type().name().equals("MODULE") || fromNode.type().name().equals("PACKAGE")
                                || impactedClasses.contains(fromNode.className());
                        boolean toImpacted = toNode.type().name().equals("MODULE") || toNode.type().name().equals("PACKAGE")
                                || impactedClasses.contains(toNode.className());
                        if (fromImpacted && toImpacted) {
                            writeEdge(store, edge, gitState);
                            snapshot.getEdges().put(edge.id(), edge);
                        }
                    }
                    if (diff.newPath() != null) {
                        snapshot.getFileHashes().put(diff.newPath(), gitState.commit());
                    }
                }
            }

            if (fullBuild) {
                persistToNebula(store, snapshot, gitState);
            }
            store.writeRepoMeta(
                    repoName,
                    repoRootString,
                    gitState.branch(),
                    gitState.commit(),
                    fullBuild ? snapshot.getFileHashes().size() : -1,
                    fullBuild ? snapshot.getNodes().size() : -1,
                    fullBuild ? snapshot.getEdges().size() : -1
            );
            snapshot.setRepoRoot(repoRootString);
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
            writeNode(store, node, gitState);
        }

        for (GraphEdge edge : snapshot.getEdges().values()) {
            writeEdge(store, edge, gitState);
        }
    }

    private void writeNode(NebulaGraphStore store, GraphNode node, GitState gitState) {
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
        store.writeNode(node.id(), props);
    }

    private void writeEdge(NebulaGraphStore store, GraphEdge edge, GitState gitState) {
        Map<String, Object> props = new HashMap<>();
        props.put("edge_id", edge.id());
        props.put("edge_type", edge.type().name());
        props.put("source_file", edge.sourceFile());
        props.put("git_branch", gitState.branch());
        props.put("git_commit", gitState.commit());
        store.writeEdge(edge.fromNodeId(), edge.toNodeId(), props);
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

    private Set<String> extractClassNames(ParsedGraphFragment fragment) {
        Set<String> classNames = new HashSet<>();
        for (GraphNode node : fragment.getNodes().values()) {
            if (node.className() != null && !node.className().isBlank()) {
                classNames.add(node.className());
            }
        }
        return classNames;
    }

    private Set<String> collectNodeIdsByClasses(ParsedGraphFragment fragment, Set<String> classNames) {
        Set<String> ids = new HashSet<>();
        for (GraphNode node : fragment.getNodes().values()) {
            if (node.type().name().equals("MODULE") || node.type().name().equals("PACKAGE")) {
                ids.add(node.id());
                continue;
            }
            if (node.className() != null && classNames.contains(node.className())) {
                ids.add(node.id());
            }
        }
        return ids;
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

    private List<JavaFileDiff> collectJavaDiffs(Path repoRoot, String fromCommit, String toCommit) {
        List<String> lines = runGitCommandAllLines(repoRoot, "diff", "--name-status", fromCommit + ".." + toCommit, "--", "*.java");
        List<JavaFileDiff> diffs = new ArrayList<>();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t");
            if (parts.length < 2) {
                continue;
            }
            String status = parts[0];
            if (status.startsWith("R") && parts.length >= 3) {
                diffs.add(new JavaFileDiff(parts[1], parts[2]));
            } else if ("A".equals(status) || "M".equals(status)) {
                diffs.add(new JavaFileDiff(parts[1], parts[1]));
            } else if ("D".equals(status)) {
                diffs.add(new JavaFileDiff(parts[1], null));
            }
        }
        return diffs;
    }

    private String readFileAtCommit(Path repoRoot, String commit, String path) {
        List<String> output = runGitCommandAllLines(repoRoot, "show", commit + ":" + path);
        if (output.isEmpty()) {
            return null;
        }
        return String.join(System.lineSeparator(), output);
    }

    private List<String> runGitCommandAllLines(Path repoRoot, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(repoRoot.toFile());
        try {
            Process process = builder.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                return new ArrayList<>();
            }
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to run git command: git " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while running git command", e);
        }
    }

    private record GitState(String branch, String commit) {
    }

    private record JavaFileDiff(String oldPath, String newPath) {
    }
}
