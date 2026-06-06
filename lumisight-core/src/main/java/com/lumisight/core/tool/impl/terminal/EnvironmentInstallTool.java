package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class EnvironmentInstallTool implements PermissionedAgentTool<EnvironmentInstallTool.Args> {

    public record Args(
            @ToolArg(description = "相对 repoRoot 的工作目录", example = "lumisight-api") String path,
            @ToolArg(description = "安装命令，首个可执行文件仅允许 mvn/npm/pnpm/yarn/java/node/bash/sh", required = true, example = "[\"npm\",\"install\"]") List<String> command
    ) {
    }

    private final LocalEnvironmentService environmentService;

    public EnvironmentInstallTool(LocalEnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public String toolName() {
        return "envInstall";
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
        return "在仓库内受控执行依赖安装命令，适合补 npm/pnpm/yarn/maven 依赖或安装浏览器运行时。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            Map<String, Object> result = environmentService.install(root, args.path(), args.command());
            return List.of(new AgentContextItem("local", "envInstall", "依赖安装命令执行完成", result));
        } catch (Exception e) {
            return List.of(new AgentContextItem("tool_error", "envInstall", "envInstall 执行失败: " + e.getMessage(), Map.of()));
        }
    }
}
