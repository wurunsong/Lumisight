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

    @Tool(description = "Search in code vector store. Use this when the user input contains code snippets, stack traces, symbols, or implementation-level details.")
    public List<AgentContextItem> searchCodeVector(
            @ToolParam(description = "Absolute repository root path") String repoRoot,
            @ToolParam(description = "Code-oriented query content") String query,
            @ToolParam(description = "Max number of contexts to retrieve") Integer limit
    ) {
        return codeVectorContextProvider.retrieveByCode(repoRoot, query, limit);
    }

    @Tool(description = "Search in comment/documentation vector store. Use this when the user asks in natural language about concepts, behavior, intent, or design.")
    public List<AgentContextItem> searchCommentVector(
            @ToolParam(description = "Absolute repository root path") String repoRoot,
            @ToolParam(description = "Natural language query") String query,
            @ToolParam(description = "Max number of contexts to retrieve") Integer limit
    ) {
        return commentVectorContextProvider.retrieveByComment(repoRoot, query, limit);
    }
}
