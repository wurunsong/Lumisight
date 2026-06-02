package com.lumisight.core.tool.impl;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.port.SourceCodeLookupProvider;
import com.lumisight.core.service.SourceCodeLookupProviderImpl;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.support.ToolArgumentValidators;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class MethodSourceLookupTool implements PermissionedAgentTool {

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
    public String description() {
        return "根据 sourceFile、startLine、endLine 读取方法对应源码片段，适合从图谱或定位信息回查真实实现。";
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        return fetchMethodSourceByLocation(
                args.get("sourceFile") == null ? "" : String.valueOf(args.get("sourceFile")),
                parseInteger(args.get("startLine")),
                parseInteger(args.get("endLine"))
        );
    }

    @Override
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("sourceFile", "string", true, "源码相对路径"),
                new ToolArgumentSpec("startLine", "integer", true, "起始行号"),
                new ToolArgumentSpec("endLine", "integer", true, "结束行号")
        );
    }

    @Override
    public List<String> validateArgs(Map<String, Object> args) {
        List<String> errors = ToolArgumentValidators.requireText(args, "sourceFile", "sourceFile");
        errors.addAll(ToolArgumentValidators.requireInteger(args, "startLine", "startLine"));
        errors.addAll(ToolArgumentValidators.requireInteger(args, "endLine", "endLine"));
        return errors;
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

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
