package com.lumisight.tools.vector.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.lumisight.tools.kg.util.HashUtils;
import com.lumisight.tools.vector.model.CodeChunkIngestCommand;
import com.lumisight.tools.vector.model.RepoCodeChunkIngestResult;
import com.lumisight.tools.vector.model.SymbolDocIngestCommand;
import com.lumisight.tools.vector.model.VectorBatchIngestResult;
import com.lumisight.tools.vector.model.VectorIngestResult;
import com.lumisight.tools.vector.spi.SymbolDocGenerator;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class VectorIngestService {

    private static final Logger log = LoggerFactory.getLogger(VectorIngestService.class);

    private static final String CODE_CHUNK_COLLECTION = "code_chunk";
    private static final String SYMBOL_DOC_COLLECTION = "symbol_doc";
    // DashScope-compatible embedding endpoints accept at most 10 inputs per request.
    private static final int MAX_EMBEDDING_BATCH_SIZE = 10;

    private final VectorStore codeChunkVectorStore;
    private final VectorStore symbolDocVectorStore;
    private final SymbolDocGenerator symbolDocGenerator;
    private final CodeChunkSplitter codeChunkSplitter;

    public VectorIngestService(
            @Qualifier("codeChunkVectorStore") VectorStore codeChunkVectorStore,
            @Qualifier("symbolDocVectorStore") VectorStore symbolDocVectorStore,
            SymbolDocGenerator symbolDocGenerator,
            CodeChunkSplitter codeChunkSplitter
    ) {
        this.codeChunkVectorStore = codeChunkVectorStore;
        this.symbolDocVectorStore = symbolDocVectorStore;
        this.symbolDocGenerator = symbolDocGenerator;
        this.codeChunkSplitter = codeChunkSplitter;
    }

    public VectorIngestResult ingestCodeChunk(CodeChunkIngestCommand command) {
        String repo = Path.of(command.repoRoot()).toAbsolutePath().normalize().toString();
        String repoName = Path.of(repo).getFileName().toString();
        String id = stableDocId(CODE_CHUNK_COLLECTION, command.qualifiedName(), command.gitCommit(), command.sourceFile());
        Document doc = new Document(
                id,
                command.chunkText(),
                Map.of(
                        "repo_root", repo,
                        "repo_name", repoName,
                        "source_file", command.sourceFile(),
                        "qualified_name", command.qualifiedName(),
                        "start_line", command.startLine() == null ? 0 : command.startLine(),
                        "end_line", command.endLine() == null ? 0 : command.endLine(),
                        "git_branch", command.gitBranch() == null ? "" : command.gitBranch(),
                        "git_commit", command.gitCommit() == null ? "" : command.gitCommit(),
                        "doc_type", CODE_CHUNK_COLLECTION
                )
        );
        codeChunkVectorStore.add(List.of(doc));
        return new VectorIngestResult(CODE_CHUNK_COLLECTION, id, repo, command.qualifiedName());
    }

    public VectorIngestResult ingestSymbolDoc(SymbolDocIngestCommand command) {
        String repo = Path.of(command.repoRoot()).toAbsolutePath().normalize().toString();
        String repoName = Path.of(repo).getFileName().toString();
        String finalDocText = command.symbolDocText();
        if (finalDocText == null || finalDocText.isBlank()) {
            finalDocText = symbolDocGenerator.generate(
                    command.qualifiedName(),
                    command.symbolSignature(),
                    command.codeContext()
            );
        }
        String id = stableDocId(SYMBOL_DOC_COLLECTION, command.qualifiedName(), command.gitCommit(), command.sourceFile());
        Document doc = new Document(
                id,
                finalDocText,
                Map.of(
                        "repo_root", repo,
                        "repo_name", repoName,
                        "source_file", command.sourceFile(),
                        "qualified_name", command.qualifiedName(),
                        "kg_node_id", command.kgNodeId() == null ? "" : command.kgNodeId(),
                        "symbol_signature", command.symbolSignature() == null ? "" : command.symbolSignature(),
                        "git_branch", command.gitBranch() == null ? "" : command.gitBranch(),
                        "git_commit", command.gitCommit() == null ? "" : command.gitCommit(),
                        "doc_type", SYMBOL_DOC_COLLECTION
                )
        );
        symbolDocVectorStore.add(List.of(doc));
        return new VectorIngestResult(SYMBOL_DOC_COLLECTION, id, repo, command.qualifiedName());
    }

    public VectorBatchIngestResult ingestCodeChunksAuto(
            String repoRoot,
            String sourceFile,
            String qualifiedName,
            String codeText,
            Integer maxChunkChars,
            Integer overlapChars,
            String gitBranch,
            String gitCommit
    ) {
        String repo = Path.of(repoRoot).toAbsolutePath().normalize().toString();
        String repoName = Path.of(repo).getFileName().toString();
        List<CodeChunkSplitter.ChunkSlice> slices = codeChunkSplitter.split(codeText, maxChunkChars, overlapChars);
        List<Document> docs = new java.util.ArrayList<>();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < slices.size(); i++) {
            CodeChunkSplitter.ChunkSlice s = slices.get(i);
            String chunkQualifiedName = qualifiedName + "#chunk" + (i + 1);
            String id = stableDocId(CODE_CHUNK_COLLECTION, chunkQualifiedName, gitCommit, sourceFile);
            ids.add(id);
            docs.add(new Document(
                    id,
                    s.text(),
                    Map.of(
                            "repo_root", repo,
                            "repo_name", repoName,
                            "source_file", sourceFile,
                            "qualified_name", chunkQualifiedName,
                            "start_line", s.startLine(),
                            "end_line", s.endLine(),
                            "git_branch", gitBranch == null ? "" : gitBranch,
                            "git_commit", gitCommit == null ? "" : gitCommit,
                            "doc_type", CODE_CHUNK_COLLECTION
                    )
            ));
        }
        if (!docs.isEmpty()) {
            addInBatches(codeChunkVectorStore, docs, MAX_EMBEDDING_BATCH_SIZE);
        }
        return new VectorBatchIngestResult(CODE_CHUNK_COLLECTION, repo, sourceFile, docs.size(), ids);
    }

    public RepoCodeChunkIngestResult ingestRepoCodeChunks(
            String repoRoot,
            Integer maxChunkChars,
            Integer overlapChars,
            String gitBranch,
            String gitCommit
    ) {
        log.info("Vector ingest start, repoRoot={}, maxChunkChars={}, overlapChars={}, gitBranch={}, gitCommit={}",
                repoRoot, maxChunkChars, overlapChars, gitBranch, gitCommit);
        Path repo = Path.of(repoRoot).toAbsolutePath().normalize();
        String repoName = repo.getFileName().toString();
        String currentCommit = resolveCommit(repo, gitCommit);
        Path metaFile = repo.resolve(".lumisight/vector_last_commit.txt");
        String baselineCommit = readBaselineCommit(metaFile);
        if (currentCommit.equals(baselineCommit)) {
            log.info("Vector ingest skipped: baselineCommit equals currentCommit, commit={}", currentCommit);
            return new RepoCodeChunkIngestResult(
                    CODE_CHUNK_COLLECTION,
                    repo.toString(),
                    baselineCommit,
                    currentCommit,
                    0,
                    0,
                    0,
                    0
            );
        }

        List<String> changedFiles = collectChangedJavaFiles(repo, baselineCommit, currentCommit);
        List<String> deletedFiles = collectDeletedJavaFiles(repo, baselineCommit, currentCommit);
        log.info("Vector ingest commit diff, baselineCommit={}, currentCommit={}, changedJavaFiles={}, deletedJavaFiles={}",
                baselineCommit, currentCommit, changedFiles.size(), deletedFiles.size());
        List<Document> docs = new ArrayList<>();
        AtomicInteger javaFileCount = new AtomicInteger();
        AtomicInteger methodCount = new AtomicInteger();
        AtomicInteger parseErrorCount = new AtomicInteger();
        try {
            if (baselineCommit == null || baselineCommit.isBlank()) {
                try (Stream<Path> stream = Files.walk(repo)) {
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".java"))
                            .forEach(javaFile -> {
                                javaFileCount.incrementAndGet();
                                log.debug("Parsing java file: {}", javaFile);
                                parseFileToMethodChunks(
                                        repo,
                                        repoName,
                                        javaFile,
                                        maxChunkChars,
                                        overlapChars,
                                        gitBranch,
                                        currentCommit,
                                        docs,
                                        methodCount,
                                        parseErrorCount
                                );
                            });
                }
            } else {
                for (String relPath : deletedFiles) {
                    log.debug("Deleting vectors for removed file: {}", relPath);
                    codeChunkVectorStore.delete("repo_root == '" + escapeFilter(repo.toString()) + "' && source_file == '" + escapeFilter(relPath) + "'");
                }
                for (String relPath : changedFiles) {
                    Path javaFile = repo.resolve(relPath);
                    if (!Files.exists(javaFile)) {
                        continue;
                    }
                    javaFileCount.incrementAndGet();
                    log.debug("Re-parsing changed file: {}", relPath);
                    codeChunkVectorStore.delete("repo_root == '" + escapeFilter(repo.toString()) + "' && source_file == '" + escapeFilter(relPath) + "'");
                    parseFileToMethodChunks(
                            repo,
                            repoName,
                            javaFile,
                            maxChunkChars,
                            overlapChars,
                            gitBranch,
                            currentCommit,
                            docs,
                            methodCount,
                            parseErrorCount
                    );
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to incrementally ingest repo code chunks: " + repo, e);
        }

        // If we had files to process but extracted no chunks, fail fast instead of returning misleading success.
        if (docs.isEmpty() && (javaFileCount.get() > 0 || !changedFiles.isEmpty())) {
            throw new IllegalStateException("Vector ingest produced zero chunks. parsedJavaFiles=" + javaFileCount.get()
                    + ", changedJavaFiles=" + changedFiles.size()
                    + ", parseErrors=" + parseErrorCount.get());
        }

        if (!docs.isEmpty()) {
            log.info("Vector ingest write start, docs={}, batchSize={}", docs.size(), MAX_EMBEDDING_BATCH_SIZE);
            addInBatches(codeChunkVectorStore, docs, MAX_EMBEDDING_BATCH_SIZE);
        }
        writeBaselineCommit(metaFile, currentCommit);
        log.info("Vector ingest done, repo={}, javaFiles={}, methods={}, docs={}, deletedFiles={}, parseErrors={}",
                repo, javaFileCount.get(), methodCount.get(), docs.size(), deletedFiles.size(), parseErrorCount.get());
        return new RepoCodeChunkIngestResult(
                CODE_CHUNK_COLLECTION,
                repo.toString(),
                baselineCommit,
                currentCommit,
                javaFileCount.get(),
                methodCount.get(),
                docs.size(),
                deletedFiles.size()
        );
    }

    private void parseFileToMethodChunks(
            Path repo,
            String repoName,
            Path javaFile,
            Integer maxChunkChars,
            Integer overlapChars,
            String gitBranch,
            String gitCommit,
            List<Document> docs,
            AtomicInteger methodCount,
            AtomicInteger parseErrorCount
    ) {
        String sourceFile = repo.relativize(javaFile).toString();
        CompilationUnit cu;
        try {
            cu = StaticJavaParser.parse(javaFile);
        } catch (Exception e) {
            parseErrorCount.incrementAndGet();
            log.warn("Skip java file due to parse error, file={}, reason={}", sourceFile, e.getMessage());
            return;
        }
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("default");
        // Java 仓库入库先按方法粒度切：JavaParser 负责抽出每个方法，
        // CodeChunkSplitter 只在单个方法太长时继续切方法内部。
        cu.findAll(MethodDeclaration.class).forEach(method -> {
            Optional<ClassOrInterfaceDeclaration> ownerClass = method.findAncestor(ClassOrInterfaceDeclaration.class);
            if (ownerClass.isEmpty()) {
                return;
            }
            methodCount.incrementAndGet();
            String className = ownerClass.get().getNameAsString();
            String qualifiedName = pkg + "." + className + "#" + method.getNameAsString() + "(" + method.getParameters().size() + ")";
            String methodText = method.toString();
            int methodStartLine = method.getBegin().map(p -> p.line).orElse(0);
            int methodEndLine = method.getEnd().map(p -> p.line).orElse(methodStartLine);
            List<CodeChunkSplitter.ChunkSlice> slices = codeChunkSplitter.split(methodText, maxChunkChars, overlapChars);
            if (slices.isEmpty()) {
                slices = List.of(new CodeChunkSplitter.ChunkSlice(methodText, 1, Math.max(1, methodEndLine - methodStartLine + 1)));
            }
            for (int i = 0; i < slices.size(); i++) {
                CodeChunkSplitter.ChunkSlice slice = slices.get(i);
                String chunkQualifiedName = slices.size() == 1 ? qualifiedName : qualifiedName + "#chunk" + (i + 1);
                int startLine = methodStartLine <= 0 ? 0 : methodStartLine + slice.startLine() - 1;
                int endLine = methodStartLine <= 0 ? 0 : methodStartLine + slice.endLine() - 1;
                String id = stableDocId(CODE_CHUNK_COLLECTION, chunkQualifiedName, gitCommit, sourceFile);
                docs.add(new Document(
                        id,
                        slice.text(),
                        Map.of(
                                "repo_root", repo.toString(),
                                "repo_name", repoName,
                                "source_file", sourceFile,
                                "qualified_name", chunkQualifiedName,
                                "start_line", startLine,
                                "end_line", endLine,
                                "git_branch", gitBranch == null ? "" : gitBranch,
                                "git_commit", gitCommit == null ? "" : gitCommit,
                                "doc_type", CODE_CHUNK_COLLECTION
                        )
                ));
            }
        });
        log.debug("Parsed file done, sourceFile={}, methodsSoFar={}, docsSoFar={}", sourceFile, methodCount.get(), docs.size());
    }

    private String stableDocId(String type, String qualifiedName, String gitCommit, String sourceFile) {
        // Keep doc_id short to satisfy existing Milvus VarChar max_length constraints.
        return HashUtils.sha256Hex(type + "|" + qualifiedName + "|" + gitCommit + "|" + sourceFile)
                .substring(0, 32);
    }

    private void addInBatches(VectorStore store, List<Document> docs, int batchSize) {
        for (int i = 0; i < docs.size(); i += batchSize) {
            int end = Math.min(i + batchSize, docs.size());
            log.debug("Vector batch add, from={}, to={}, batchSize={}", i, end, end - i);
            store.add(docs.subList(i, end));
        }
    }

    private String resolveCommit(Path repo, String gitCommit) {
        if (gitCommit != null && !gitCommit.isBlank() && !"HEAD".equalsIgnoreCase(gitCommit)) {
            return gitCommit;
        }
        return runGit(repo, "rev-parse", "HEAD");
    }

    private List<String> collectChangedJavaFiles(Path repo, String baselineCommit, String currentCommit) {
        if (baselineCommit == null || baselineCommit.isBlank()) {
            return List.of();
        }
        List<String> lines = runGitLines(repo, "diff", "--name-status", baselineCommit + ".." + currentCommit, "--", "*.java");
        List<String> files = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String status = parts[0];
            if (status.startsWith("D")) {
                continue;
            }
            String path = parts[parts.length - 1];
            files.add(path);
        }
        return files;
    }

    private List<String> collectDeletedJavaFiles(Path repo, String baselineCommit, String currentCommit) {
        if (baselineCommit == null || baselineCommit.isBlank()) {
            return List.of();
        }
        List<String> lines = runGitLines(repo, "diff", "--name-status", baselineCommit + ".." + currentCommit, "--", "*.java");
        List<String> files = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            if (!parts[0].startsWith("D")) {
                continue;
            }
            files.add(parts[1]);
        }
        return files;
    }

    private String readBaselineCommit(Path metaFile) {
        try {
            if (!Files.exists(metaFile)) {
                return null;
            }
            String value = Files.readString(metaFile).trim();
            return value.isBlank() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeBaselineCommit(Path metaFile, String commit) {
        try {
            Files.createDirectories(metaFile.getParent());
            Files.writeString(metaFile, commit == null ? "" : commit);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write vector baseline commit: " + metaFile, e);
        }
    }

    private String runGit(Path repo, String... args) {
        List<String> lines = runGitLines(repo, args);
        if (lines.isEmpty()) {
            throw new IllegalStateException("git output empty: " + String.join(" ", args));
        }
        return lines.get(0).trim();
    }

    private List<String> runGitLines(Path repo, String... args) {
        try {
            ProcessBuilder pb = new ProcessBuilder();
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.addAll(List.of(args));
            pb.command(cmd);
            pb.directory(repo.toFile());
            Process process = pb.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git command failed: " + String.join(" ", cmd));
            }
            return lines;
        } catch (Exception e) {
            throw new IllegalStateException("failed to run git command", e);
        }
    }

    private String escapeFilter(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
