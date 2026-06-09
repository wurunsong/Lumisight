package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.ambient.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class LocalCatTool implements PermissionedAgentTool<LocalCatTool.Args> {

    public record Args(
            @ToolArg(description = "文件相对路径", required = true, example = "README.md") String sourceFile,
            @ToolArg(description = "起始行", example = "1") Integer startLine,
            @ToolArg(description = "结束行", example = "40") Integer endLine,
            @ToolArg(description = "最大返回行数", example = "300") Integer maxLines
    ) {
    }

    @Override
    public String toolName() {
        return "cat";
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
        return "读取仓库内单个文件内容，支持按起止行截取源码片段，适合定位实现细节和查看局部上下文。";
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            String sourceFile = args.sourceFile();
            Integer startLine = args.startLine();
            Integer endLine = args.endLine();
            int maxLines = args.maxLines() == null ? 300 : args.maxLines();
            Path file = LocalRepoPathSupport.resolveInRepo(root, sourceFile);
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return error("cat", "文件不存在: " + sourceFile);
            }
            List<String> lines = Files.readAllLines(file);
            int from = startLine == null ? 1 : Math.max(1, startLine);
            int to = endLine == null ? Math.min(lines.size(), from + maxLines - 1) : Math.min(lines.size(), endLine);
            if (to < from) {
                return error("cat", "行号区间非法");
            }
            StringBuilder content = new StringBuilder();
            for (int i = from; i <= to; i++) {
                content.append(i).append(": ").append(lines.get(i - 1)).append("\n");
            }
            return List.of(new AgentContextItem(
                    "local",
                    "cat",
                    content.toString(),
                    Map.of("sourceFile", sourceFile, "lineRange", from + "-" + to)
            ));
        } catch (Exception e) {
            return error("cat", "cat 执行失败: " + e.getMessage());
        }
    }

    private List<AgentContextItem> error(String sourceId, String message) {
        return List.of(new AgentContextItem("tool_error", sourceId, message, Map.of()));
    }
}
