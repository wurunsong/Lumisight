package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.support.ToolArgumentValidators;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class LocalGrepTool implements PermissionedAgentTool {

    @Override
    public String toolName() {
        return "grep";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.LOCAL;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.LOCAL_FS_READ;
    }

    @Override
    public String description() {
        return "在仓库文件中搜索关键字或模式，适合快速定位符号、配置项、报错文本和调用点。";
    }

    @Override
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("pattern", "string", true, "检索关键词"),
                new ToolArgumentSpec("filePattern", "string", false, "文件名过滤"),
                new ToolArgumentSpec("limit", "integer", false, "最大命中条数"),
                new ToolArgumentSpec("caseSensitive", "boolean", false, "是否区分大小写")
        );
    }

    @Override
    public List<String> validateArgs(Map<String, Object> args) {
        return ToolArgumentValidators.requireText(args, "pattern", "pattern");
    }

    @Override
    public Map<String, Object> exampleArgs() {
        return Map.of(
                "pattern", "AgentSessionDispatcher",
                "filePattern", ".java",
                "limit", 20
        );
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            String pattern = String.valueOf(args.get("pattern"));
            String filePattern = args.get("filePattern") == null ? "" : String.valueOf(args.get("filePattern"));
            boolean caseSensitive = boolValue(args.get("caseSensitive"), false);
            int limit = intValue(args.get("limit"), defaultLimit <= 0 ? 100 : defaultLimit);
            String lookup = caseSensitive ? pattern : pattern.toLowerCase();
            List<Map<String, Object>> matches = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(root)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> filePattern.isBlank() || root.relativize(path).toString().contains(filePattern))
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
                        if (target.contains(lookup)) {
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
            return List.of(new AgentContextItem(
                    "local",
                    "grep",
                    "检索完成",
                    Map.of("pattern", pattern, "count", matches.size(), "matches", matches)
            ));
        } catch (Exception e) {
            return error("grep", "grep 执行失败: " + e.getMessage());
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

    private boolean boolValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private List<AgentContextItem> error(String sourceId, String message) {
        return List.of(new AgentContextItem("tool_error", sourceId, message, Map.of()));
    }
}
