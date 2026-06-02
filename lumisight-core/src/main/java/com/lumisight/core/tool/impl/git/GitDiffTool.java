package com.lumisight.core.tool.impl.git;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.sandbox.SandboxCommandRunner;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GitDiffTool implements PermissionedAgentTool {

    private final SandboxCommandRunner commandRunner;

    public GitDiffTool(SandboxCommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    @Override
    public String toolName() {
        return "gitDiff";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.GIT;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.GIT_READ;
    }

    @Override
    public String description() {
        return "查看当前仓库工作区或暂存区的 git diff，适合核对本地改动内容。";
    }

    @Override
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("sourceFile", "string", false, "文件相对路径"),
                new ToolArgumentSpec("staged", "boolean", false, "是否查看暂存区")
        );
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        Path root = GitRepoPathSupport.requireRepoRoot(context.repoRoot());
        boolean staged = boolValue(args.get("staged"), false);
        String sourceFile = args.get("sourceFile") == null ? "" : String.valueOf(args.get("sourceFile"));
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("diff");
        if (staged) {
            cmd.add("--cached");
        }
        if (!sourceFile.isBlank()) {
            cmd.add("--");
            cmd.add(sourceFile);
        }
        Map<String, Object> result = commandRunner.run(root, cmd);
        return List.of(new AgentContextItem(
                "git",
                "diff",
                "git diff 执行完成",
                result
        ));
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
}
