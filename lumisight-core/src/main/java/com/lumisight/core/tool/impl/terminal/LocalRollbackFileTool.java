package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.sandbox.SnapshotManager;
import com.lumisight.core.support.ToolArgumentValidators;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class LocalRollbackFileTool implements PermissionedAgentTool {

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
    public String description() {
        return "根据快照ID回滚文件写入结果，用于撤销之前的本地写操作。";
    }

    @Override
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(new ToolArgumentSpec("snapshotId", "string", true, "writeRepoFile 返回的快照ID"));
    }

    @Override
    public List<String> validateArgs(Map<String, Object> args) {
        return ToolArgumentValidators.requireText(args, "snapshotId", "snapshotId");
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        Path root = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
        String snapshotId = String.valueOf(args.get("snapshotId"));
        Map<String, Object> result = snapshotManager.rollback(root, snapshotId);
        return List.of(new AgentContextItem(
                "local",
                "rollbackRepoFile",
                Boolean.TRUE.equals(result.get("success")) ? "回滚完成" : "回滚失败",
                result
        ));
    }
}
