package com.lumisight.core.agent.tool.impl;

import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.port.SourceCodeLookupProvider;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MethodSourceLookupTool implements PermissionedAgentTool {

    private final SourceCodeLookupProvider sourceCodeLookupProvider;

    public MethodSourceLookupTool(SourceCodeLookupProvider sourceCodeLookupProvider) {
        this.sourceCodeLookupProvider = sourceCodeLookupProvider;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.METHOD_SOURCE_READ;
    }

    @Tool(description = "根据方法节点信息读取源码片段。输入 sourceFile/startLine/endLine，返回对应源码行内容。适用于图谱节点定位后回查真实实现。")
    public List<AgentContextItem> fetchMethodSourceByLocation(
            @ToolParam(description = "源码相对路径，例如 src/main/java/com/x/Foo.java") String sourceFile,
            @ToolParam(description = "起始行号（方法节点startLine）") Integer startLine,
            @ToolParam(description = "结束行号（方法节点endLine）") Integer endLine
    ) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        return sourceCodeLookupProvider.lookupMethodSource(context.repoRoot(), sourceFile, startLine, endLine);
    }
}
