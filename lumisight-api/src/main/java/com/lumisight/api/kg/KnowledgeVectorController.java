package com.lumisight.api.kg;

import com.lumisight.tools.kg.service.CodeChunkIngestCommand;
import com.lumisight.tools.kg.service.SymbolDocIngestCommand;
import com.lumisight.tools.kg.service.VectorIngestResult;
import com.lumisight.tools.kg.service.VectorIngestService;
import lombok.extern.slf4j.Slf4j;
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
