package com.lumisight.core.agent.tool.impl.terminal;

import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.ToolArgumentSpec;
import com.lumisight.core.agent.support.ToolArgumentValidators;
import com.lumisight.core.agent.tool.AgentToolCategory;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

@Component
public class LocalWriteFileTool implements PermissionedAgentTool {

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
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("sourceFile", "string", true, "文件相对路径"),
                new ToolArgumentSpec("content", "string", true, "写入内容"),
                new ToolArgumentSpec("mode", "string", false, "写入模式 overwrite/append")
        );
    }

    @Override
    public List<String> validateArgs(Map<String, Object> args) {
        List<String> errors = ToolArgumentValidators.requireText(args, "sourceFile", "sourceFile");
        errors.addAll(ToolArgumentValidators.requireText(args, "content", "content"));
        String mode = args.get("mode") == null ? "overwrite" : String.valueOf(args.get("mode")).toLowerCase();
        if (!"overwrite".equals(mode) && !"append".equals(mode)) {
            errors.add("mode 仅支持 overwrite 或 append");
        }
        return errors;
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            String sourceFile = String.valueOf(args.get("sourceFile"));
            String content = String.valueOf(args.get("content"));
            String mode = args.get("mode") == null ? "overwrite" : String.valueOf(args.get("mode")).toLowerCase();
            Path file = LocalRepoPathSupport.resolveInRepo(root, sourceFile);
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
                    Map.of("sourceFile", sourceFile, "mode", mode, "length", content.length())
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
}
