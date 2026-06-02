package com.lumisight.core.tool.impl.git;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.sandbox.SandboxCommandRunner;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class GitStatusTool implements PermissionedAgentTool<GitStatusTool.Args> {

    public record Args(
            @ToolArg(description = "是否使用短格式", example = "true") Boolean shortFormat
    ) {
    }

    private final SandboxCommandRunner commandRunner;

    public GitStatusTool(SandboxCommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    @Override
    public String toolName() {
        return "gitStatus";
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
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "查看仓库当前 git 状态，了解哪些文件被修改、暂存或未跟踪。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        Path root = GitRepoPathSupport.requireRepoRoot(context.repoRoot());
        boolean shortFormat = args.shortFormat() == null || args.shortFormat();
        List<String> cmd = shortFormat
                ? List.of("git", "status", "--short", "--branch")
                : List.of("git", "status");
        Map<String, Object> result = commandRunner.run(root, cmd);
        return List.of(new AgentContextItem(
                "git",
                "status",
                "git status 执行完成",
                result
        ));
    }
}
