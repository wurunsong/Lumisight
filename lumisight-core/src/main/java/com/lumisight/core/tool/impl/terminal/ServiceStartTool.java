package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
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
public class ServiceStartTool implements PermissionedAgentTool<ServiceStartTool.Args> {

    public record Args(
            @ToolArg(description = "服务标识，用于后续 status/logs/stop", required = true, example = "lumisight-api") String serviceId,
            @ToolArg(description = "相对 repoRoot 的工作目录", example = "lumisight-api") String path,
            @ToolArg(description = "启动命令，首个可执行文件仅允许 mvn/npm/pnpm/yarn/java/node/bash/sh", required = true, example = "[\"mvn\",\"-pl\",\"lumisight-api\",\"-am\",\"spring-boot:run\"]") List<String> command
    ) {
    }

    private final LocalEnvironmentService environmentService;

    public ServiceStartTool(LocalEnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public String toolName() {
        return "serviceStart";
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
        return "在仓库内启动受控后台服务，自动记录 pid 与日志文件，适合本地启动 spring boot、npm dev 或脚本服务。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        try {
            ToolRuntimeScope.Context context = ToolRuntimeScope.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            Map<String, Object> result = environmentService.startService(root, args.serviceId(), args.path(), args.command(), Map.of());
            return List.of(new AgentContextItem("local", "serviceStart", "服务启动命令执行完成", result));
        } catch (Exception e) {
            return List.of(new AgentContextItem("tool_error", "serviceStart", "serviceStart 执行失败: " + e.getMessage(), Map.of()));
        }
    }
}
