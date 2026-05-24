package com.lumisight.api.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class AiConfigProbe {

    @Bean
    public ApplicationRunner aiKeyProbe(
            @Value("${spring.ai.openai.api-key:}") String chatApiKey,
            @Value("${spring.ai.openai.embedding.api-key:}") String embeddingApiKey
    ) {
        return args -> log.info("AI key probe, chatApiKeySet={}, embeddingApiKeySet={}",
                !chatApiKey.isBlank(), !embeddingApiKey.isBlank());
    }
}
