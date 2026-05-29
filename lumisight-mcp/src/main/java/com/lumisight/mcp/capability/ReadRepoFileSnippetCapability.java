package com.lumisight.mcp.capability;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class ReadRepoFileSnippetCapability implements McpCapability {

    @Override
    public String name() {
        return "readRepoFileSnippet";
    }

    @Override
    public String description() {
        return "根据 repoRoot + sourceFile + startLine/endLine 读取源码片段。";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> args) {
        String repoRoot = value(args.get("repoRoot"));
        String sourceFile = value(args.get("sourceFile"));
        Integer startLine = intValue(args.get("startLine"));
        Integer endLine = intValue(args.get("endLine"));
        if (!StringUtils.hasText(repoRoot) || !StringUtils.hasText(sourceFile)) {
            return Map.of("status", "error", "message", "repoRoot 和 sourceFile 不能为空");
        }
        if (startLine == null || endLine == null || startLine <= 0 || endLine < startLine) {
            return Map.of("status", "error", "message", "startLine/endLine 参数非法");
        }

        Path file = Path.of(repoRoot).resolve(sourceFile).normalize();
        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            return Map.of("status", "error", "message", "文件不存在: " + sourceFile);
        }

        try {
            List<String> lines = Files.readAllLines(file);
            int from = Math.max(1, startLine);
            int to = Math.min(lines.size(), endLine);
            if (from > lines.size()) {
                return Map.of("status", "ok", "content", "", "lineRange", from + "-" + to);
            }
            StringBuilder builder = new StringBuilder();
            for (int i = from; i <= to; i++) {
                builder.append(i).append(": ").append(lines.get(i - 1)).append("\n");
            }
            return Map.of(
                    "status", "ok",
                    "content", builder.toString(),
                    "lineRange", from + "-" + to,
                    "sourceFile", sourceFile
            );
        } catch (IOException e) {
            return Map.of("status", "error", "message", "读取文件失败: " + e.getMessage());
        }
    }

    private String value(Object value) {
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
}
