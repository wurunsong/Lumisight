package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class LocalGrepTool implements PermissionedAgentTool<LocalGrepTool.Args> {

    public record Args(
            @ToolArg(description = "检索关键词", required = true, example = "AgentSessionDispatcher") String pattern,
            @ToolArg(description = "文件名过滤", example = ".java") String filePattern,
            @ToolArg(description = "最大命中条数", example = "20") Integer limit,
            @ToolArg(description = "是否区分大小写", example = "false") Boolean caseSensitive
    ) {
    }

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
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "在仓库文件中搜索关键字或模式，适合快速定位符号、配置项、报错文本和调用点。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        try {
            ToolRuntimeScope.Context context = ToolRuntimeScope.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            String pattern = args.pattern();
            String filePattern = args.filePattern() == null ? "" : args.filePattern();
            boolean caseSensitive = args.caseSensitive() != null && args.caseSensitive();
            int limit = args.limit() == null ? (defaultLimit <= 0 ? 100 : defaultLimit) : args.limit();
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

    private List<AgentContextItem> error(String sourceId, String message) {
        return List.of(new AgentContextItem("tool_error", sourceId, message, Map.of()));
    }
}
