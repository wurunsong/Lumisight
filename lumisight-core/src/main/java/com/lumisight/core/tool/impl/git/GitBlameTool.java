package com.lumisight.core.tool.impl.git;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.common.exec.SandboxAccessSpec;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.sandbox.SandboxCommandRunner;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GitBlameTool implements PermissionedAgentTool<GitBlameTool.Args> {

    public record Args(
            @ToolArg(description = "文件相对路径", required = true, example = "README.md") String sourceFile,
            @ToolArg(description = "起始行", example = "1") Integer startLine,
            @ToolArg(description = "结束行", example = "20") Integer endLine
    ) {
    }

    private final SandboxCommandRunner commandRunner;

    public GitBlameTool(SandboxCommandRunner commandRunner) {
        this.commandRunner = commandRunner;
    }

    @Override
    public String toolName() {
        return "gitBlame";
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
        return "按行查看文件 blame 信息，定位某段代码最后由谁在什么时候修改。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        ToolRuntimeScope.Context context = ToolRuntimeScope.required();
        Path root = GitRepoPathSupport.requireRepoRoot(context.repoRoot());
        String sourceFile = args.sourceFile();
        Integer startLine = args.startLine();
        Integer endLine = args.endLine();

        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("blame");
        cmd.add("-w");
        if (startLine != null || endLine != null) {
            int from = startLine == null ? 1 : Math.max(1, startLine);
            int to = endLine == null ? from + Math.max(20, defaultLimit) : Math.max(from, endLine);
            cmd.add("-L");
            cmd.add(from + "," + to);
        }
        cmd.add("--");
        cmd.add(sourceFile);

        Path targetFile = GitRepoPathSupport.resolveInRepo(root, sourceFile);
        List<String> readablePaths = new ArrayList<>();
        readablePaths.add(root.resolve(".git").toString());
        readablePaths.add(targetFile.toString());
        readablePaths.addAll(GitRepoPathSupport.readableGitConfigPaths());
        Map<String, Object> result = commandRunner.run(root, cmd, new SandboxAccessSpec(
                "gitBlame",
                false,
                readablePaths,
                List.of(),
                List.of(),
                List.of("git blame is scoped to the requested file, repository metadata, and global git config")
        ));
        return List.of(new AgentContextItem(
                "git",
                "blame",
                "git blame 执行完成",
                result
        ));
    }
}
