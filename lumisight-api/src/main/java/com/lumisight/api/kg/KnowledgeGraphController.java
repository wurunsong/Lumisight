package com.lumisight.api.kg;

import com.lumisight.tools.kg.service.KnowledgeGraphBuildResult;
import com.lumisight.tools.kg.service.KnowledgeGraphBuildService;
import com.lumisight.tools.kg.service.KnowledgeGraphViewResult;
import com.lumisight.tools.kg.service.KnowledgeGraphViewService;
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
@RequestMapping("/api/kg")
@Slf4j
public class KnowledgeGraphController {

    private final KnowledgeGraphBuildService buildService;
    private final KnowledgeGraphViewService viewService;

    public KnowledgeGraphController(KnowledgeGraphBuildService buildService, KnowledgeGraphViewService viewService) {
        this.buildService = buildService;
        this.viewService = viewService;
    }

    @PostMapping("/build")
    @ResponseStatus(HttpStatus.OK)
    public KnowledgeGraphBuildResult build(@RequestBody KnowledgeGraphBuildRequest request) {
        log.info("Received KG build request, repoRoot={}", request.repoRoot());
        if (!StringUtils.hasText(request.repoRoot())) {
            log.warn("Reject KG build request: repoRoot is empty");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoRoot is required");
        }
        KnowledgeGraphBuildResult result = buildService.build(request.repoRoot());
        log.info("KG build request done, updated={}, gitBranch={}, gitCommit={}, nodes={}, edges={}",
                result.updated(), result.gitBranch(), result.gitCommit(), result.nodeCount(), result.edgeCount());
        return result;
    }

    @PostMapping("/view")
    @ResponseStatus(HttpStatus.OK)
    public KnowledgeGraphViewResult view(@RequestBody KnowledgeGraphViewRequest request) {
        log.info("Received KG view request, repoRoot={}, nodeLimit={}, edgeLimit={}",
                request.repoRoot(), request.nodeLimit(), request.edgeLimit());
        if (!StringUtils.hasText(request.repoRoot())) {
            log.warn("Reject KG view request: repoRoot is empty");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoRoot is required");
        }
        KnowledgeGraphViewResult result = viewService.view(request.repoRoot(), request.nodeLimit(), request.edgeLimit());
        log.info("KG view request done, gitCommit={}, nodes={}, edges={}",
                result.gitCommit(), result.nodeCount(), result.edgeCount());
        return result;
    }
}
