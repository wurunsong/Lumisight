package com.lumisight.core.agent.tool.impl.lsp;

import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.ToolArgumentSpec;
import com.lumisight.core.agent.support.ToolArgumentValidators;
import com.lumisight.core.agent.tool.AgentToolCategory;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class JavaFindReferencesTool implements PermissionedAgentTool {

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
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("symbol", "string", true, "要检索引用的符号名"),
                new ToolArgumentSpec("limit", "integer", false, "最多返回引用条数")
        );
    }

    @Override
    public List<String> validateArgs(Map<String, Object> args) {
        return ToolArgumentValidators.requireText(args, "symbol", "symbol");
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        String symbol = String.valueOf(args.get("symbol"));
        int limit = intValue(args.get("limit"), defaultLimit <= 0 ? 100 : defaultLimit);
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
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
