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
public class ServiceLogsTool implements PermissionedAgentTool<ServiceLogsTool.Args> {

    public record Args(
            @ToolArg(description = "服务标识", required = true, example = "lumisight-api") String serviceId,
            @ToolArg(description = "返回最新多少行", example = "120") Integer lines
    ) {
    }

    private final LocalEnvironmentService environmentService;

    public ServiceLogsTool(LocalEnvironmentService environmentService) {
        this.environmentService = environmentService;
    }

    @Override
    public String toolName() {
        return "serviceLogs";
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
        return "查看受控后台服务日志的最新若干行，适合快速检查启动结果和报错信息。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            Map<String, Object> result = environmentService.serviceLogs(root, args.serviceId(), args.lines());
            return List.of(new AgentContextItem("local", "serviceLogs", "已读取服务日志", result));
        } catch (Exception e) {
            return List.of(new AgentContextItem("tool_error", "serviceLogs", "serviceLogs 执行失败: " + e.getMessage(), Map.of()));
        }
    }
}
