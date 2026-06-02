package com.lumisight.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(LumisightAiProperties.class)
public class OpenAiClientTimeoutConfig {

    @Bean
    @Primary
    public RestClient.Builder lumisightOpenAiRestClientBuilder(LumisightAiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(Math.max(1, properties.getChatConnectTimeoutSeconds())));
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(1, properties.getChatReadTimeoutSeconds())));
        return RestClient.builder().requestFactory(requestFactory);
    }
}
