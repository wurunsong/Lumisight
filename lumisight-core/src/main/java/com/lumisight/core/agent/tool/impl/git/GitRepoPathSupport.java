package com.lumisight.core.agent.tool.impl.git;

import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;

final class GitRepoPathSupport {

    private GitRepoPathSupport() {
    }

    static Path requireRepoRoot(String repoRoot) {
        if (!StringUtils.hasText(repoRoot)) {
            throw new IllegalArgumentException("repoRoot 不能为空");
        }
        Path root = Path.of(repoRoot).toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("repoRoot 不存在或不是目录");
        }
        return root;
    }
}
