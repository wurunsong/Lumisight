package com.lumisight.core.config;

import io.milvus.client.MilvusServiceClient;
import com.lumisight.tools.vector.service.CodeChunkSplitter;
import com.lumisight.tools.vector.service.VectorIngestService;
import com.lumisight.tools.vector.spi.SymbolDocGenerator;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "lumisight.vector", name = "enabled", havingValue = "true")
public class VectorStoreConfig {

    @Bean("codeChunkVectorStore")
    public VectorStore codeChunkVectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("code_chunk_1024")
                .initializeSchema(true)
                .build();
    }

    @Bean("symbolDocVectorStore")
    public VectorStore symbolDocVectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("symbol_doc_1024")
                .initializeSchema(true)
                .build();
    }

    @Bean
    public VectorIngestService vectorIngestService(
            @Qualifier("codeChunkVectorStore") VectorStore codeChunkVectorStore,
            @Qualifier("symbolDocVectorStore") VectorStore symbolDocVectorStore,
            SymbolDocGenerator symbolDocGenerator,
            CodeChunkSplitter codeChunkSplitter
    ) {
        return new VectorIngestService(codeChunkVectorStore, symbolDocVectorStore, symbolDocGenerator, codeChunkSplitter);
    }
}
