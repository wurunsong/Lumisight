package com.lumisight.core.tool.impl.lsp;

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
import java.util.regex.Pattern;

@Component
public class JavaFindReferencesTool implements PermissionedAgentTool<JavaFindReferencesTool.Args> {

    public record Args(
            @ToolArg(description = "要检索引用的符号名", required = true, example = "AgentSessionDispatcher") String symbol,
            @ToolArg(description = "最多返回引用条数", example = "20") Integer limit
    ) {
    }

    @Override
    public String toolName() {
        return "javaFindReferences";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.LSP;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.LSP_JAVA_READ;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "基于 Java 语言服务查找符号引用位置，适合分析调用方和影响范围。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        String symbol = args.symbol();
        int limit = args.limit() == null ? (defaultLimit <= 0 ? 100 : defaultLimit) : args.limit();
        ToolRuntimeScope.Context context = ToolRuntimeScope.required();
        try {
            Path root = JavaLspPathSupport.requireRepoRoot(context.repoRoot());
            Pattern refPattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
            List<Map<String, Object>> refs = new ArrayList<>();
            try (var stream = JavaLspPathSupport.javaFiles(root)) {
                List<Path> files = stream.toList();
                for (Path file : files) {
                    if (refs.size() >= limit) {
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
                        String trimmed = line.trim();
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) {
                            continue;
                        }
                        if (refPattern.matcher(line).find()) {
                            refs.add(Map.of(
                                    "file", root.relativize(file).toString(),
                                    "line", i + 1,
                                    "content", line
                            ));
                            if (refs.size() >= limit) {
                                break;
                            }
                        }
                    }
                }
            }
            return List.of(new AgentContextItem(
                    "lsp_java",
                    "references",
                    "references 查询完成",
                    Map.of("symbol", symbol, "references", refs, "count", refs.size())
            ));
        } catch (Exception e) {
            return List.of(new AgentContextItem(
                    "tool_error",
                    "javaFindReferences",
                    "references 查询失败: " + e.getMessage(),
                    Map.of("symbol", symbol)
            ));
        }
    }
}
