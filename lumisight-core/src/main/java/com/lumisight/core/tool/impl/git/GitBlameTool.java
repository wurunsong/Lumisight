package com.lumisight.core.tool.impl.git;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.support.ToolArgumentValidators;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GitBlameTool implements PermissionedAgentTool {

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
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("sourceFile", "string", true, "文件相对路径"),
                new ToolArgumentSpec("startLine", "integer", false, "起始行"),
                new ToolArgumentSpec("endLine", "integer", false, "结束行")
        );
    }

    @Override
    public List<String> validateArgs(Map<String, Object> args) {
        return ToolArgumentValidators.requireText(args, "sourceFile", "sourceFile");
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        Path root = GitRepoPathSupport.requireRepoRoot(context.repoRoot());
        String sourceFile = String.valueOf(args.get("sourceFile"));
        Integer startLine = intValue(args.get("startLine"));
        Integer endLine = intValue(args.get("endLine"));

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

        Map<String, Object> result = GitCommandSupport.run(root, cmd);
        return List.of(new AgentContextItem(
                "git",
                "blame",
                "git blame 执行完成",
                result
        ));
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }
}
