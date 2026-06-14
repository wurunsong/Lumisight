package com.lumisight.core.tool.impl;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.port.SourceCodeLookupProvider;
import com.lumisight.core.service.SourceCodeLookupProviderImpl;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MethodSourceLookupTool implements PermissionedAgentTool<MethodSourceLookupTool.Args> {

    public record Args(
            @ToolArg(description = "源码相对路径", required = true, example = "src/main/java/com/example/Foo.java") String sourceFile,
            @ToolArg(description = "起始行号", required = true, example = "42") Integer startLine,
            @ToolArg(description = "结束行号", required = true, example = "88") Integer endLine
    ) {
    }

    private final SourceCodeLookupProvider sourceCodeLookupProvider;

    public MethodSourceLookupTool(@Autowired(required = false) SourceCodeLookupProvider sourceCodeLookupProvider) {
        this.sourceCodeLookupProvider = sourceCodeLookupProvider == null
                ? new SourceCodeLookupProviderImpl()
                : sourceCodeLookupProvider;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.METHOD_SOURCE_READ;
    }

    @Override
    public String toolName() {
        return "fetchMethodSourceByLocation";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.SOURCE;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "根据 sourceFile、startLine、endLine 读取方法对应源码片段，适合从图谱或定位信息回查真实实现。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        return fetchMethodSourceByLocation(args.sourceFile(), args.startLine(), args.endLine());
    }

    @Tool(description = "根据方法节点信息读取源码片段。输入 sourceFile/startLine/endLine，返回对应源码行内容。适用于图谱节点定位后回查真实实现。")
    public List<AgentContextItem> fetchMethodSourceByLocation(
            @ToolParam(description = "源码相对路径，例如 src/main/java/com/x/Foo.java") String sourceFile,
            @ToolParam(description = "起始行号（方法节点startLine）") Integer startLine,
            @ToolParam(description = "结束行号（方法节点endLine）") Integer endLine
    ) {
        ToolRuntimeScope.Context context = ToolRuntimeScope.required();
        return sourceCodeLookupProvider.lookupMethodSource(context.repoRoot(), sourceFile, startLine, endLine);
    }
}
