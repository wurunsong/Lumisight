package com.lumisight.core.agent.tool.impl;

import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.port.CodeVectorContextProvider;
import com.lumisight.core.agent.tool.AgentToolCategory;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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

    @Override
    public String toolName() {
        return "searchCodeVector";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.RAG;
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        String query = args.get("query") == null ? "" : String.valueOf(args.get("query"));
        Integer limit = parseLimit(args.get("limit"), defaultLimit);
        return searchCodeVector(query, limit);
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

    private Integer parseLimit(Object value, int defaultLimit) {
        if (value == null) {
            return defaultLimit;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }
}
