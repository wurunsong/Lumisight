package com.lumisight.core.agent.tool.impl;

import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.port.KnowledgeGraphOneHopProvider;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeGraphOneHopTool implements PermissionedAgentTool {

    private final KnowledgeGraphOneHopProvider knowledgeGraphOneHopProvider;

    public KnowledgeGraphOneHopTool(KnowledgeGraphOneHopProvider knowledgeGraphOneHopProvider) {
        this.knowledgeGraphOneHopProvider = knowledgeGraphOneHopProvider;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.KG_ONE_HOP_READ;
    }

    @Tool(description = "根据知识图谱节点ID查询一跳邻接信息。输入来自注释文档向量召回的kgNodeId，返回中心节点及其一跳相邻边，用于补充结构化依赖关系。")
    public List<AgentContextItem> fetchOneHopByKgNodeId(
            @ToolParam(description = "知识图谱节点ID（通常来自注释文档向量的kg_node_id）") String kgNodeId,
            @ToolParam(description = "最多返回多少条相邻边，建议 20-200") Integer limit
    ) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        int finalLimit = limit == null ? context.defaultLimit() : limit;
        return knowledgeGraphOneHopProvider.retrieveByNodeId(context.repoRoot(), kgNodeId, finalLimit);
    }
}
