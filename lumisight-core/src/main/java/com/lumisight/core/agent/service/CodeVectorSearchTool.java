package com.lumisight.core.agent.service;

import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.port.CodeVectorContextProvider;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CodeVectorSearchTool implements PermissionedAgentTool {

    private final CodeVectorContextProvider codeVectorContextProvider;

    public CodeVectorSearchTool(CodeVectorContextProvider codeVectorContextProvider) {
        this.codeVectorContextProvider = codeVectorContextProvider;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.CODE_VECTOR_READ;
    }

    @Tool(description = "在代码向量库中检索上下文。适用于包含代码片段、报错堆栈、类名/方法名/字段名、调用链、实现细节的问题。若输入偏代码语义，应优先调用该工具。")
    public List<AgentContextItem> searchCodeVector(
            @ToolParam(description = "代码导向查询内容，可直接传用户问题或代码片段") String query,
            @ToolParam(description = "最多返回多少条上下文，建议 3-10") Integer limit
    ) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        int finalLimit = limit == null ? context.defaultLimit() : limit;
        return codeVectorContextProvider.retrieveByCode(context.repoRoot(), query, finalLimit);
    }
}
