package com.lumisight.mcp.capability;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class ListRepoDirectoryCapability implements McpCapability {

    @Override
    public String name() {
        return "ls";
    }

    @Override
    public String description() {
        return "列出 repoRoot 下目录内容。参数: path(可选), limit(可选,默认200)。";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> args) {
        try {
            String repoRoot = args.get("repoRoot") == null ? "" : String.valueOf(args.get("repoRoot"));
            String pathArg = args.get("path") == null ? "" : String.valueOf(args.get("path"));
            int limit = intValue(args.get("limit"), 200);

            Path root = RepoPathGuards.requireRepoRoot(repoRoot);
            Path target = RepoPathGuards.resolveInRepo(root, pathArg);
            if (!Files.exists(target) || !Files.isDirectory(target)) {
                return Map.of("status", "error", "message", "目录不存在: " + pathArg);
            }
            try (Stream<Path> stream = Files.list(target)) {
                List<Map<String, Object>> entries = stream
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .limit(limit)
                        .map(path -> Map.<String, Object>of(
                                "name", path.getFileName().toString(),
                                "type", Files.isDirectory(path) ? "dir" : "file",
                                "path", root.relativize(path).toString()
                        ))
                        .toList();
                return Map.of(
                        "status", "ok",
                        "path", root.relativize(target).toString(),
                        "entries", entries,
                        "count", entries.size()
                );
            }
        } catch (IllegalArgumentException e) {
            return Map.of("status", "error", "message", e.getMessage());
        } catch (Exception e) {
            return Map.of("status", "error", "message", "ls 执行失败: " + e.getMessage());
        }
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
