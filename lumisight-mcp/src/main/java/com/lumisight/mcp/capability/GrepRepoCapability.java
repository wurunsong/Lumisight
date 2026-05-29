package com.lumisight.mcp.capability;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class GrepRepoCapability implements McpCapability {

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "在 repoRoot 内按关键词检索文本文件，返回命中行。参数: pattern(必填), filePattern(可选), limit(可选), caseSensitive(可选)。";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> args) {
        try {
            String repoRoot = stringValue(args.get("repoRoot"));
            String pattern = stringValue(args.get("pattern"));
            String filePattern = stringValue(args.get("filePattern"));
            int limit = intValue(args.get("limit"), 100);
            boolean caseSensitive = boolValue(args.get("caseSensitive"), false);
            if (!StringUtils.hasText(pattern)) {
                return Map.of("status", "error", "message", "pattern 不能为空");
            }

            Path root = RepoPathGuards.requireRepoRoot(repoRoot);
            String finalPattern = caseSensitive ? pattern : pattern.toLowerCase();
            List<Map<String, Object>> matches = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> !StringUtils.hasText(filePattern) || root.relativize(path).toString().contains(filePattern))
                        .toList();
                for (Path file : files) {
                    if (matches.size() >= limit) {
                        break;
                    }
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(file);
                    } catch (Exception ignored) {
                        continue;
                    }
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String target = caseSensitive ? line : line.toLowerCase();
                        if (target.contains(finalPattern)) {
                            matches.add(Map.of(
                                    "file", root.relativize(file).toString(),
                                    "line", i + 1,
                                    "content", line
                            ));
                            if (matches.size() >= limit) {
                                break;
                            }
                        }
                    }
                }
            }
            return Map.of(
                    "status", "ok",
                    "matches", matches,
                    "count", matches.size()
            );
        } catch (IllegalArgumentException e) {
            return Map.of("status", "error", "message", e.getMessage());
        } catch (IOException e) {
            return Map.of("status", "error", "message", "grep 执行失败: " + e.getMessage());
        }
    }

    private String stringValue(Object value) {
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

    private boolean boolValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
