package com.lumisight.skills.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SkillCatalogProperties.class)
public class SkillCatalogConfig {
}

