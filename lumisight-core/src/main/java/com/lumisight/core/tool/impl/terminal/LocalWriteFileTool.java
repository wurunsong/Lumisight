package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.sandbox.SnapshotManager;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LocalWriteFileTool implements PermissionedAgentTool<LocalWriteFileTool.Args> {

    public record Args(
            @ToolArg(description = "文件相对路径", required = true, example = "src/main/java/com/example/Foo.java") String sourceFile,
            @ToolArg(description = "写入内容", required = true, example = "public class Foo {}") String content,
            @ToolArg(description = "写入模式 overwrite/append", example = "overwrite") String mode
    ) {
    }

    private final SnapshotManager snapshotManager;

    public LocalWriteFileTool(SnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    @Override
    public String toolName() {
        return "writeRepoFile";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.LOCAL;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.LOCAL_FS_WRITE;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "写入或覆盖仓库内文件内容，可创建新文件或修改已有文件，属于高风险写操作。";
    }

    @Override
    public List<String> validateArgs(Args args) {
        List<String> errors = new ArrayList<>();
        String mode = modeOrDefault(args.mode());
        if (!"overwrite".equals(mode) && !"append".equals(mode)) {
            errors.add("mode 仅支持 overwrite 或 append");
        }
        return errors;
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        try {
            ToolRuntimeScope.Context context = ToolRuntimeScope.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            String sourceFile = args.sourceFile();
            String content = args.content();
            String mode = modeOrDefault(args.mode());
            Path file = LocalRepoPathSupport.resolveInRepo(root, sourceFile);
            String snapshotId = snapshotManager.snapshotBeforeWrite(root, file);
            if (file.getParent() != null && !Files.exists(file.getParent())) {
                Files.createDirectories(file.getParent());
            }
            if ("append".equals(mode)) {
                Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            return List.of(new AgentContextItem(
                    "local",
                    "writeRepoFile",
                    "写入完成",
                    Map.of("sourceFile", sourceFile, "mode", mode, "length", content.length(), "snapshotId", snapshotId)
            ));
        } catch (Exception e) {
            return List.of(new AgentContextItem(
                    "tool_error",
                    "writeRepoFile",
                    "写入失败: " + e.getMessage(),
                    Map.of()
            ));
        }
    }

    private String modeOrDefault(String mode) {
        return mode == null ? "overwrite" : mode.toLowerCase();
    }
}
