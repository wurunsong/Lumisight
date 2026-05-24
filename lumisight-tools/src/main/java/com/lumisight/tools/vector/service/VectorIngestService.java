package com.lumisight.tools.vector.service;

import com.lumisight.tools.kg.util.HashUtils;
import com.lumisight.tools.vector.model.CodeChunkIngestCommand;
import com.lumisight.tools.vector.model.SymbolDocIngestCommand;
import com.lumisight.tools.vector.model.VectorBatchIngestResult;
import com.lumisight.tools.vector.model.VectorIngestResult;
import com.lumisight.tools.vector.spi.SymbolDocGenerator;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Service
public class VectorIngestService {

    private static final String CODE_CHUNK_COLLECTION = "code_chunk";
    private static final String SYMBOL_DOC_COLLECTION = "symbol_doc";

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
            codeChunkVectorStore.add(docs);
        }
        return new VectorBatchIngestResult(CODE_CHUNK_COLLECTION, repo, sourceFile, docs.size(), ids);
    }

    private String stableDocId(String type, String qualifiedName, String gitCommit, String sourceFile) {
        return type + "_" + HashUtils.sha256Hex(type + "|" + qualifiedName + "|" + gitCommit + "|" + sourceFile);
    }
}
