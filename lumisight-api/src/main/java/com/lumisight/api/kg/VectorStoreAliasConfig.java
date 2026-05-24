package com.lumisight.api.kg;

import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class VectorStoreAliasConfig {

    @Bean("codeChunkVectorStore")
    public VectorStore codeChunkVectorStore(VectorStore vectorStore) {
        return vectorStore;
    }

    @Bean("symbolDocVectorStore")
    @Primary
    public VectorStore symbolDocVectorStore(VectorStore vectorStore) {
        return vectorStore;
    }
}
