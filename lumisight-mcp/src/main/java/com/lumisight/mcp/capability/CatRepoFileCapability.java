package com.lumisight.mcp.capability;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class CatRepoFileCapability implements McpCapability {

    @Override
    public String name() {
        return "cat";
    }

    @Override
    public String description() {
        return "读取 repoRoot 下指定文件内容。参数: sourceFile(必填), startLine/endLine/maxLines(可选)。";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> args) {
        try {
            String repoRoot = stringValue(args.get("repoRoot"));
            String sourceFile = stringValue(args.get("sourceFile"));
            if (!StringUtils.hasText(sourceFile)) {
                return Map.of("status", "error", "message", "sourceFile 不能为空");
            }
            Integer startLine = intValue(args.get("startLine"));
            Integer endLine = intValue(args.get("endLine"));
            int maxLines = intValue(args.get("maxLines"), 300);

            Path root = RepoPathGuards.requireRepoRoot(repoRoot);
            Path file = RepoPathGuards.resolveInRepo(root, sourceFile);
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return Map.of("status", "error", "message", "文件不存在: " + sourceFile);
            }

            List<String> lines = Files.readAllLines(file);
            int from = startLine == null ? 1 : Math.max(startLine, 1);
            int to = endLine == null ? Math.min(lines.size(), from + maxLines - 1) : Math.min(endLine, lines.size());
            if (to < from) {
                return Map.of("status", "error", "message", "行号区间非法");
            }

            StringBuilder content = new StringBuilder();
            for (int i = from; i <= to; i++) {
                content.append(i).append(": ").append(lines.get(i - 1)).append("\n");
            }
            return Map.of(
                    "status", "ok",
                    "sourceFile", sourceFile,
                    "lineRange", from + "-" + to,
                    "content", content.toString()
            );
        } catch (IllegalArgumentException e) {
            return Map.of("status", "error", "message", e.getMessage());
        } catch (Exception e) {
            return Map.of("status", "error", "message", "cat 执行失败: " + e.getMessage());
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private int intValue(Object value, int defaultValue) {
        Integer parsed = intValue(value);
        return parsed == null ? defaultValue : parsed;
    }
}
