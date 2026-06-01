package com.lumisight.core.config;

import com.lumisight.core.port.CodeVectorContextProvider;
import com.lumisight.core.port.CommentVectorContextProvider;
import com.lumisight.core.port.KnowledgeGraphContextProvider;
import com.lumisight.core.port.KnowledgeGraphOneHopProvider;
import com.lumisight.core.port.SourceCodeLookupProvider;
import com.lumisight.core.service.NoopCodeVectorContextProvider;
import com.lumisight.core.service.NoopCommentVectorContextProvider;
import com.lumisight.core.service.NoopKnowledgeGraphContextProvider;
import com.lumisight.core.service.NoopKnowledgeGraphOneHopProvider;
import com.lumisight.core.service.SourceCodeLookupProviderImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

@Configuration
public class AgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SourceCodeLookupProvider.class)
    public SourceCodeLookupProvider sourceCodeLookupProvider() {
        return new SourceCodeLookupProviderImpl();
    }

    @Bean
    @ConditionalOnMissingBean(CodeVectorContextProvider.class)
    public CodeVectorContextProvider codeVectorContextProvider() {
        return new NoopCodeVectorContextProvider();
    }

    @Bean
    @ConditionalOnMissingBean(CommentVectorContextProvider.class)
    public CommentVectorContextProvider commentVectorContextProvider() {
        return new NoopCommentVectorContextProvider();
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeGraphContextProvider.class)
    public KnowledgeGraphContextProvider knowledgeGraphContextProvider() {
        return new NoopKnowledgeGraphContextProvider();
    }

    @Bean
    @ConditionalOnMissingBean(KnowledgeGraphOneHopProvider.class)
    public KnowledgeGraphOneHopProvider knowledgeGraphOneHopProvider() {
        return new NoopKnowledgeGraphOneHopProvider();
    }
}
