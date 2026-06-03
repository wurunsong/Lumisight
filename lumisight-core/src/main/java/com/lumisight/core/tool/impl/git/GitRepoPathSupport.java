package com.lumisight.core.tool.impl.git;

import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    static Path resolveInRepo(Path repoRoot, String relativePath) {
        if (!StringUtils.hasText(relativePath)) {
            return repoRoot;
        }
        Path resolved = repoRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(repoRoot)) {
            throw new IllegalArgumentException("路径越界，不允许访问仓库外部");
        }
        return resolved;
    }

    static List<String> readableGitConfigPaths() {
        String userHome = System.getProperty("user.home");
        if (!StringUtils.hasText(userHome)) {
            return List.of();
        }
        Path home = Path.of(userHome).toAbsolutePath().normalize();
        List<String> paths = new ArrayList<>();
        paths.add(home.resolve(".gitconfig").toString());
        paths.add(home.resolve(".config").resolve("git").toString());
        return List.copyOf(paths);
    }
}
