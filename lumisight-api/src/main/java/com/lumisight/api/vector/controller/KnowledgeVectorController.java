package com.lumisight.api.vector.controller;

import com.lumisight.api.vector.dto.request.CodeChunkIngestRequest;
import com.lumisight.api.vector.dto.request.CodeChunkAutoIngestRequest;
import com.lumisight.api.vector.dto.request.RepoCodeChunkIngestRequest;
import com.lumisight.api.vector.dto.request.SymbolDocIngestRequest;
import com.lumisight.tools.vector.model.CodeChunkIngestCommand;
import com.lumisight.tools.vector.model.RepoCodeChunkIngestResult;
import com.lumisight.tools.vector.model.SymbolDocIngestCommand;
import com.lumisight.tools.vector.model.VectorBatchIngestResult;
import com.lumisight.tools.vector.model.VectorIngestResult;
import com.lumisight.tools.vector.service.VectorIngestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/lumisight/vector")
@Slf4j
@ConditionalOnProperty(prefix = "lumisight.vector", name = "enabled", havingValue = "true")
public class KnowledgeVectorController {

    private final VectorIngestService vectorIngestService;

    public KnowledgeVectorController(VectorIngestService vectorIngestService) {
        this.vectorIngestService = vectorIngestService;
    }

    @PostMapping("/code-chunk/ingest")
    @ResponseStatus(HttpStatus.OK)
    public VectorIngestResult ingestCodeChunk(@RequestBody CodeChunkIngestRequest request) {
        validateRequired(request.repoRoot(), "repoRoot");
        validateRequired(request.sourceFile(), "sourceFile");
        validateRequired(request.qualifiedName(), "qualifiedName");
        validateRequired(request.chunkText(), "chunkText");
        log.info("Received code chunk ingest request, repoRoot={}, sourceFile={}, qualifiedName={}",
                request.repoRoot(), request.sourceFile(), request.qualifiedName());
        return vectorIngestService.ingestCodeChunk(new CodeChunkIngestCommand(
                request.repoRoot(),
                request.sourceFile(),
                request.qualifiedName(),
                request.chunkText(),
                request.startLine(),
                request.endLine(),
                request.gitBranch(),
                request.gitCommit()
        ));
    }

    @PostMapping("/code-chunk/ingest-auto")
    @ResponseStatus(HttpStatus.OK)
    public VectorBatchIngestResult ingestCodeChunkAuto(@RequestBody CodeChunkAutoIngestRequest request) {
        validateRequired(request.repoRoot(), "repoRoot");
        validateRequired(request.sourceFile(), "sourceFile");
        validateRequired(request.qualifiedName(), "qualifiedName");
        validateRequired(request.codeText(), "codeText");
        log.info("Received code chunk auto ingest request, repoRoot={}, sourceFile={}, qualifiedName={}",
                request.repoRoot(), request.sourceFile(), request.qualifiedName());
        return vectorIngestService.ingestCodeChunksAuto(
                request.repoRoot(),
                request.sourceFile(),
                request.qualifiedName(),
                request.codeText(),
                request.maxChunkChars(),
                request.overlapChars(),
                request.gitBranch(),
                request.gitCommit()
        );
    }

    @PostMapping("/code-chunk/ingest-repo")
    @ResponseStatus(HttpStatus.OK)
    public RepoCodeChunkIngestResult ingestRepoCodeChunks(@RequestBody RepoCodeChunkIngestRequest request) {
        validateRequired(request.repoRoot(), "repoRoot");
        log.info("Received repo code chunk ingest request, repoRoot={}", request.repoRoot());
        return vectorIngestService.ingestRepoCodeChunks(
                request.repoRoot(),
                request.maxChunkChars(),
                request.overlapChars(),
                request.gitBranch(),
                request.gitCommit()
        );
    }

    @PostMapping("/symbol-doc/ingest")
    @ResponseStatus(HttpStatus.OK)
    public VectorIngestResult ingestSymbolDoc(@RequestBody SymbolDocIngestRequest request) {
        validateRequired(request.repoRoot(), "repoRoot");
        validateRequired(request.sourceFile(), "sourceFile");
        validateRequired(request.qualifiedName(), "qualifiedName");
        log.info("Received symbol doc ingest request, repoRoot={}, sourceFile={}, qualifiedName={}",
                request.repoRoot(), request.sourceFile(), request.qualifiedName());
        return vectorIngestService.ingestSymbolDoc(new SymbolDocIngestCommand(
                request.repoRoot(),
                request.sourceFile(),
                request.qualifiedName(),
                request.symbolSignature(),
                request.codeContext(),
                request.symbolDocText(),
                request.gitBranch(),
                request.gitCommit()
        ));
    }

    private void validateRequired(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
    }
}
