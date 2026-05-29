package com.lumisight.mcp.capability;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class ListRepoFilesCapability implements McpCapability {

    @Override
    public String name() {
        return "listRepoFiles";
    }

    @Override
    public String description() {
        return "根据 repoRoot 列出仓库文件，可按 filePattern 过滤。";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> args) {
        String repoRoot = value(args.get("repoRoot"));
        String filePattern = value(args.get("filePattern"));
        int limit = intValue(args.get("limit"), 50);
        if (!StringUtils.hasText(repoRoot)) {
            return Map.of("status", "error", "message", "repoRoot 不能为空");
        }

        Path root = Path.of(repoRoot).normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return Map.of("status", "error", "message", "repoRoot 不存在或不是目录");
        }

        try (Stream<Path> stream = Files.walk(root)) {
            List<String> files = stream
                    .filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString())
                    .filter(path -> !StringUtils.hasText(filePattern) || path.contains(filePattern))
                    .limit(limit)
                    .toList();
            return Map.of(
                    "status", "ok",
                    "files", files,
                    "count", files.size()
            );
        } catch (IOException e) {
            return Map.of("status", "error", "message", "文件遍历失败: " + e.getMessage());
        }
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
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
