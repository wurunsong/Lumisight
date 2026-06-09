package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.ambient.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.NoToolArgs;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class LocalPwdTool implements PermissionedAgentTool<NoToolArgs> {

    @Override
    public String toolName() {
        return "pwd";
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
    public Class<NoToolArgs> argsType() {
        return NoToolArgs.class;
    }

    @Override
    public String description() {
        return "返回当前工具运行使用的仓库根目录，适合确认执行上下文和相对路径基准。";
    }

    @Override
    public List<AgentContextItem> invoke(NoToolArgs args, int defaultLimit) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
        return List.of(new AgentContextItem(
                "local",
                "pwd",
                root.toString(),
                Map.of("repoRoot", root.toString())
        ));
    }
}
