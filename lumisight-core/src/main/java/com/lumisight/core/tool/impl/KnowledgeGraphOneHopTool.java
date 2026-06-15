package com.lumisight.core.tool.impl;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.port.KnowledgeGraphOneHopProvider;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeGraphOneHopTool implements PermissionedAgentTool<KnowledgeGraphOneHopTool.Args> {

    public record Args(
            @ToolArg(description = "图谱节点ID", required = true, example = "method:com.lumisight.core.agent.AgentExecutionEntryService#execute") String kgNodeId,
            @ToolArg(description = "返回条数", example = "50") Integer limit
    ) {
    }

    private final KnowledgeGraphOneHopProvider knowledgeGraphOneHopProvider;

    public KnowledgeGraphOneHopTool(KnowledgeGraphOneHopProvider knowledgeGraphOneHopProvider) {
        this.knowledgeGraphOneHopProvider = knowledgeGraphOneHopProvider;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.KG_ONE_HOP_READ;
    }

    @Override
    public String toolName() {
        return "fetchOneHopByKgNodeId";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.GRAPH;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "根据知识图谱节点ID查询一跳邻接关系，用于补充类、方法、调用链之间的结构化依赖信息。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        Integer limit = args.limit() == null ? defaultLimit : args.limit();
        return fetchOneHopByKgNodeId(args.kgNodeId(), limit);
    }

    @Tool(description = "根据知识图谱节点ID查询一跳邻接信息。输入来自注释文档向量召回的kgNodeId，返回中心节点及其一跳相邻边，用于补充结构化依赖关系。")
    public List<AgentContextItem> fetchOneHopByKgNodeId(
            @ToolParam(description = "知识图谱节点ID（通常来自注释文档向量的kg_node_id）") String kgNodeId,
            @ToolParam(description = "最多返回多少条相邻边，建议 20-200") Integer limit
    ) {
        ToolRuntimeScope.Context context = ToolRuntimeScope.required();
        int finalLimit = limit == null ? context.defaultLimit() : limit;
        return knowledgeGraphOneHopProvider.retrieveByNodeId(context.repoRoot(), kgNodeId, finalLimit);
    }
}
