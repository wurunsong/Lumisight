package com.lumisight.mcp.capability;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

@Component
public class PrintRepoRootCapability implements McpCapability {

    @Override
    public String name() {
        return "pwd";
    }

    @Override
    public String description() {
        return "返回当前会话的仓库根目录（repoRoot）。";
    }

    @Override
    public Map<String, Object> invoke(Map<String, Object> args) {
        try {
            String repoRoot = args.get("repoRoot") == null ? "" : String.valueOf(args.get("repoRoot"));
            Path root = RepoPathGuards.requireRepoRoot(repoRoot);
            return Map.of(
                    "status", "ok",
                    "repoRoot", root.toString()
            );
        } catch (IllegalArgumentException e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }
}
