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
public class ServiceStopTool implements PermissionedAgentTool<ServiceStopTool.Args> {

    public record Args(
            @ToolArg(description = "服务标识", required = true, example = "lumisight-api") String serviceId
    ) {
    }

    private final LocalEnvironmentService environmentService;

    public ServiceStopTool(LocalEnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public String toolName() {
        return "serviceStop";
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
        return "停止由 serviceStart 启动并托管的后台服务，避免本地遗留进程持续占用端口。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            Map<String, Object> result = environmentService.stopService(root, args.serviceId());
            return List.of(new AgentContextItem("local", "serviceStop", "已执行服务停止", result));
        } catch (Exception e) {
            return List.of(new AgentContextItem("tool_error", "serviceStop", "serviceStop 执行失败: " + e.getMessage(), Map.of()));
        }
    }
}
