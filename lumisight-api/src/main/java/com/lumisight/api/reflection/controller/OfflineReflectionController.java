package com.lumisight.api.reflection.controller;

import com.lumisight.api.reflection.dto.OfflineReflectionResponse;
import com.lumisight.api.reflection.dto.OfflineReflectionRunRequest;
import com.lumisight.api.reflection.support.OfflineReflectionService;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;

@RestController
@RequestMapping("/api/lumisight/reflection")
public class OfflineReflectionController {

    private final OfflineReflectionService offlineReflectionService;

    public OfflineReflectionController(OfflineReflectionService offlineReflectionService) {
        this.offlineReflectionService = offlineReflectionService;
    }

    @PostMapping("/offline-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public OfflineReflectionResponse run(@RequestBody(required = false) OfflineReflectionRunRequest request) {
        String repoRoot = normalizeRepoRoot(request == null ? null : request.repoRoot());
        String userId = normalizeUserId(request == null ? null : request.userId());
        return offlineReflectionService.run(repoRoot, userId, request);
    }

    private String normalizeRepoRoot(String repoRoot) {
        if (StringUtils.hasText(repoRoot)) {
            return repoRoot.trim();
        }
        return Path.of("").toAbsolutePath().normalize().toString();
    }

    private String normalizeUserId(String userId) {
        if (StringUtils.hasText(userId)) {
            return userId.trim();
        }
        return "debug-user";
    }
}
