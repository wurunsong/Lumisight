package com.lumisight.core.agent.service;

import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.port.CodeVectorContextProvider;
import com.lumisight.core.agent.port.CommentVectorContextProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class VectorSearchTools {

    private final CodeVectorContextProvider codeVectorContextProvider;
    private final CommentVectorContextProvider commentVectorContextProvider;

    public VectorSearchTools(
            CodeVectorContextProvider codeVectorContextProvider,
            CommentVectorContextProvider commentVectorContextProvider
    ) {
        this.codeVectorContextProvider = codeVectorContextProvider;
        this.commentVectorContextProvider = commentVectorContextProvider;
    }

    @Tool(description = "在代码向量库中检索上下文。适用于包含代码片段、报错堆栈、类名/方法名/字段名、调用链、实现细节的问题。若输入偏代码语义，应优先调用该工具。")
    public List<AgentContextItem> searchCodeVector(
            @ToolParam(description = "代码仓绝对路径，例如 /Users/xxx/project") String repoRoot,
            @ToolParam(description = "代码导向查询内容，可直接传用户问题或代码片段") String query,
            @ToolParam(description = "最多返回多少条上下文，建议 3-10") Integer limit
    ) {
        return codeVectorContextProvider.retrieveByCode(repoRoot, query, limit);
    }

    @Tool(description = "在注释/文档向量库中检索上下文。适用于自然语言问题，例如概念解释、行为说明、设计意图、模块职责、架构理解。若输入偏业务或说明语义，应优先调用该工具。")
    public List<AgentContextItem> searchCommentVector(
            @ToolParam(description = "代码仓绝对路径，例如 /Users/xxx/project") String repoRoot,
            @ToolParam(description = "自然语言查询内容，通常直接传用户问题") String query,
            @ToolParam(description = "最多返回多少条上下文，建议 3-10") Integer limit
    ) {
        return commentVectorContextProvider.retrieveByComment(repoRoot, query, limit);
    }
}
