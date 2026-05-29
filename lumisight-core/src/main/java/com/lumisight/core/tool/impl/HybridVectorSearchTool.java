package com.lumisight.core.tool.impl;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.port.CodeVectorContextProvider;
import com.lumisight.core.port.CommentVectorContextProvider;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class HybridVectorSearchTool implements PermissionedAgentTool {

    private final CodeVectorContextProvider codeVectorContextProvider;
    private final CommentVectorContextProvider commentVectorContextProvider;

    public HybridVectorSearchTool(
            CodeVectorContextProvider codeVectorContextProvider,
            CommentVectorContextProvider commentVectorContextProvider
    ) {
        this.codeVectorContextProvider = codeVectorContextProvider;
        this.commentVectorContextProvider = commentVectorContextProvider;
    }

    @Override
    public String toolName() {
        return "searchHybridVector";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.RAG;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.HYBRID_VECTOR_READ;
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        String codeQuery = args.get("codeQuery") == null ? "" : String.valueOf(args.get("codeQuery"));
        String naturalLanguageQuery = args.get("naturalLanguageQuery") == null ? "" : String.valueOf(args.get("naturalLanguageQuery"));
        Integer limit = parseLimit(args.get("limit"), defaultLimit);
        return searchHybridVector(codeQuery, naturalLanguageQuery, limit);
    }

    @Override
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("codeQuery", "string", false, "代码导向查询"),
                new ToolArgumentSpec("naturalLanguageQuery", "string", false, "自然语言查询"),
                new ToolArgumentSpec("limit", "integer", false, "返回条数")
        );
    }

    @Override
    public List<String> validateArgs(Map<String, Object> args) {
        List<String> errors = new ArrayList<>();
        String codeQuery = args.get("codeQuery") == null ? "" : String.valueOf(args.get("codeQuery"));
        String naturalLanguageQuery = args.get("naturalLanguageQuery") == null ? "" : String.valueOf(args.get("naturalLanguageQuery"));
        if (codeQuery.isBlank() && naturalLanguageQuery.isBlank()) {
            errors.add("codeQuery 和 naturalLanguageQuery 不能同时为空");
        }
        return errors;
    }

    @Tool(description = "并发执行双向量库检索：codeQuery 用于代码向量库，naturalLanguageQuery 用于注释文档向量库。适合需要同时拿实现细节和语义说明的场景。")
    public List<AgentContextItem> searchHybridVector(
            @ToolParam(description = "代码导向查询（如代码片段、符号名、报错栈）") String codeQuery,
            @ToolParam(description = "自然语言查询（如意图说明、行为描述、概念问题）") String naturalLanguageQuery,
            @ToolParam(description = "每个向量库最多返回多少条，建议 3-10") Integer limit
    ) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        int finalLimit = limit == null ? context.defaultLimit() : limit;

        CompletableFuture<List<AgentContextItem>> codeFuture = CompletableFuture.supplyAsync(
                () -> codeVectorContextProvider.retrieveByCode(context.repoRoot(), codeQuery, finalLimit)
        );
        CompletableFuture<List<AgentContextItem>> commentFuture = CompletableFuture.supplyAsync(
                () -> commentVectorContextProvider.retrieveByComment(context.repoRoot(), naturalLanguageQuery, finalLimit)
        );

        List<AgentContextItem> merged = new ArrayList<>();
        merged.addAll(codeFuture.join());
        merged.addAll(commentFuture.join());
        return merged;
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
