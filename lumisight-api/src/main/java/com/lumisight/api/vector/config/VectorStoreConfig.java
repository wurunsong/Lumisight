package com.lumisight.api.vector.config;

import io.milvus.client.MilvusServiceClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

    @Bean("codeChunkVectorStore")
    public VectorStore codeChunkVectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("code_chunk")
                .initializeSchema(true)
                .build();
    }

    @Bean("symbolDocVectorStore")
    public VectorStore symbolDocVectorStore(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("symbol_doc")
                .initializeSchema(true)
                .build();
    }
}
