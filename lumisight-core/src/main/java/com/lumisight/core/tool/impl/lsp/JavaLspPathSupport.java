package com.lumisight.core.tool.impl.lsp;

import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

final class JavaLspPathSupport {

    private JavaLspPathSupport() {
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

    static Stream<Path> javaFiles(Path repoRoot) throws Exception {
        return Files.walk(repoRoot)
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".java"));
    }
}
