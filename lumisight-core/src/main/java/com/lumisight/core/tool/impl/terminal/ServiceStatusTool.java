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
public class ServiceStatusTool implements PermissionedAgentTool<ServiceStatusTool.Args> {

    public record Args(
            @ToolArg(description = "服务标识", required = true, example = "lumisight-api") String serviceId
    ) {
    }

    private final LocalEnvironmentService environmentService;

    public ServiceStatusTool(LocalEnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public String toolName() {
        return "serviceStatus";
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
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "查看受控后台服务的运行状态、pid、日志文件和启动命令。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            Map<String, Object> result = environmentService.serviceStatus(root, args.serviceId());
            return List.of(new AgentContextItem("local", "serviceStatus", "已查询服务状态", result));
        } catch (Exception e) {
            return List.of(new AgentContextItem("tool_error", "serviceStatus", "serviceStatus 执行失败: " + e.getMessage(), Map.of()));
        }
    }
}
