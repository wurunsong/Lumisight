package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.sandbox.SnapshotManager;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class LocalRollbackFileTool implements PermissionedAgentTool<LocalRollbackFileTool.Args> {

    public record Args(
            @ToolArg(description = "writeRepoFile 返回的快照ID", required = true, example = "snapshot-123456") String snapshotId
    ) {
    }

    private final SnapshotManager snapshotManager;

    public LocalRollbackFileTool(SnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    @Override
    public String toolName() {
        return "rollbackRepoFile";
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
        return "根据快照ID回滚文件写入结果，用于撤销之前的本地写操作。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        ToolRuntimeScope.Context context = ToolRuntimeScope.required();
        Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
        Map<String, Object> result = snapshotManager.rollback(root, args.snapshotId());
        return List.of(new AgentContextItem(
                "local",
                "rollbackRepoFile",
                Boolean.TRUE.equals(result.get("success")) ? "回滚完成" : "回滚失败",
                result
        ));
    }
}
