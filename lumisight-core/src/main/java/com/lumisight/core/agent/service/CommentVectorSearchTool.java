package com.lumisight.core.agent.service;

import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.port.CommentVectorContextProvider;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CommentVectorSearchTool implements PermissionedAgentTool {

    private final CommentVectorContextProvider commentVectorContextProvider;

    public CommentVectorSearchTool(CommentVectorContextProvider commentVectorContextProvider) {
        this.commentVectorContextProvider = commentVectorContextProvider;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.COMMENT_VECTOR_READ;
    }

    @Tool(description = "在注释/文档向量库中检索上下文。适用于自然语言问题，例如概念解释、行为说明、设计意图、模块职责、架构理解。若输入偏业务或说明语义，应优先调用该工具。")
    public List<AgentContextItem> searchCommentVector(
            @ToolParam(description = "自然语言查询内容，通常直接传用户问题") String query,
            @ToolParam(description = "最多返回多少条上下文，建议 3-10") Integer limit
    ) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        int finalLimit = limit == null ? context.defaultLimit() : limit;
        return commentVectorContextProvider.retrieveByComment(context.repoRoot(), query, finalLimit);
    }
}
