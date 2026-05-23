package com.lumisight.api.kg;

import com.lumisight.tools.kg.service.KnowledgeGraphBuildResult;
import com.lumisight.tools.kg.service.KnowledgeGraphBuildService;
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
public class KnowledgeGraphController {

    private final KnowledgeGraphBuildService buildService;

    public KnowledgeGraphController(KnowledgeGraphBuildService buildService) {
        this.buildService = buildService;
    }

    @PostMapping("/build")
    @ResponseStatus(HttpStatus.OK)
    public KnowledgeGraphBuildResult build(@RequestBody KnowledgeGraphBuildRequest request) {
        // API entry for offline/incremental KG build.
        if (!StringUtils.hasText(request.repoRoot())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repoRoot is required");
        }
        return buildService.build(request.repoRoot());
    }
}
