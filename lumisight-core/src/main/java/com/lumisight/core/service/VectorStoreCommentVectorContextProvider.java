package com.lumisight.core.service;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.port.CommentVectorContextProvider;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "lumisight.vector", name = "enabled", havingValue = "true")
public class VectorStoreCommentVectorContextProvider implements CommentVectorContextProvider {

    private final VectorStore symbolDocVectorStore;

    public VectorStoreCommentVectorContextProvider(@Qualifier("symbolDocVectorStore") VectorStore symbolDocVectorStore) {
        this.symbolDocVectorStore = symbolDocVectorStore;
    }

    @Override
    public List<AgentContextItem> retrieveByComment(String repoRoot, String naturalLanguageQuery, Integer limit) {
        if (naturalLanguageQuery == null || naturalLanguageQuery.isBlank()) {
            return List.of();
        }
        int topK = limit == null ? 5 : Math.max(1, Math.min(limit, 20));
        String normalizedRepoRoot = Path.of(repoRoot).toAbsolutePath().normalize().toString();
        List<Document> docs = symbolDocVectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(naturalLanguageQuery)
                        .topK(topK)
                        .similarityThresholdAll()
                        .filterExpression("repo_root == '" + escapeFilter(normalizedRepoRoot) + "'")
                        .build()
        );
        return docs.stream().map(this::toContextItem).toList();
    }

    private AgentContextItem toContextItem(Document document) {
        Map<String, Object> metadata = document.getMetadata() == null ? Map.of() : Map.copyOf(document.getMetadata());
        String sourceFile = String.valueOf(metadata.getOrDefault("source_file", ""));
        String qualifiedName = String.valueOf(metadata.getOrDefault("qualified_name", sourceFile));
        return new AgentContextItem(
                "comment_vector",
                qualifiedName,
                document.getText(),
                metadata
        );
    }

    private String escapeFilter(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }
}
