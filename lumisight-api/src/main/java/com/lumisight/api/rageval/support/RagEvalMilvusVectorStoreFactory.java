package com.lumisight.api.rageval.support;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RagEvalMilvusVectorStoreFactory {

    private final EmbeddingModel embeddingModel;
    private final RagEvalMilvusProperties properties;

    public RagEvalMilvusVectorStoreFactory(EmbeddingModel embeddingModel, RagEvalMilvusProperties properties) {
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String collectionName() {
        return properties.getCollectionName();
    }

    public VectorStore create() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("RAG eval Milvus backend is disabled. Set lumisight.rag-eval.milvus.enabled=true to use backend=milvus.");
        }
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withDatabaseName(properties.getDatabaseName())
                .withSecure(properties.isSecure());
        if (StringUtils.hasText(properties.getToken())) {
            builder.withToken(properties.getToken());
        } else if (StringUtils.hasText(properties.getUsername()) || StringUtils.hasText(properties.getPassword())) {
            builder.withAuthorization(properties.getUsername(), properties.getPassword());
        }
        MilvusServiceClient client = new MilvusServiceClient(builder.build());
        MilvusVectorStore store = MilvusVectorStore.builder(client, embeddingModel)
                .collectionName(properties.getCollectionName())
                .initializeSchema(properties.isInitializeSchema())
                .build();
        if (properties.isClearBeforeRun()) {
            store.delete("doc_type == 'codesearchnet_eval_code_chunk'");
        }
        return store;
    }
}
