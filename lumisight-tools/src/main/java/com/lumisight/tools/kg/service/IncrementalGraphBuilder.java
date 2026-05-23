package com.lumisight.tools.kg.service;

import com.lumisight.tools.kg.config.NebulaProperties;
import com.lumisight.tools.kg.model.GraphElementStatus;
import com.lumisight.tools.kg.model.GraphEdge;
import com.lumisight.tools.kg.model.GraphNode;
import com.lumisight.tools.kg.model.GraphSnapshot;
import com.lumisight.tools.kg.parser.JavaCodeGraphParser;
import com.lumisight.tools.kg.parser.ParsedGraphFragment;
import com.lumisight.tools.kg.store.NebulaGraphStore;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class IncrementalGraphBuilder {

    private final JavaCodeGraphParser parser = new JavaCodeGraphParser();
    private final NebulaProperties nebulaProperties;

    public IncrementalGraphBuilder(NebulaProperties nebulaProperties) {
        this.nebulaProperties = nebulaProperties;
    }

    public GraphSnapshot build(Path repoRoot) {
        GitState gitState = resolveGitState(repoRoot);
        log.info("Start KG build, repoRoot={}, gitBranch={}, gitCommit={}",
                repoRoot.toAbsolutePath().normalize(), gitState.branch(), gitState.commit());
        validateBranch(gitState.branch());
        String repoName = repoRoot.getFileName().toString();
        String repoRootString = repoRoot.toAbsolutePath().normalize().toString();

        try (NebulaGraphStore store = new NebulaGraphStore(
                nebulaProperties.getHost(),
                nebulaProperties.getPort(),
                nebulaProperties.getUsername(),
                nebulaProperties.getPassword(),
                repoName
        )) {
            String graphCommit = store.currentRepoCommit(repoName);
            log.info("Current graph baseline commit from repo_meta, repoName={}, graphCommit={}", repoName, graphCommit);

            GraphSnapshot skipped = new GraphSnapshot();
            skipped.setRepoRoot(repoRootString);
            skipped.setGitBranch(gitState.branch());
            skipped.setGitCommit(gitState.commit());
            skipped.setBuildUpdated(false);

            if (graphCommit != null && !graphCommit.isBlank()) {
                if (graphCommit.equals(gitState.commit())) {
                    log.info("Skip KG build: graph already at current HEAD, commit={}", gitState.commit());
                    skipped.setBuildSkipReason("No update: graph already at current HEAD");
                    return skipped;
                }
                if (!isAncestor(repoRoot, graphCommit, gitState.commit())) {
                    log.warn("Skip KG build: current commit is not ahead of baseline, baseline={}, current={}",
                            graphCommit, gitState.commit());
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
                log.info("Run full KG build, repoName={}", repoName);
                // 首次构建全量解析。
                List<Path> javaFiles = collectJavaFiles(repoRoot);
                log.info("Full KG build java file count={}", javaFiles.size());
                for (Path javaFile : javaFiles) {
                    ParsedGraphFragment fragment = parser.parseFile(repoRoot, javaFile);
                    snapshot.getNodes().putAll(fragment.getNodes());
                    snapshot.getEdges().putAll(fragment.getEdges());
                    String relativePath = repoRoot.relativize(javaFile).toString();
                    snapshot.getFileHashes().put(relativePath, gitState.commit());
                    log.info("Full build parsed file, file={}, nodes={}, edges={}",
                            relativePath, fragment.getNodes().size(), fragment.getEdges().size());
                }
            } else {
                log.info("Run incremental KG build, baselineCommit={}, currentCommit={}", graphCommit, gitState.commit());
                // 后续仅处理 git diff 变更文件，并按类粒度做删除+重建。
                List<JavaFileDiff> diffs = collectJavaDiffs(repoRoot, graphCommit, gitState.commit());
                log.info("Incremental KG build diff java file count={}", diffs.size());
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
                    log.info("Process java diff, oldPath={}, newPath={}", diff.oldPath(), diff.newPath());
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
                    log.info("Diff impacted classes, count={}, classes={}", impactedClasses.size(), impactedClasses);

                    for (String className : impactedClasses) {
                        Map<String, GraphNode> oldMethods = methodsByClass(oldFragment, className);
                        Map<String, GraphNode> newMethods = methodsByClass(newFragment, className);

                        // 新版本缺失的方法：标记删除。
                        for (String oldMethodId : oldMethods.keySet()) {
                            if (!newMethods.containsKey(oldMethodId)) {
                                store.updateNodeStatus(oldMethodId, GraphElementStatus.DELETED.code());
                                store.markAdjacentEdgesDeleted(oldMethodId);
                            }
                        }

                        GraphNode oldClassNode = classNodeByName(oldFragment, className);
                        GraphNode newClassNode = classNodeByName(newFragment, className);
                        if (newClassNode != null) {
                            writeNode(store, newClassNode, gitState);
                            snapshot.getNodes().put(newClassNode.id(), newClassNode);
                        } else if (oldClassNode != null) {
                            store.updateNodeStatus(oldClassNode.id(), GraphElementStatus.DELETED.code());
                            store.markAdjacentEdgesDeleted(oldClassNode.id());
                        }

                        // 该类下方法统一重写（新增和保留都会覆盖写入）。
                        for (GraphNode methodNode : newMethods.values()) {
                            writeNode(store, methodNode, gitState);
                            snapshot.getNodes().put(methodNode.id(), methodNode);
                        }

                        // 该类相关边统一重写。
                        for (GraphEdge edge : relevantEdgesForClass(newFragment, className)) {
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
            log.info("KG build finished, repoName={}, updated=true, nodes={}, edges={}, files={}",
                    repoName, snapshot.getNodes().size(), snapshot.getEdges().size(), snapshot.getFileHashes().size());
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
        props.put("start_line", node.startLine() == null ? 0 : node.startLine());
        props.put("end_line", node.endLine() == null ? 0 : node.endLine());
        props.put("status", node.status() == null ? 0 : node.status().code());
        props.put("git_branch", gitState.branch());
        props.put("git_commit", gitState.commit());
        store.writeNode(node.id(), props);
    }

    private void writeEdge(NebulaGraphStore store, GraphEdge edge, GitState gitState) {
        Map<String, Object> props = new HashMap<>();
        props.put("edge_id", edge.id());
        props.put("edge_type", edge.type().name());
        props.put("source_file", edge.sourceFile());
        props.put("status", edge.status() == null ? 0 : edge.status().code());
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

    private GraphNode classNodeByName(ParsedGraphFragment fragment, String className) {
        for (GraphNode node : fragment.getNodes().values()) {
            if (node.type().name().equals("CLASS") && className.equals(node.className())) {
                return node;
            }
        }
        return null;
    }

    private Map<String, GraphNode> methodsByClass(ParsedGraphFragment fragment, String className) {
        Map<String, GraphNode> methods = new HashMap<>();
        for (GraphNode node : fragment.getNodes().values()) {
            if (node.type().name().equals("METHOD") && className.equals(node.className())) {
                methods.put(node.id(), node);
            }
        }
        return methods;
    }

    private List<GraphEdge> relevantEdgesForClass(ParsedGraphFragment fragment, String className) {
        List<GraphEdge> edges = new ArrayList<>();
        for (GraphEdge edge : fragment.getEdges().values()) {
            GraphNode fromNode = fragment.getNodes().get(edge.fromNodeId());
            GraphNode toNode = fragment.getNodes().get(edge.toNodeId());
            if (fromNode == null || toNode == null) {
                continue;
            }
            boolean hit = className.equals(fromNode.className()) || className.equals(toNode.className());
            if (hit) {
                edges.add(edge);
            }
        }
        return edges;
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
